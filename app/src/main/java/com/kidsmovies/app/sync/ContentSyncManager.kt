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

class ContentSyncManager(
    private val pairingDao: PairingDao,
    private val videoRepository: VideoRepository,
    private val collectionRepository: CollectionRepository,
    private val coroutineScope: CoroutineScope
) {
    private val database = FirebaseDatabase.getInstance()

    private var syncRequestListener: ValueEventListener? = null
    private var locksListener: ValueEventListener? = null
    private var currentFamilyId: String? = null
    private var currentChildUid: String? = null

    private val _pendingLocks = MutableStateFlow<List<PendingLock>>(emptyList())
    val pendingLocks: StateFlow<List<PendingLock>> = _pendingLocks

    private val _lockWarning = MutableStateFlow<LockWarning?>(null)
    val lockWarning: StateFlow<LockWarning?> = _lockWarning

    companion object {
        private const val TAG = "ContentSyncManager"
    }

    data class LockWarning(
        val title: String,
        val isVideo: Boolean, // true = video, false = collection
        val minutesRemaining: Int,
        val appliesAt: Long
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
        }
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
            val isLocked = lockSnapshot.child("isLocked").getValue(Boolean::class.java) ?: false
            val warningMinutes = lockSnapshot.child("warningMinutes").getValue(Int::class.java) ?: 5
            val lockedAt = lockSnapshot.child("lockedAt").getValue(Long::class.java) ?: now

            if (isLocked) {
                val appliesAt = lockedAt + (warningMinutes * 60 * 1000)

                if (appliesAt <= now) {
                    // Warning time has passed, apply the lock immediately
                    applyLock(videoTitle, collectionName, true)
                    // Remove the processed lock command
                    removeLockCommand(lockId)
                } else {
                    // Add to pending locks with warning
                    newPendingLocks.add(
                        PendingLock(
                            videoTitle = videoTitle,
                            collectionName = collectionName,
                            appliesAt = appliesAt,
                            warningMinutes = warningMinutes
                        )
                    )

                    // Update warning state
                    val minutesRemaining = ((appliesAt - now) / 60000).toInt()
                    val title = videoTitle ?: collectionName ?: "Content"
                    _lockWarning.value = LockWarning(
                        title = title,
                        isVideo = videoTitle != null,
                        minutesRemaining = minutesRemaining,
                        appliesAt = appliesAt
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
     * Apply lock/unlock to video or collection
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

        for (lock in locks) {
            if (lock.appliesAt <= now) {
                applyLock(lock.videoTitle, lock.collectionName, true)
                toRemove.add(lock)
            } else {
                // Update warning time
                val minutesRemaining = ((lock.appliesAt - now) / 60000).toInt()
                val title = lock.videoTitle ?: lock.collectionName ?: "Content"
                _lockWarning.value = LockWarning(
                    title = title,
                    isVideo = lock.videoTitle != null,
                    minutesRemaining = minutesRemaining,
                    appliesAt = lock.appliesAt
                )
            }
        }

        locks.removeAll(toRemove)
        _pendingLocks.value = locks

        if (locks.isEmpty()) {
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
        val familyId = currentFamilyId ?: return
        val childUid = currentChildUid ?: return

        database.getReference("families/$familyId/children/$childUid/deviceInfo/currentlyWatching")
            .setValue(videoTitle).await()

        database.getReference("families/$familyId/children/$childUid/deviceInfo/lastSeen")
            .setValue(System.currentTimeMillis()).await()
    }

    /**
     * Update video's enabled status in Firebase after local change
     */
    suspend fun syncVideoEnabledStatus(videoTitle: String, isEnabled: Boolean) {
        val familyId = currentFamilyId ?: return
        val childUid = currentChildUid ?: return

        val key = sanitizeFirebaseKey(videoTitle)
        database.getReference("families/$familyId/children/$childUid/videos/$key/isEnabled")
            .setValue(isEnabled).await()
    }

    /**
     * Update collection's enabled status in Firebase after local change
     */
    suspend fun syncCollectionEnabledStatus(collectionName: String, isEnabled: Boolean) {
        val familyId = currentFamilyId ?: return
        val childUid = currentChildUid ?: return

        val key = sanitizeFirebaseKey(collectionName)
        database.getReference("families/$familyId/children/$childUid/collections/$key/isEnabled")
            .setValue(isEnabled).await()
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
            }
        }
        syncRequestListener = null
        locksListener = null
        currentFamilyId = null
        currentChildUid = null
    }

    fun dismissLockWarning() {
        _lockWarning.value = null
    }
}
