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
                    for (videoSnapshot in snapshot.children) {
                        val videoKey = videoSnapshot.key ?: continue
                        val title = videoSnapshot.child("title").getValue(String::class.java) ?: continue
                        // Note: Firebase serializes 'isEnabled' as 'enabled' (drops the 'is' prefix)
                        val enabled = videoSnapshot.child("enabled").getValue(Boolean::class.java) ?: true
                        val hidden = videoSnapshot.child("hidden").getValue(Boolean::class.java) ?: false

                        // Find video by title and update status
                        val video = videoRepository.getVideoByTitle(title)
                        if (video != null) {
                            if (video.isEnabled != enabled) {
                                videoRepository.updateEnabled(video.id, enabled)
                                Log.d(TAG, "Synced video enabled status from Firebase: $title, enabled=$enabled")
                            }
                            if (video.isHidden != hidden) {
                                videoRepository.updateHidden(video.id, hidden)
                                Log.d(TAG, "Synced video hidden status from Firebase: $title, hidden=$hidden")
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
                    for (collectionSnapshot in snapshot.children) {
                        val collectionKey = collectionSnapshot.key ?: continue
                        val name = collectionSnapshot.child("name").getValue(String::class.java) ?: continue
                        // Note: Firebase serializes 'isEnabled' as 'enabled' (drops the 'is' prefix)
                        val enabled = collectionSnapshot.child("enabled").getValue(Boolean::class.java) ?: true
                        val hidden = collectionSnapshot.child("hidden").getValue(Boolean::class.java) ?: false

                        // Find collection by name and update status
                        val collection = collectionRepository.getCollectionByName(name)
                        if (collection != null) {
                            if (collection.isEnabled != enabled) {
                                collectionRepository.updateEnabled(collection.id, enabled)
                                Log.d(TAG, "Synced collection enabled status from Firebase: $name, enabled=$enabled")
                            }
                            if (collection.isHidden != hidden) {
                                collectionRepository.updateHidden(collection.id, hidden)
                                Log.d(TAG, "Synced collection hidden status from Firebase: $name, hidden=$hidden")
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
            val collectionName = lockSnapshot.child("collectionName").getValue(String::class.java)
            // Note: Firebase serializes 'isLocked' as 'locked' (drops the 'is' prefix)
            val isLocked = lockSnapshot.child("locked").getValue(Boolean::class.java) ?: false
            val warningMinutes = lockSnapshot.child("warningMinutes").getValue(Int::class.java) ?: 5
            val lockedAt = lockSnapshot.child("lockedAt").getValue(Long::class.java) ?: now
            val allowFinishCurrentVideo = lockSnapshot.child("allowFinishCurrentVideo").getValue(Boolean::class.java) ?: false

            if (isLocked) {
                val appliesAt = lockedAt + (warningMinutes * 60 * 1000)

                if (appliesAt <= now) {
                    // Warning time has passed
                    if (allowFinishCurrentVideo && isWatchingVideo) {
                        // Child is watching a video and allowed to finish - add to waiting list
                        val waitingLock = PendingLock(
                            videoTitle = videoTitle,
                            collectionName = collectionName,
                            appliesAt = appliesAt,
                            warningMinutes = warningMinutes,
                            allowFinishCurrentVideo = true
                        )
                        val currentWaiting = _locksWaitingForVideoEnd.value.toMutableList()
                        if (!currentWaiting.any { it.videoTitle == videoTitle && it.collectionName == collectionName }) {
                            currentWaiting.add(waitingLock)
                            _locksWaitingForVideoEnd.value = currentWaiting
                        }

                        // Show "last one" warning
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
                        // Apply the lock immediately
                        applyLock(videoTitle, collectionName, true)
                        // Remove the processed lock command
                        removeLockCommand(lockId)
                    }
                } else {
                    // Add to pending locks with warning
                    newPendingLocks.add(
                        PendingLock(
                            videoTitle = videoTitle,
                            collectionName = collectionName,
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
                applyLock(videoTitle, collectionName, false)
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
     * When locking a collection, also locks all videos within it.
     */
    private suspend fun applyLock(videoTitle: String?, collectionName: String?, isLocked: Boolean) {
        val enabled = !isLocked

        if (videoTitle != null) {
            // Find video by title and update isEnabled
            val video = videoRepository.getVideoByTitle(videoTitle)
            if (video != null) {
                videoRepository.updateEnabled(video.id, enabled)
                Log.d(TAG, "Applied lock to video: $videoTitle, enabled=$enabled")
            } else {
                Log.w(TAG, "Video not found for lock: $videoTitle")
            }
        }

        if (collectionName != null) {
            // Find collection by name and update isEnabled
            val collection = collectionRepository.getCollectionByName(collectionName)
            if (collection != null) {
                collectionRepository.updateEnabled(collection.id, enabled)
                Log.d(TAG, "Applied lock to collection: $collectionName, enabled=$enabled")

                // Also lock/unlock all videos in this collection
                val videosInCollection = collectionRepository.getVideosInCollection(collection.id)
                for (video in videosInCollection) {
                    videoRepository.updateEnabled(video.id, enabled)
                    Log.d(TAG, "Applied collection lock to video: ${video.title}, enabled=$enabled")
                }

                // If this is a TV show, also lock all seasons and their episodes
                if (collection.isTvShow()) {
                    val seasons = collectionRepository.getSubCollections(collection.id)
                    for (season in seasons) {
                        collectionRepository.updateEnabled(season.id, enabled)
                        Log.d(TAG, "Applied lock to season: ${season.name}, enabled=$enabled")

                        // Lock episodes in this season
                        val episodes = collectionRepository.getVideosInCollection(season.id)
                        for (episode in episodes) {
                            videoRepository.updateEnabled(episode.id, enabled)
                            Log.d(TAG, "Applied season lock to episode: ${episode.title}, enabled=$enabled")
                        }
                    }
                }
            } else {
                Log.w(TAG, "Collection not found for lock: $collectionName")
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
                    // Child is watching - add to waiting list instead of applying
                    toWaitForVideoEnd.add(lock)
                    toRemove.add(lock)

                    // Show "last one" warning
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
                    applyLock(lock.videoTitle, lock.collectionName, true)
                    toRemove.add(lock)
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

    private suspend fun uploadVideoList(familyId: String, childUid: String) {
        val videos = videoRepository.getAllVideos()
        val collections = collectionRepository.getAllCollections()

        val syncedVideos = mutableMapOf<String, SyncedVideo>()

        for (video in videos) {
            // Get collections this video belongs to
            val videoCollections = collections.filter { collection ->
                collectionRepository.isVideoInCollection(video.id, collection.id)
            }

            val syncedVideo = SyncedVideo(
                title = video.title,
                collectionNames = videoCollections.map { it.name },
                isFavourite = video.isFavourite,
                isEnabled = video.isEnabled,
                isHidden = video.isHidden,
                duration = video.duration,
                playbackPosition = video.playbackPosition,
                lastWatched = if (video.playbackPosition > 0) video.dateModified else null,
                thumbnailUrl = null // Thumbnails are local, not synced
            )

            // Use sanitized title as key (Firebase doesn't allow certain characters)
            val key = sanitizeFirebaseKey(video.title)
            syncedVideos[key] = syncedVideo
        }

        database.getReference("families/$familyId/children/$childUid/videos")
            .setValue(syncedVideos).await()
    }

    private suspend fun uploadCollectionList(familyId: String, childUid: String) {
        val collections = collectionRepository.getAllCollections()

        val syncedCollections = mutableMapOf<String, SyncedCollection>()

        for (collection in collections) {
            val videoCount = collectionRepository.getVideoCountInCollection(collection.id)
            val parentCollection = collection.parentCollectionId?.let {
                collectionRepository.getCollectionById(it)
            }

            val syncedCollection = SyncedCollection(
                name = collection.name,
                type = collection.collectionType,
                parentName = parentCollection?.name,
                videoCount = videoCount,
                isEnabled = collection.isEnabled,
                isHidden = collection.isHidden,
                thumbnailUrl = null
            )

            val key = sanitizeFirebaseKey(collection.name)
            syncedCollections[key] = syncedCollection
        }

        database.getReference("families/$familyId/children/$childUid/collections")
            .setValue(syncedCollections).await()
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
            applyLock(lock.videoTitle, lock.collectionName, true)
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
    suspend fun syncVideoEnabledStatus(videoTitle: String, isEnabled: Boolean) {
        val familyId = currentFamilyId ?: return
        val childUid = currentChildUid ?: return

        val key = sanitizeFirebaseKey(videoTitle)
        // Note: Firebase serializes 'isEnabled' as 'enabled' (drops the 'is' prefix)
        database.getReference("families/$familyId/children/$childUid/videos/$key/enabled")
            .setValue(isEnabled).await()
    }

    /**
     * Update collection's enabled status in Firebase after local change
     */
    suspend fun syncCollectionEnabledStatus(collectionName: String, isEnabled: Boolean) {
        val familyId = currentFamilyId ?: return
        val childUid = currentChildUid ?: return

        val key = sanitizeFirebaseKey(collectionName)
        // Note: Firebase serializes 'isEnabled' as 'enabled' (drops the 'is' prefix)
        database.getReference("families/$familyId/children/$childUid/collections/$key/enabled")
            .setValue(isEnabled).await()
    }

    /**
     * Update video's hidden status in Firebase after local change
     */
    suspend fun syncVideoHiddenStatus(videoTitle: String, isHidden: Boolean) {
        val familyId = currentFamilyId ?: return
        val childUid = currentChildUid ?: return

        val key = sanitizeFirebaseKey(videoTitle)
        // Note: Firebase serializes 'isHidden' as 'hidden' (drops the 'is' prefix)
        database.getReference("families/$familyId/children/$childUid/videos/$key/hidden")
            .setValue(isHidden).await()
    }

    /**
     * Update collection's hidden status in Firebase after local change
     */
    suspend fun syncCollectionHiddenStatus(collectionName: String, isHidden: Boolean) {
        val familyId = currentFamilyId ?: return
        val childUid = currentChildUid ?: return

        val key = sanitizeFirebaseKey(collectionName)
        // Note: Firebase serializes 'isHidden' as 'hidden' (drops the 'is' prefix)
        database.getReference("families/$familyId/children/$childUid/collections/$key/hidden")
            .setValue(isHidden).await()
    }

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
            }
        }
        syncRequestListener = null
        locksListener = null
        appLockListener = null
        scheduleListener = null
        timeLimitsListener = null
        videosStatusListener = null
        collectionsStatusListener = null
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
