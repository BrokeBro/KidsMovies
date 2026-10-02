package com.kidsmovies.app.sync

import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.kidsmovies.app.data.database.entities.Video
import com.kidsmovies.app.data.database.entities.VideoCollection
import com.kidsmovies.app.data.repository.CollectionRepository
import com.kidsmovies.app.data.repository.VideoRepository
import com.kidsmovies.app.pairing.PairingDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ContentSyncManager(
    private val pairingDao: PairingDao,
    private val videoRepository: VideoRepository,
    private val collectionRepository: CollectionRepository,
    private val coroutineScope: CoroutineScope
) {
    private val database = FirebaseDatabase.getInstance()

    private var syncRequestListener: ValueEventListener? = null
    private var locksListener: ValueEventListener? = null
    private var appLockListener: ValueEventListener? = null
    private var scheduleListener: ValueEventListener? = null
    private var timeLimitsListener: ValueEventListener? = null
    private var videosStatusListener: ValueEventListener? = null
    private var collectionsStatusListener: ValueEventListener? = null
    private var deviceSettingsListener: ValueEventListener? = null
    private var currentFamilyId: String? = null
    private var currentChildUid: String? = null

    private val _pendingLocks = MutableStateFlow<List<PendingLock>>(emptyList())
    val pendingLocks: StateFlow<List<PendingLock>> = _pendingLocks

    private val _lockWarning = MutableStateFlow<LockWarning?>(null)
    val lockWarning: StateFlow<LockWarning?> = _lockWarning

    // App-level lock state
    private val _appLock = MutableStateFlow<AppLockState?>(null)
    val appLock: StateFlow<AppLockState?> = _appLock

    // Schedule settings
    private val _scheduleSettings = MutableStateFlow<ScheduleState?>(null)
    val scheduleSettings: StateFlow<ScheduleState?> = _scheduleSettings

    // Time limit settings
    private val _timeLimitSettings = MutableStateFlow<TimeLimitState?>(null)
    val timeLimitSettings: StateFlow<TimeLimitState?> = _timeLimitSettings

    // Cloud video access toggle (per-device, set by parent)
    private val _cloudVideosEnabled = MutableStateFlow(true)
    val cloudVideosEnabled: StateFlow<Boolean> = _cloudVideosEnabled

    // Parent-set content rating override (null = parent hasn't set, child controls locally)
    private val _parentMaxContentRating = MutableStateFlow<String?>(null)
    val parentMaxContentRating: StateFlow<String?> = _parentMaxContentRating

    // Track currently watching video for "finish current video" feature
    private var currentlyWatchingTitle: String? = null
    private var isWatchingVideo: Boolean = false

    // Viewing metrics tracking
    private var sessionStartTime: Long = 0
    private var todayWatchTimeMinutes: Long = 0
    private var weekWatchTimeMinutes: Long = 0
    private var totalWatchTimeMinutes: Long = 0
    private var videosWatchedToday: Int = 0
    private var lastWatchDate: String = ""

    // Locks waiting for video to finish (allowFinishCurrentVideo = true)
    private val _locksWaitingForVideoEnd = MutableStateFlow<List<PendingLock>>(emptyList())
    val locksWaitingForVideoEnd: StateFlow<List<PendingLock>> = _locksWaitingForVideoEnd

    companion object {
        private const val TAG = "ContentSyncManager"
    }

    data class LockWarning(
        val title: String,
        val isVideo: Boolean, // true = video, false = collection
        val minutesRemaining: Int,
        val appliesAt: Long,
        val allowFinishCurrentVideo: Boolean = false, // Can finish current video before lock
        val isLastOne: Boolean = false // Warning period expired, this is the last video
    )

    data class AppLockState(
        val isLocked: Boolean,
        val message: String,
        val unlockAt: Long?,
        val warningMinutes: Int,
        val appliesAt: Long,
        val allowFinishCurrentVideo: Boolean
    )

    data class ScheduleState(
        val enabled: Boolean,
        val isCurrentlyAllowed: Boolean,
        val nextAllowedTime: Long?,
        val message: String
    )

    data class TimeLimitState(
        val enabled: Boolean,
        val dailyLimitMinutes: Int,
        val remainingMinutes: Int,
        val isLimitReached: Boolean
    )

    /**
     * Start listening for sync requests and lock commands
     */
    fun startListening() {
        coroutineScope.launch(Dispatchers.IO) {
            val pairingState = pairingDao.getPairingState()
            if (pairingState == null || !pairingState.isPaired) {
                Log.d(TAG, "Not paired, skipping content sync")
                return@launch
            }

            val familyId = pairingState.familyId ?: return@launch
            val childUid = pairingState.childUid ?: return@launch

            if (currentFamilyId != familyId) {
                stopListening()
            }

            currentFamilyId = familyId
            currentChildUid = childUid

            listenForSyncRequests(familyId, childUid)
            listenForLockCommands(familyId, childUid)
            listenForAppLock(familyId, childUid)
            listenForScheduleSettings(familyId, childUid)
            listenForTimeLimitSettings(familyId, childUid)
            listenForVideoStatusChanges(familyId, childUid)
            listenForCollectionStatusChanges(familyId, childUid)
            listenForDeviceSettings(familyId, childUid)
            loadViewingMetrics(familyId, childUid)
        }
    }

    private fun listenForAppLock(familyId: String, childUid: String) {
        val appLockRef = database.getReference("families/$familyId/children/$childUid/appLock")

        appLockListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // Note: Firebase serializes 'isLocked' as 'locked' (drops the 'is' prefix)
                val isLocked = snapshot.child("locked").getValue(Boolean::class.java) ?: false
                val message = snapshot.child("message").getValue(String::class.java) ?: "App is locked by parent"
                val unlockAt = snapshot.child("unlockAt").getValue(Long::class.java)
                val warningMinutes = snapshot.child("warningMinutes").getValue(Int::class.java) ?: 0
                val lockedAt = snapshot.child("lockedAt").getValue(Long::class.java) ?: System.currentTimeMillis()
                val allowFinishCurrentVideo = snapshot.child("allowFinishCurrentVideo").getValue(Boolean::class.java) ?: false

                val appliesAt = lockedAt + (warningMinutes * 60 * 1000)

                _appLock.value = AppLockState(
                    isLocked = isLocked,
                    message = message,
                    unlockAt = unlockAt,
                    warningMinutes = warningMinutes,
                    appliesAt = appliesAt,
                    allowFinishCurrentVideo = allowFinishCurrentVideo
                )

                Log.d(TAG, "App lock state updated: isLocked=$isLocked")
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "App lock listener cancelled", error.toException())
            }
        }

        appLockRef.addValueEventListener(appLockListener!!)
    }

    private fun listenForScheduleSettings(familyId: String, childUid: String) {
        val scheduleRef = database.getReference("families/$familyId/children/$childUid/settings/schedule")

        scheduleListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val enabled = snapshot.child("enabled").getValue(Boolean::class.java) ?: false
                if (!enabled) {
                    _scheduleSettings.value = ScheduleState(
                        enabled = false,
                        isCurrentlyAllowed = true,
                        nextAllowedTime = null,
                        message = ""
                    )
                    return
                }

                val allowedDays = snapshot.child("allowedDays").children.mapNotNull {
                    it.getValue(Int::class.java)
                }
                val startHour = snapshot.child("allowedStartHour").getValue(Int::class.java) ?: 8
                val startMinute = snapshot.child("allowedStartMinute").getValue(Int::class.java) ?: 0
                val endHour = snapshot.child("allowedEndHour").getValue(Int::class.java) ?: 20
                val endMinute = snapshot.child("allowedEndMinute").getValue(Int::class.java) ?: 0

                val calendar = Calendar.getInstance()
                val currentDay = calendar.get(Calendar.DAY_OF_WEEK) - 1 // Convert to 0-based (Sunday = 0)
                val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
                val currentMinute = calendar.get(Calendar.MINUTE)

                val currentTimeMinutes = currentHour * 60 + currentMinute
                val startTimeMinutes = startHour * 60 + startMinute
                val endTimeMinutes = endHour * 60 + endMinute

                val isDayAllowed = allowedDays.isEmpty() || allowedDays.contains(currentDay)
                val isTimeAllowed = currentTimeMinutes in startTimeMinutes until endTimeMinutes

                val isAllowed = isDayAllowed && isTimeAllowed

                val message = if (!isAllowed) {
                    if (!isDayAllowed) {
                        "App is not available today"
                    } else if (currentTimeMinutes < startTimeMinutes) {
                        "App available from ${formatTime(startHour, startMinute)}"
                    } else {
                        "App time is over for today"
                    }
                } else ""

                _scheduleSettings.value = ScheduleState(
                    enabled = true,
                    isCurrentlyAllowed = isAllowed,
                    nextAllowedTime = null,
                    message = message
                )

                Log.d(TAG, "Schedule settings updated: enabled=$enabled, allowed=$isAllowed")
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Schedule listener cancelled", error.toException())
            }
        }

        scheduleRef.addValueEventListener(scheduleListener!!)
    }

    private fun listenForTimeLimitSettings(familyId: String, childUid: String) {
        val timeLimitsRef = database.getReference("families/$familyId/children/$childUid/settings/timeLimits")

        timeLimitsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val enabled = snapshot.child("enabled").getValue(Boolean::class.java) ?: false
                val dailyLimitMinutes = snapshot.child("dailyLimitMinutes").getValue(Int::class.java) ?: 120

                if (!enabled || dailyLimitMinutes == 0) {
                    _timeLimitSettings.value = TimeLimitState(
                        enabled = false,
                        dailyLimitMinutes = 0,
                        remainingMinutes = Int.MAX_VALUE,
                        isLimitReached = false
                    )
                    return
                }

                val remaining = (dailyLimitMinutes - todayWatchTimeMinutes).coerceAtLeast(0).toInt()
                val isReached = remaining <= 0

                _timeLimitSettings.value = TimeLimitState(
                    enabled = true,
                    dailyLimitMinutes = dailyLimitMinutes,
                    remainingMinutes = remaining,
                    isLimitReached = isReached
                )

                Log.d(TAG, "Time limits updated: enabled=$enabled, remaining=$remaining minutes")
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Time limits listener cancelled", error.toException())
            }
        }

        timeLimitsRef.addValueEventListener(timeLimitsListener!!)
    }

    private fun loadViewingMetrics(familyId: String, childUid: String) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val metricsRef = database.getReference("families/$familyId/children/$childUid/metrics")
                val snapshot = metricsRef.get().await()

                todayWatchTimeMinutes = snapshot.child("todayWatchTimeMinutes").getValue(Long::class.java) ?: 0
                weekWatchTimeMinutes = snapshot.child("weekWatchTimeMinutes").getValue(Long::class.java) ?: 0
                totalWatchTimeMinutes = snapshot.child("totalWatchTimeMinutes").getValue(Long::class.java) ?: 0
                videosWatchedToday = snapshot.child("videosWatchedToday").getValue(Int::class.java) ?: 0
                lastWatchDate = snapshot.child("lastWatchDate").getValue(String::class.java) ?: ""

                // Reset today's metrics if it's a new day
                val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                if (lastWatchDate != todayDate) {
                    todayWatchTimeMinutes = 0
                    videosWatchedToday = 0
                }

                Log.d(TAG, "Loaded viewing metrics: today=$todayWatchTimeMinutes min")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load viewing metrics", e)
            }
        }
    }

    /**
     * Listen for video enabled/hidden status changes from Firebase.
     * This provides a backup sync mechanism when the lock commands path is missed.
     */
    private fun listenForVideoStatusChanges(familyId: String, childUid: String) {
        val videosRef = database.getReference("families/$familyId/children/$childUid/videos")

        videosStatusListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                coroutineScope.launch(Dispatchers.IO) {
                    // Locks still in their warning period are applied by the pending-lock timer, not here
                    val deferred = readDeferredLockIds(familyId, childUid)
                    for (videoSnapshot in snapshot.children) {
                        val videoKey = videoSnapshot.key ?: continue
                        // Note: Firebase serializes 'isEnabled' as 'enabled' (drops the 'is' prefix)
                        val enabled = videoSnapshot.child("enabled").getValue(Boolean::class.java) ?: true
                        val hidden = videoSnapshot.child("hidden").getValue(Boolean::class.java) ?: false

                        // Resolve by stable local ID (key is the video's local ID)
                        val localId = videoSnapshot.child("localId").getValue(Long::class.java)
                            ?: videoKey.toLongOrNull()
                        val video = if (localId != null) {
                            videoRepository.getVideoById(localId)
                        } else {
                            // Fallback for legacy data keyed by sanitized title
                            val title = videoSnapshot.child("title").getValue(String::class.java) ?: continue
                            videoRepository.getVideoByTitle(title)
                        }

                        if (video != null) {
                            val deferLock = !enabled && video.id in deferred.videoIds
                            if (video.isEnabled != enabled && !deferLock) {
                                videoRepository.updateEnabled(video.id, enabled)
                                Log.d(TAG, "Synced video enabled status from Firebase: ${video.title}, enabled=$enabled")
                            }
                            if (video.isHidden != hidden) {
                                videoRepository.updateHidden(video.id, hidden)
                                Log.d(TAG, "Synced video hidden status from Firebase: ${video.title}, hidden=$hidden")
                            }
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Videos status listener cancelled", error.toException())
            }
        }

        videosRef.addValueEventListener(videosStatusListener!!)
    }

    /**
     * Listen for collection enabled/hidden status changes from Firebase.
     */
    private fun listenForCollectionStatusChanges(familyId: String, childUid: String) {
        val collectionsRef = database.getReference("families/$familyId/children/$childUid/collections")

        collectionsStatusListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                coroutineScope.launch(Dispatchers.IO) {
                    val deferred = readDeferredLockIds(familyId, childUid)
                    for (collectionSnapshot in snapshot.children) {
                        val collectionKey = collectionSnapshot.key ?: continue
                        // Note: Firebase serializes 'isEnabled' as 'enabled' (drops the 'is' prefix)
                        val enabled = collectionSnapshot.child("enabled").getValue(Boolean::class.java) ?: true
                        val hidden = collectionSnapshot.child("hidden").getValue(Boolean::class.java) ?: false

                        // Resolve by stable local ID (key is the collection's local ID)
                        val localId = collectionSnapshot.child("localId").getValue(Long::class.java)
                            ?: collectionKey.toLongOrNull()
                        val collection = if (localId != null) {
                            collectionRepository.getCollectionById(localId)
                        } else {
                            // Fallback for legacy data keyed by sanitized name
                            val name = collectionSnapshot.child("name").getValue(String::class.java) ?: continue
                            collectionRepository.getCollectionByName(name)
                        }

                        if (collection != null) {
                            val deferLock = !enabled && collection.id in deferred.collectionIds
                            if (collection.isEnabled != enabled && !deferLock) {
                                collectionRepository.updateEnabled(collection.id, enabled)
                                Log.d(TAG, "Synced collection enabled status from Firebase: ${collection.name}, enabled=$enabled")
                            }
                            if (collection.isHidden != hidden) {
                                collectionRepository.updateHidden(collection.id, hidden)
                                Log.d(TAG, "Synced collection hidden status from Firebase: ${collection.name}, hidden=$hidden")
                            }
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Collections status listener cancelled", error.toException())
            }
        }

        collectionsRef.addValueEventListener(collectionsStatusListener!!)
    }

    /**
     * Listen for per-device settings changes (e.g. cloud videos enabled/disabled).
     */
    private fun listenForDeviceSettings(familyId: String, childUid: String) {
        val settingsRef = database.getReference("families/$familyId/children/$childUid/deviceSettings")

        deviceSettingsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val cloudEnabled = snapshot.child("cloudVideosEnabled").getValue(Boolean::class.java) ?: true
                _cloudVideosEnabled.value = cloudEnabled

                // Content rating override from parent
                val maxRating = snapshot.child("maxContentRating").getValue(String::class.java)
                val previousRating = _parentMaxContentRating.value
                _parentMaxContentRating.value = maxRating

                Log.d(TAG, "Device settings updated: cloudVideosEnabled=$cloudEnabled, maxContentRating=$maxRating")

                // If rating changed, notify listeners (KidsMoviesApp will re-evaluate artwork)
                if (maxRating != previousRating) {
                    Log.d(TAG, "Parent content rating changed: $previousRating -> $maxRating")
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Device settings listener cancelled", error.toException())
            }
        }

        settingsRef.addValueEventListener(deviceSettingsListener!!)
    }

    private fun formatTime(hour: Int, minute: Int): String {
        val amPm = if (hour < 12) "AM" else "PM"
        val hour12 = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
        return String.format("%d:%02d %s", hour12, minute, amPm)
    }

    private fun listenForSyncRequests(familyId: String, childUid: String) {
        val syncRequestRef = database.getReference("families/$familyId/children/$childUid/syncRequest")

        syncRequestListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val requested = snapshot.child("requested").getValue(Boolean::class.java) ?: false
                if (requested) {
                    Log.d(TAG, "Sync request received from parent")
                    coroutineScope.launch(Dispatchers.IO) {
                        performFullSync()
                        // Clear the sync request flag
                        syncRequestRef.child("requested").setValue(false)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Sync request listener cancelled", error.toException())
            }
        }

        syncRequestRef.addValueEventListener(syncRequestListener!!)
    }

    private fun listenForLockCommands(familyId: String, childUid: String) {
        val locksRef = database.getReference("families/$familyId/children/$childUid/locks")

        locksListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                coroutineScope.launch(Dispatchers.IO) {
                    processLockCommands(snapshot)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Locks listener cancelled", error.toException())
            }
        }

        locksRef.addValueEventListener(locksListener!!)
    }

    private suspend fun processLockCommands(snapshot: DataSnapshot) {
        val newPendingLocks = mutableListOf<PendingLock>()
        val now = System.currentTimeMillis()

        for (lockSnapshot in snapshot.children) {
            val lockId = lockSnapshot.key ?: continue
            val videoTitle = lockSnapshot.child("videoTitle").getValue(String::class.java)
            val videoId = lockSnapshot.child("videoId").getValue(Long::class.java)
            val collectionName = lockSnapshot.child("collectionName").getValue(String::class.java)
            val collectionId = lockSnapshot.child("collectionId").getValue(Long::class.java)
            // Note: Firebase serializes 'isLocked' as 'locked' (drops the 'is' prefix)
            val isLocked = lockSnapshot.child("locked").getValue(Boolean::class.java) ?: false
            val warningMinutes = lockSnapshot.child("warningMinutes").getValue(Int::class.java) ?: 5
            val lockedAt = lockSnapshot.child("lockedAt").getValue(Long::class.java) ?: now
            val allowFinishCurrentVideo = lockSnapshot.child("allowFinishCurrentVideo").getValue(Boolean::class.java) ?: false

            if (isLocked) {
                val appliesAt = lockedAt + (warningMinutes * 60 * 1000)

                if (appliesAt <= now) {
                    // Warning time has passed (or was immediate)
                    if (allowFinishCurrentVideo && isWatchingVideo) {
                        // Child is watching a video and allowed to finish - add to waiting list
                        val waitingLock = PendingLock(
                            videoTitle = videoTitle,
                            videoId = videoId,
                            collectionName = collectionName,
                            collectionId = collectionId,
                            appliesAt = appliesAt,
                            warningMinutes = warningMinutes,
                            allowFinishCurrentVideo = true
                        )
                        val currentWaiting = _locksWaitingForVideoEnd.value.toMutableList()
                        if (!currentWaiting.any { it.videoTitle == videoTitle && it.collectionName == collectionName }) {
                            currentWaiting.add(waitingLock)
                            _locksWaitingForVideoEnd.value = currentWaiting
                        }

                        // Show "last one" warning - child can finish this video
                        val title = videoTitle ?: collectionName ?: "Content"
                        _lockWarning.value = LockWarning(
                            title = title,
                            isVideo = videoTitle != null,
                            minutesRemaining = 0,
                            appliesAt = appliesAt,
                            allowFinishCurrentVideo = true,
                            isLastOne = true
                        )
                    } else {
                        // Apply the lock immediately (no finish allowed, or not watching)
                        applyLock(videoTitle, collectionName, true, videoId, collectionId)
                        // Remove the processed lock command
                        removeLockCommand(lockId)
                    }
                } else {
                    // Add to pending locks with warning
                    newPendingLocks.add(
                        PendingLock(
                            videoTitle = videoTitle,
                            videoId = videoId,
                            collectionName = collectionName,
                            collectionId = collectionId,
                            appliesAt = appliesAt,
                            warningMinutes = warningMinutes,
                            allowFinishCurrentVideo = allowFinishCurrentVideo
                        )
                    )

                    // Update warning state
                    val minutesRemaining = ((appliesAt - now) / 60000).toInt()
                    val title = videoTitle ?: collectionName ?: "Content"
                    _lockWarning.value = LockWarning(
                        title = title,
                        isVideo = videoTitle != null,
                        minutesRemaining = minutesRemaining,
                        appliesAt = appliesAt,
                        allowFinishCurrentVideo = allowFinishCurrentVideo,
                        isLastOne = false
                    )
                }
            } else {
                // Unlock command - apply immediately
                applyLock(videoTitle, collectionName, false, videoId, collectionId)
                removeLockCommand(lockId)
            }
        }

        _pendingLocks.value = newPendingLocks
    }

    private suspend fun removeLockCommand(lockId: String) {
        val familyId = currentFamilyId ?: return
        val childUid = currentChildUid ?: return

        try {
            database.getReference("families/$familyId/children/$childUid/locks/$lockId")
                .removeValue().await()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove lock command", e)
        }
    }

    /**
     * Apply lock/unlock to video or collection.
     * A collection lock cascades to its videos (and a TV show's seasons/episodes), but each child takes
     * the parent app's per-item state from Firebase when known. That preserves videos the parent
     * individually unlocked inside a locked collection (and vice versa) instead of overwriting them.
     * Resolves by stable ID first, falling back to title/name for legacy data.
     */
    private suspend fun applyLock(
        videoTitle: String?,
        collectionName: String?,
        isLocked: Boolean,
        videoId: Long? = null,
        collectionId: Long? = null
    ) {
        val enabled = !isLocked

        if (videoTitle != null || videoId != null) {
            // Resolve by ID first, fallback to title
            val video = videoId?.let { videoRepository.getVideoById(it) }
                ?: videoTitle?.let { videoRepository.getVideoByTitle(it) }
            if (video != null) {
                videoRepository.updateEnabled(video.id, enabled)
                Log.d(TAG, "Applied lock to video: ${video.title} (id=${video.id}), enabled=$enabled")
            } else {
                Log.w(TAG, "Video not found for lock: id=$videoId, title=$videoTitle")
            }
        }

        if (collectionName != null || collectionId != null) {
            // Resolve by ID first, fallback to name
            val collection = collectionId?.let { collectionRepository.getCollectionById(it) }
                ?: collectionName?.let { collectionRepository.getCollectionByName(it) }
            if (collection != null) {
                collectionRepository.updateEnabled(collection.id, enabled)
                Log.d(TAG, "Applied lock to collection: ${collection.name} (id=${collection.id}), enabled=$enabled")

                val remoteVideos = readRemoteEnabled("videos")
                val remoteCollections = readRemoteEnabled("collections")

                suspend fun cascadeToVideos(ofCollectionId: Long) {
                    for (video in collectionRepository.getVideosInCollection(ofCollectionId)) {
                        val target = remoteVideos[video.id] ?: enabled
                        if (video.isEnabled != target) {
                            videoRepository.updateEnabled(video.id, target)
                            Log.d(TAG, "Cascaded lock to video: ${video.title}, enabled=$target")
                        }
                    }
                }

                cascadeToVideos(collection.id)

                // If this is a TV show, also cascade to its seasons and their episodes
                if (collection.isTvShow()) {
                    for (season in collectionRepository.getSubCollections(collection.id)) {
                        val target = remoteCollections[season.id] ?: enabled
                        if (season.isEnabled != target) {
                            collectionRepository.updateEnabled(season.id, target)
                            Log.d(TAG, "Cascaded lock to season: ${season.name}, enabled=$target")
                        }
                        cascadeToVideos(season.id)
                    }
                }
            } else {
                Log.w(TAG, "Collection not found for lock: id=$collectionId, name=$collectionName")
            }
        }

        // Clear warning if this was the content being warned about
        _lockWarning.value?.let { warning ->
            if (warning.title == (videoTitle ?: collectionName)) {
                _lockWarning.value = null
            }
        }
    }

    /**
     * Check and apply any pending locks that have passed their warning period
     */
    suspend fun checkPendingLocks() {
        val now = System.currentTimeMillis()
        val locks = _pendingLocks.value.toMutableList()
        val toRemove = mutableListOf<PendingLock>()
        val toWaitForVideoEnd = mutableListOf<PendingLock>()

        for (lock in locks) {
            if (lock.appliesAt <= now) {
                if (lock.allowFinishCurrentVideo && isWatchingVideo) {
                    // Child is watching and allowed to finish - add to waiting list
                    toWaitForVideoEnd.add(lock)
                    toRemove.add(lock)

                    // Show "last one" warning - child can finish this video
                    val title = lock.videoTitle ?: lock.collectionName ?: "Content"
                    _lockWarning.value = LockWarning(
                        title = title,
                        isVideo = lock.videoTitle != null,
                        minutesRemaining = 0,
                        appliesAt = lock.appliesAt,
                        allowFinishCurrentVideo = true,
                        isLastOne = true
                    )
                } else {
                    // Timer expired - apply lock immediately
                    // This includes: not watching, or watching but not allowed to finish
                    applyLock(lock.videoTitle, lock.collectionName, true, lock.videoId, lock.collectionId)
                    // Remove the command, otherwise it lingers and is re-applied on every later lock change
                    removeLockCommand(lock.lockId())
                    toRemove.add(lock)

                    // Signal that lock should be enforced now (minutesRemaining=0, not deferrable)
                    val title = lock.videoTitle ?: lock.collectionName ?: "Content"
                    _lockWarning.value = LockWarning(
                        title = title,
                        isVideo = lock.videoTitle != null,
                        minutesRemaining = 0,
                        appliesAt = lock.appliesAt,
                        allowFinishCurrentVideo = false,
                        isLastOne = false
                    )
                }
            } else {
                // Update warning time
                val minutesRemaining = ((lock.appliesAt - now) / 60000).toInt()
                val title = lock.videoTitle ?: lock.collectionName ?: "Content"
                _lockWarning.value = LockWarning(
                    title = title,
                    isVideo = lock.videoTitle != null,
                    minutesRemaining = minutesRemaining,
                    appliesAt = lock.appliesAt,
                    allowFinishCurrentVideo = lock.allowFinishCurrentVideo,
                    isLastOne = false
                )
            }
        }

        // Add to waiting list
        if (toWaitForVideoEnd.isNotEmpty()) {
            val currentWaiting = _locksWaitingForVideoEnd.value.toMutableList()
            currentWaiting.addAll(toWaitForVideoEnd)
            _locksWaitingForVideoEnd.value = currentWaiting
        }

        locks.removeAll(toRemove)
        _pendingLocks.value = locks

        if (locks.isEmpty() && _locksWaitingForVideoEnd.value.isEmpty()) {
            _lockWarning.value = null
        }
    }

    /**
     * Perform a full content sync to Firebase
     */
    suspend fun performFullSync() {
        val familyId = currentFamilyId ?: run {
            val pairingState = pairingDao.getPairingState()
            pairingState?.familyId
        } ?: return

        val childUid = currentChildUid ?: run {
            val pairingState = pairingDao.getPairingState()
            pairingState?.childUid
        } ?: return

        Log.d(TAG, "Performing full content sync")

        try {
            // Upload device info
            uploadDeviceInfo(familyId, childUid)

            // Upload video list
            uploadVideoList(familyId, childUid)

            // Upload collection list
            uploadCollectionList(familyId, childUid)

            Log.d(TAG, "Full content sync completed")
        } catch (e: Exception) {
            Log.e(TAG, "Content sync failed", e)
        }
    }

    private suspend fun uploadDeviceInfo(familyId: String, childUid: String) {
        val pairingState = pairingDao.getPairingState() ?: return

        val deviceInfo = SyncedChildDevice(
            deviceName = pairingState.deviceName,
            childUid = childUid,
            lastSeen = System.currentTimeMillis(),
            appVersion = "1.0", // TODO: Get from BuildConfig
            isOnline = true,
            currentlyWatching = null, // Will be updated when video plays
            todayWatchTime = 0 // TODO: Calculate from viewing sessions
        )

        database.getReference("families/$familyId/children/$childUid/deviceInfo")
            .setValue(deviceInfo).await()
    }

    /**
     * Upload the video list. Lock/hide flags are owned by the parent app once a video exists in
     * Firebase, so existing entries only have their descriptive fields updated; overwriting the whole
     * node used to replace the parent's locks with this device's (possibly stale) local state.
     */
    private suspend fun uploadVideoList(familyId: String, childUid: String) {
        val videos = videoRepository.getAllVideos()
        val collections = collectionRepository.getAllCollections()
        val collectionsById = collections.associateBy { it.id }
        val videosRef = database.getReference("families/$familyId/children/$childUid/videos")
        val remoteKeys = videosRef.get().await().children.mapNotNull { it.key }.toSet()

        val updates = mutableMapOf<String, Any?>()
        val localKeys = mutableSetOf<String>()

        for (video in videos) {
            val videoCollections = collectionRepository.getCollectionsForVideo(video.id)
            val key = video.id.toString()
            localKeys.add(key)

            if (key in remoteKeys) {
                val base = "$key/"
                updates[base + "localId"] = video.id
                updates[base + "title"] = video.title
                updates[base + "collectionNames"] = videoCollections.map { it.name }
                updates[base + "collectionIds"] = videoCollections.map { it.id }
                updates[base + "favourite"] = video.isFavourite
                updates[base + "duration"] = video.duration
                updates[base + "playbackPosition"] = video.playbackPosition
                updates[base + "lastWatched"] = if (video.playbackPosition > 0) video.dateModified else null
                updates[base + "sourceType"] = video.sourceType
                updates[base + "remoteId"] = video.remoteId
            } else {
                // New video: inherit the lock of any locked collection (or locked parent show) it belongs to
                val inheritsLock = videoCollections.any { c ->
                    !c.isEnabled || c.parentCollectionId?.let { collectionsById[it]?.isEnabled == false } == true
                }
                val enabled = video.isEnabled && !inheritsLock
                if (enabled != video.isEnabled) videoRepository.updateEnabled(video.id, enabled)

                updates[key] = SyncedVideo(
                    localId = video.id,
                    title = video.title,
                    collectionNames = videoCollections.map { it.name },
                    collectionIds = videoCollections.map { it.id },
                    isFavourite = video.isFavourite,
                    isEnabled = enabled,
                    isHidden = video.isHidden,
                    duration = video.duration,
                    playbackPosition = video.playbackPosition,
                    lastWatched = if (video.playbackPosition > 0) video.dateModified else null,
                    thumbnailUrl = null, // Thumbnails are local, not synced
                    sourceType = video.sourceType,
                    remoteId = video.remoteId
                )
            }
        }

        // Remove videos no longer on this device
        (remoteKeys - localKeys).forEach { updates[it] = null }

        if (updates.isNotEmpty()) videosRef.updateChildren(updates).await()
    }

    /** Upload the collection list, preserving the parent's lock/hide flags on existing entries. */
    private suspend fun uploadCollectionList(familyId: String, childUid: String) {
        val collections = collectionRepository.getAllCollections()
        val collectionsById = collections.associateBy { it.id }
        val collectionsRef = database.getReference("families/$familyId/children/$childUid/collections")
        val remoteKeys = collectionsRef.get().await().children.mapNotNull { it.key }.toSet()

        val updates = mutableMapOf<String, Any?>()
        val localKeys = mutableSetOf<String>()

        for (collection in collections) {
            val videoCount = collectionRepository.getVideoCountInCollection(collection.id)
            val parentCollection = collection.parentCollectionId?.let { collectionsById[it] }
            val key = collection.id.toString()
            localKeys.add(key)

            if (key in remoteKeys) {
                val base = "$key/"
                updates[base + "localId"] = collection.id
                updates[base + "name"] = collection.name
                updates[base + "type"] = collection.collectionType
                updates[base + "parentName"] = parentCollection?.name
                updates[base + "parentId"] = collection.parentCollectionId
                updates[base + "videoCount"] = videoCount
            } else {
                updates[key] = SyncedCollection(
                    localId = collection.id,
                    name = collection.name,
                    type = collection.collectionType,
                    parentName = parentCollection?.name,
                    parentId = collection.parentCollectionId,
                    videoCount = videoCount,
                    isEnabled = collection.isEnabled,
                    isHidden = collection.isHidden,
                    thumbnailUrl = null
                )
            }
        }

        (remoteKeys - localKeys).forEach { updates[it] = null }

        if (updates.isNotEmpty()) collectionsRef.updateChildren(updates).await()
    }

    /**
     * Update currently watching status
     */
    suspend fun updateCurrentlyWatching(videoTitle: String?) {
        val wasWatching = isWatchingVideo
        currentlyWatchingTitle = videoTitle
        isWatchingVideo = videoTitle != null

        // If video ended and there are locks waiting, apply them now
        if (wasWatching && !isWatchingVideo) {
            applyWaitingLocks()
        }

        val familyId = currentFamilyId ?: return
        val childUid = currentChildUid ?: return

        try {
            database.getReference("families/$familyId/children/$childUid/deviceInfo/currentlyWatching")
                .setValue(videoTitle).await()

            database.getReference("families/$familyId/children/$childUid/deviceInfo/lastSeen")
                .setValue(System.currentTimeMillis()).await()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update currently watching status", e)
        }
    }

    /**
     * Apply any locks that were waiting for video to finish
     */
    private suspend fun applyWaitingLocks() {
        val waitingLocks = _locksWaitingForVideoEnd.value.toList()
        if (waitingLocks.isEmpty()) return

        Log.d(TAG, "Video ended, applying ${waitingLocks.size} waiting locks")

        for (lock in waitingLocks) {
            applyLock(lock.videoTitle, lock.collectionName, true, lock.videoId, lock.collectionId)
            removeLockCommand(lock.lockId())
        }

        // Clear waiting locks
        _locksWaitingForVideoEnd.value = emptyList()

        // Clear the "last one" warning
        _lockWarning.value = null
    }

    /**
     * Check if there are locks waiting for video to end
     */
    fun hasLocksWaitingForVideoEnd(): Boolean {
        return _locksWaitingForVideoEnd.value.isNotEmpty()
    }

    /**
     * Update video's enabled status in Firebase after local change
     */
    suspend fun syncVideoEnabledStatus(videoId: Long, isEnabled: Boolean) {
        val familyId = currentFamilyId ?: return
        val childUid = currentChildUid ?: return

        val key = videoId.toString()
        // Note: Firebase serializes 'isEnabled' as 'enabled' (drops the 'is' prefix)
        database.getReference("families/$familyId/children/$childUid/videos/$key/enabled")
            .setValue(isEnabled).await()
    }

    /**
     * Update collection's enabled status in Firebase after local change
     */
    suspend fun syncCollectionEnabledStatus(collectionId: Long, isEnabled: Boolean) {
        val familyId = currentFamilyId ?: return
        val childUid = currentChildUid ?: return

        val key = collectionId.toString()
        // Note: Firebase serializes 'isEnabled' as 'enabled' (drops the 'is' prefix)
        database.getReference("families/$familyId/children/$childUid/collections/$key/enabled")
            .setValue(isEnabled).await()
    }

    /**
     * Update video's hidden status in Firebase after local change
     */
    suspend fun syncVideoHiddenStatus(videoId: Long, isHidden: Boolean) {
        val familyId = currentFamilyId ?: return
        val childUid = currentChildUid ?: return

        val key = videoId.toString()
        // Note: Firebase serializes 'isHidden' as 'hidden' (drops the 'is' prefix)
        database.getReference("families/$familyId/children/$childUid/videos/$key/hidden")
            .setValue(isHidden).await()
    }

    /**
     * Update collection's hidden status in Firebase after local change
     */
    suspend fun syncCollectionHiddenStatus(collectionId: Long, isHidden: Boolean) {
        val familyId = currentFamilyId ?: return
        val childUid = currentChildUid ?: return

        val key = collectionId.toString()
        // Note: Firebase serializes 'isHidden' as 'hidden' (drops the 'is' prefix)
        database.getReference("families/$familyId/children/$childUid/collections/$key/hidden")
            .setValue(isHidden).await()
    }

    private data class DeferredLockIds(val videoIds: Set<Long>, val collectionIds: Set<Long>)

    /**
     * IDs with a lock command still in its warning period, or waiting for the current video to end.
     * Read from Firebase (not the in-memory pending list) because the status listeners can fire
     * before the locks listener for the same parent update.
     */
    private suspend fun readDeferredLockIds(familyId: String, childUid: String): DeferredLockIds {
        val videoIds = mutableSetOf<Long>()
        val collectionIds = mutableSetOf<Long>()
        val now = System.currentTimeMillis()
        try {
            val locks = database.getReference("families/$familyId/children/$childUid/locks").get().await()
            for (lock in locks.children) {
                // Note: Firebase serializes 'isLocked' as 'locked' (drops the 'is' prefix)
                if (lock.child("locked").getValue(Boolean::class.java) != true) continue
                val warningMinutes = lock.child("warningMinutes").getValue(Int::class.java) ?: 5
                val lockedAt = lock.child("lockedAt").getValue(Long::class.java) ?: now
                val allowFinish = lock.child("allowFinishCurrentVideo").getValue(Boolean::class.java) ?: false
                val deferred = lockedAt + warningMinutes * 60_000L > now || (allowFinish && isWatchingVideo)
                if (!deferred) continue
                lock.child("videoId").getValue(Long::class.java)?.let { videoIds.add(it) }
                lock.child("collectionId").getValue(Long::class.java)?.let { collectionIds.add(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read lock commands", e)
        }
        _locksWaitingForVideoEnd.value.forEach { lock ->
            lock.videoId?.let { videoIds.add(it) }
            lock.collectionId?.let { collectionIds.add(it) }
        }
        return DeferredLockIds(videoIds, collectionIds)
    }

    /** Parent-set enabled flags from Firebase ("videos" or "collections"), keyed by local ID. */
    private suspend fun readRemoteEnabled(node: String): Map<Long, Boolean> {
        val familyId = currentFamilyId ?: return emptyMap()
        val childUid = currentChildUid ?: return emptyMap()
        return try {
            database.getReference("families/$familyId/children/$childUid/$node").get().await()
                .children.mapNotNull { item ->
                    val id = item.child("localId").getValue(Long::class.java)
                        ?: item.key?.toLongOrNull() ?: return@mapNotNull null
                    val enabled = item.child("enabled").getValue(Boolean::class.java) ?: return@mapNotNull null
                    id to enabled
                }.toMap()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read remote $node state", e)
            emptyMap()
        }
    }

    /** Firebase key of the lock command this pending lock came from (matches the parent app's keys). */
    private fun PendingLock.lockId(): String =
        videoId?.let { "v_$it" } ?: collectionId?.let { "c_$it" } ?: ""

    private fun sanitizeFirebaseKey(key: String): String {
        // Firebase keys cannot contain . $ # [ ] /
        return key.replace(Regex("[.\\$#\\[\\]/]"), "_")
    }

    fun stopListening() {
        currentFamilyId?.let { familyId ->
            currentChildUid?.let { childUid ->
                syncRequestListener?.let {
                    database.getReference("families/$familyId/children/$childUid/syncRequest")
                        .removeEventListener(it)
                }
                locksListener?.let {
                    database.getReference("families/$familyId/children/$childUid/locks")
                        .removeEventListener(it)
                }
                appLockListener?.let {
                    database.getReference("families/$familyId/children/$childUid/appLock")
                        .removeEventListener(it)
                }
                scheduleListener?.let {
                    database.getReference("families/$familyId/children/$childUid/settings/schedule")
                        .removeEventListener(it)
                }
                timeLimitsListener?.let {
                    database.getReference("families/$familyId/children/$childUid/settings/timeLimits")
                        .removeEventListener(it)
                }
                videosStatusListener?.let {
                    database.getReference("families/$familyId/children/$childUid/videos")
                        .removeEventListener(it)
                }
                collectionsStatusListener?.let {
                    database.getReference("families/$familyId/children/$childUid/collections")
                        .removeEventListener(it)
                }
                deviceSettingsListener?.let {
                    database.getReference("families/$familyId/children/$childUid/deviceSettings")
                        .removeEventListener(it)
                }
            }
        }
        syncRequestListener = null
        locksListener = null
        appLockListener = null
        scheduleListener = null
        timeLimitsListener = null
        videosStatusListener = null
        collectionsStatusListener = null
        deviceSettingsListener = null
        currentFamilyId = null
        currentChildUid = null
    }

    fun dismissLockWarning() {
        _lockWarning.value = null
    }

    /**
     * Called when app comes to foreground - marks device as online
     */
    fun onAppForeground() {
        coroutineScope.launch(Dispatchers.IO) {
            setOnlineStatus(true)
        }
    }

    /**
     * Called when app goes to background - marks device as offline
     */
    fun onAppBackground() {
        coroutineScope.launch(Dispatchers.IO) {
            // Update watch time when going to background
            endWatchingSession()
            setOnlineStatus(false)
        }
    }

    private suspend fun setOnlineStatus(isOnline: Boolean) {
        val familyId = currentFamilyId ?: run {
            val pairingState = pairingDao.getPairingState()
            pairingState?.familyId
        } ?: return

        val childUid = currentChildUid ?: run {
            val pairingState = pairingDao.getPairingState()
            pairingState?.childUid
        } ?: return

        try {
            val updates = mutableMapOf<String, Any?>()
            updates["families/$familyId/children/$childUid/deviceInfo/isOnline"] = isOnline
            updates["families/$familyId/children/$childUid/deviceInfo/lastSeen"] = System.currentTimeMillis()

            database.reference.updateChildren(updates).await()
            Log.d(TAG, "Online status set to: $isOnline")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update online status", e)
        }
    }

    /**
     * Start watching session - call when video playback starts
     */
    fun startWatchingSession(videoTitle: String) {
        sessionStartTime = System.currentTimeMillis()
        videosWatchedToday++

        coroutineScope.launch(Dispatchers.IO) {
            updateCurrentlyWatching(videoTitle)
        }
    }

    /**
     * End watching session - call when video playback stops
     */
    fun endWatchingSession() {
        if (sessionStartTime > 0) {
            val sessionDuration = (System.currentTimeMillis() - sessionStartTime) / 60000 // Convert to minutes
            todayWatchTimeMinutes += sessionDuration
            weekWatchTimeMinutes += sessionDuration
            totalWatchTimeMinutes += sessionDuration
            sessionStartTime = 0

            coroutineScope.launch(Dispatchers.IO) {
                updateCurrentlyWatching(null)
                uploadViewingMetrics()
            }
        }
    }

    private suspend fun uploadViewingMetrics() {
        val familyId = currentFamilyId ?: return
        val childUid = currentChildUid ?: return

        val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        try {
            val metrics = mapOf(
                "todayWatchTimeMinutes" to todayWatchTimeMinutes,
                "weekWatchTimeMinutes" to weekWatchTimeMinutes,
                "totalWatchTimeMinutes" to totalWatchTimeMinutes,
                "lastWatchDate" to todayDate,
                "videosWatchedToday" to videosWatchedToday,
                "lastVideoWatched" to currentlyWatchingTitle,
                "lastWatchedAt" to System.currentTimeMillis()
            )

            database.getReference("families/$familyId/children/$childUid/metrics")
                .updateChildren(metrics).await()

            // Also update deviceInfo with today's watch time
            database.getReference("families/$familyId/children/$childUid/deviceInfo/todayWatchTime")
                .setValue(todayWatchTimeMinutes).await()

            Log.d(TAG, "Uploaded viewing metrics: today=$todayWatchTimeMinutes min")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload viewing metrics", e)
        }
    }

    /**
     * Check if app should be blocked based on app lock, schedule, or time limits
     */
    fun shouldBlockApp(): BlockReason? {
        // Check app lock
        val appLockState = _appLock.value
        if (appLockState?.isLocked == true) {
            val now = System.currentTimeMillis()
            if (appLockState.appliesAt <= now) {
                // Check if scheduled unlock time has passed
                appLockState.unlockAt?.let { unlockTime ->
                    if (now >= unlockTime) {
                        // Scheduled unlock time passed, clear the lock
                        coroutineScope.launch(Dispatchers.IO) { clearAppLock() }
                        return null
                    }
                }
                return BlockReason.AppLocked(appLockState.message, appLockState.unlockAt)
            }
        }

        // Check schedule
        val scheduleState = _scheduleSettings.value
        if (scheduleState?.enabled == true && !scheduleState.isCurrentlyAllowed) {
            return BlockReason.ScheduleRestriction(scheduleState.message)
        }

        // Check time limits
        val timeLimitState = _timeLimitSettings.value
        if (timeLimitState?.enabled == true && timeLimitState.isLimitReached) {
            return BlockReason.TimeLimitReached("Daily time limit reached")
        }

        return null
    }

    private suspend fun clearAppLock() {
        val familyId = currentFamilyId ?: return
        val childUid = currentChildUid ?: return

        try {
            // Note: Firebase serializes 'isLocked' as 'locked'
            database.getReference("families/$familyId/children/$childUid/appLock/locked")
                .setValue(false).await()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear app lock", e)
        }
    }

    sealed class BlockReason {
        data class AppLocked(val message: String, val unlockAt: Long?) : BlockReason()
        data class ScheduleRestriction(val message: String) : BlockReason()
        data class TimeLimitReached(val message: String) : BlockReason()
    }
}
