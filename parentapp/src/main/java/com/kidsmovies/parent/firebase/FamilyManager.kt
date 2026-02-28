package com.kidsmovies.parent.firebase

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.kidsmovies.shared.models.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID

class FamilyManager {

    private val database = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()

    companion object {
        private const val TAG = "FamilyManager"
    }

    /**
     * Get or create the family for the current user
     */
    suspend fun getOrCreateFamily(): Family? {
        val userId = auth.currentUser?.uid ?: return null

        // Check if user already has a family
        val familySnapshot = database.getReference(FirebasePaths.FAMILIES)
            .orderByChild("createdBy")
            .equalTo(userId)
            .get()
            .await()

        if (familySnapshot.exists()) {
            for (child in familySnapshot.children) {
                val family = child.getValue(Family::class.java)
                if (family != null) {
                    return family
                }
            }
        }

        // Create new family
        val familyId = UUID.randomUUID().toString()
        val family = Family(
            familyId = familyId,
            createdAt = System.currentTimeMillis(),
            createdBy = userId,
            familyName = "My Family"
        )

        database.getReference(FirebasePaths.familyPath(familyId))
            .setValue(family)
            .await()

        return family
    }

    /**
     * Get all children in the family as a Flow
     */
    fun getChildrenFlow(familyId: String): Flow<List<ChildDevice>> = callbackFlow {
        val childrenRef = database.getReference("${FirebasePaths.familyPath(familyId)}/${FirebasePaths.CHILDREN}")

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val children = mutableListOf<ChildDevice>()
                for (childSnapshot in snapshot.children) {
                    val childUid = childSnapshot.key ?: continue
                    val deviceInfo = childSnapshot.child(FirebasePaths.DEVICE_INFO)
                        .getValue(SyncedChildDevice::class.java)

                    if (deviceInfo != null) {
                        children.add(
                            ChildDevice(
                                childUid = childUid,
                                deviceInfo = deviceInfo,
                                familyId = familyId
                            )
                        )
                    }
                }
                trySend(children)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Children listener cancelled", error.toException())
            }
        }

        childrenRef.addValueEventListener(listener)

        awaitClose {
            childrenRef.removeEventListener(listener)
        }
    }

    /**
     * Get videos for a specific child
     */
    fun getChildVideosFlow(familyId: String, childUid: String): Flow<List<ChildVideo>> = callbackFlow {
        val videosRef = database.getReference(FirebasePaths.childVideosPath(familyId, childUid))

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val videos = mutableListOf<ChildVideo>()
                for (videoSnapshot in snapshot.children) {
                    val video = videoSnapshot.getValue(SyncedVideo::class.java)
                    if (video != null) {
                        videos.add(ChildVideo(video, videoSnapshot.key ?: ""))
                    }
                }
                trySend(videos.sortedBy { it.video.title })
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Videos listener cancelled", error.toException())
            }
        }

        videosRef.addValueEventListener(listener)

        awaitClose {
            videosRef.removeEventListener(listener)
        }
    }

    /**
     * Get collections for a specific child
     */
    fun getChildCollectionsFlow(familyId: String, childUid: String): Flow<List<ChildCollection>> = callbackFlow {
        val collectionsRef = database.getReference(FirebasePaths.childCollectionsPath(familyId, childUid))

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val collections = mutableListOf<ChildCollection>()
                for (collectionSnapshot in snapshot.children) {
                    val collection = collectionSnapshot.getValue(SyncedCollection::class.java)
                    if (collection != null) {
                        collections.add(ChildCollection(collection, collectionSnapshot.key ?: ""))
                    }
                }
                trySend(collections.sortedBy { it.collection.name })
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Collections listener cancelled", error.toException())
            }
        }

        collectionsRef.addValueEventListener(listener)

        awaitClose {
            collectionsRef.removeEventListener(listener)
        }
    }

    /**
     * Request the child device to sync its content
     */
    suspend fun requestSync(familyId: String, childUid: String) {
        val userId = auth.currentUser?.uid ?: return

        val syncRequest = SyncRequest(
            requested = true,
            requestedAt = System.currentTimeMillis(),
            requestedBy = userId
        )

        database.getReference(FirebasePaths.childSyncRequestPath(familyId, childUid))
            .setValue(syncRequest)
            .await()
    }

    /**
     * Lock or unlock a video
     */
    suspend fun setVideoLock(
        familyId: String,
        childUid: String,
        videoTitle: String,
        isLocked: Boolean,
        warningMinutes: Int = 5,
        allowFinishCurrentVideo: Boolean = false
    ) {
        val userId = auth.currentUser?.uid ?: return

        val lockCommand = LockCommand(
            videoTitle = videoTitle,
            collectionName = null,
            isLocked = isLocked,
            lockedBy = userId,
            lockedAt = System.currentTimeMillis(),
            warningMinutes = warningMinutes,
            allowFinishCurrentVideo = allowFinishCurrentVideo
        )

        val lockId = sanitizeFirebaseKey(videoTitle)

        // Write lock command to locks path
        database.getReference("${FirebasePaths.childLocksPath(familyId, childUid)}/$lockId")
            .setValue(lockCommand)
            .await()

        // Also update the video's enabled field directly for immediate effect
        // Note: Firebase serializes 'isEnabled' as 'enabled' (drops the 'is' prefix)
        database.getReference("${FirebasePaths.childVideosPath(familyId, childUid)}/$lockId/enabled")
            .setValue(!isLocked)
            .await()
    }

    /**
     * Lock or unlock a collection, cascading to child seasons and videos.
     * Uses atomic updateChildren() so all lock changes (collection + seasons + videos)
     * are applied simultaneously, preventing UI timing issues where the collection
     * shows locked but child toggles still appear unlocked.
     */
    suspend fun setCollectionLock(
        familyId: String,
        childUid: String,
        collectionName: String,
        isLocked: Boolean,
        warningMinutes: Int = 5,
        allowFinishCurrentVideo: Boolean = false
    ) {
        val userId = auth.currentUser?.uid ?: return
        val now = System.currentTimeMillis()
        val childRef = database.getReference(FirebasePaths.childPath(familyId, childUid))

        // Build all updates into a single atomic map
        val updates = mutableMapOf<String, Any?>()

        // 1. Lock the collection itself
        val collectionKey = sanitizeFirebaseKey(collectionName)
        val collectionLockId = "collection_$collectionKey"
        updates["${FirebasePaths.COLLECTIONS}/$collectionKey/enabled"] = !isLocked
        updates["${FirebasePaths.LOCKS}/$collectionLockId"] = LockCommand(
            collectionName = collectionName,
            isLocked = isLocked,
            lockedBy = userId,
            lockedAt = now,
            warningMinutes = warningMinutes,
            allowFinishCurrentVideo = allowFinishCurrentVideo
        )

        try {
            // 2. Find child seasons and add them to the atomic update
            val collectionsSnapshot = database.getReference(
                FirebasePaths.childCollectionsPath(familyId, childUid)
            ).get().await()

            val childSeasonNames = mutableListOf<String>()
            for (collectionSnapshot in collectionsSnapshot.children) {
                val collection = collectionSnapshot.getValue(SyncedCollection::class.java) ?: continue
                if (collection.parentName == collectionName) {
                    val seasonKey = collectionSnapshot.key ?: continue
                    childSeasonNames.add(collection.name)

                    val seasonLockId = "collection_${sanitizeFirebaseKey(collection.name)}"
                    updates["${FirebasePaths.COLLECTIONS}/$seasonKey/enabled"] = !isLocked
                    updates["${FirebasePaths.LOCKS}/$seasonLockId"] = LockCommand(
                        collectionName = collection.name,
                        isLocked = isLocked,
                        lockedBy = userId,
                        lockedAt = now,
                        warningMinutes = warningMinutes,
                        allowFinishCurrentVideo = allowFinishCurrentVideo
                    )
                }
            }

            // 3. Find videos in this collection or child seasons and add to atomic update
            val allCollectionNames = mutableListOf(collectionName).apply { addAll(childSeasonNames) }

            val videosSnapshot = database.getReference(
                FirebasePaths.childVideosPath(familyId, childUid)
            ).get().await()

            for (videoSnapshot in videosSnapshot.children) {
                val video = videoSnapshot.getValue(SyncedVideo::class.java) ?: continue
                val videoKey = videoSnapshot.key ?: continue

                val belongsToLockedCollection = video.collectionNames.any { it in allCollectionNames }
                if (belongsToLockedCollection) {
                    val videoLockId = sanitizeFirebaseKey(video.title)
                    updates["${FirebasePaths.VIDEOS}/$videoKey/enabled"] = !isLocked
                    updates["${FirebasePaths.LOCKS}/$videoLockId"] = LockCommand(
                        videoTitle = video.title,
                        isLocked = isLocked,
                        lockedBy = userId,
                        lockedAt = now,
                        warningMinutes = warningMinutes,
                        allowFinishCurrentVideo = allowFinishCurrentVideo
                    )
                }
            }

            // 4. Apply ALL updates atomically - listeners fire once with all changes
            childRef.updateChildren(updates).await()

        } catch (e: Exception) {
            Log.e(TAG, "Error setting collection lock with cascade", e)
        }
    }

    /**
     * Hide or unhide a video from the child
     */
    suspend fun setVideoHidden(
        familyId: String,
        childUid: String,
        videoTitle: String,
        isHidden: Boolean
    ) {
        val key = sanitizeFirebaseKey(videoTitle)
        // Note: Firebase serializes 'isHidden' as 'hidden' (drops the 'is' prefix)
        database.getReference("${FirebasePaths.childVideosPath(familyId, childUid)}/$key/hidden")
            .setValue(isHidden)
            .await()
    }

    /**
     * Hide or unhide a collection from the child
     */
    suspend fun setCollectionHidden(
        familyId: String,
        childUid: String,
        collectionName: String,
        isHidden: Boolean
    ) {
        val key = sanitizeFirebaseKey(collectionName)
        // Note: Firebase serializes 'isHidden' as 'hidden' (drops the 'is' prefix)
        database.getReference("${FirebasePaths.childCollectionsPath(familyId, childUid)}/$key/hidden")
            .setValue(isHidden)
            .await()
    }

    /**
     * Remove a child from the family
     */
    suspend fun removeChild(familyId: String, childUid: String) {
        database.getReference(FirebasePaths.childPath(familyId, childUid))
            .removeValue()
            .await()
    }

    /**
     * Lock or unlock the entire app
     */
    suspend fun setAppLock(
        familyId: String,
        childUid: String,
        isLocked: Boolean,
        unlockAt: Long? = null,
        message: String = "App is locked by parent",
        warningMinutes: Int = 5,
        allowFinishCurrentVideo: Boolean = false
    ) {
        val userId = auth.currentUser?.uid ?: return

        val appLock = AppLockCommand(
            isLocked = isLocked,
            lockedBy = userId,
            lockedAt = System.currentTimeMillis(),
            unlockAt = unlockAt,
            message = message,
            warningMinutes = warningMinutes,
            allowFinishCurrentVideo = allowFinishCurrentVideo
        )

        database.getReference(FirebasePaths.childAppLockPath(familyId, childUid))
            .setValue(appLock)
            .await()
    }

    /**
     * Get app lock status as a Flow
     */
    fun getAppLockFlow(familyId: String, childUid: String): Flow<AppLockCommand?> = callbackFlow {
        val appLockRef = database.getReference(FirebasePaths.childAppLockPath(familyId, childUid))

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val appLock = snapshot.getValue(AppLockCommand::class.java)
                trySend(appLock)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "App lock listener cancelled", error.toException())
            }
        }

        appLockRef.addValueEventListener(listener)

        awaitClose {
            appLockRef.removeEventListener(listener)
        }
    }

    /**
     * Set schedule settings for a child
     */
    suspend fun setScheduleSettings(
        familyId: String,
        childUid: String,
        settings: ScheduleSettings
    ) {
        database.getReference(FirebasePaths.childSchedulePath(familyId, childUid))
            .setValue(settings)
            .await()
    }

    /**
     * Get schedule settings as a Flow
     */
    fun getScheduleSettingsFlow(familyId: String, childUid: String): Flow<ScheduleSettings?> = callbackFlow {
        val scheduleRef = database.getReference(FirebasePaths.childSchedulePath(familyId, childUid))

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val settings = snapshot.getValue(ScheduleSettings::class.java)
                trySend(settings)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Schedule listener cancelled", error.toException())
            }
        }

        scheduleRef.addValueEventListener(listener)

        awaitClose {
            scheduleRef.removeEventListener(listener)
        }
    }

    /**
     * Set time limit settings for a child
     */
    suspend fun setTimeLimitSettings(
        familyId: String,
        childUid: String,
        settings: TimeLimitSettings
    ) {
        database.getReference(FirebasePaths.childTimeLimitsPath(familyId, childUid))
            .setValue(settings)
            .await()
    }

    /**
     * Get time limit settings as a Flow
     */
    fun getTimeLimitSettingsFlow(familyId: String, childUid: String): Flow<TimeLimitSettings?> = callbackFlow {
        val timeLimitsRef = database.getReference(FirebasePaths.childTimeLimitsPath(familyId, childUid))

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val settings = snapshot.getValue(TimeLimitSettings::class.java)
                trySend(settings)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Time limits listener cancelled", error.toException())
            }
        }

        timeLimitsRef.addValueEventListener(listener)

        awaitClose {
            timeLimitsRef.removeEventListener(listener)
        }
    }

    /**
     * Get viewing metrics for a child as a Flow
     */
    fun getViewingMetricsFlow(familyId: String, childUid: String): Flow<ViewingMetrics?> = callbackFlow {
        val metricsRef = database.getReference(FirebasePaths.childMetricsPath(familyId, childUid))

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val metrics = snapshot.getValue(ViewingMetrics::class.java)
                trySend(metrics)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Metrics listener cancelled", error.toException())
            }
        }

        metricsRef.addValueEventListener(listener)

        awaitClose {
            metricsRef.removeEventListener(listener)
        }
    }

    private fun sanitizeFirebaseKey(key: String): String {
        return key.replace(Regex("[.\\$#\\[\\]/]"), "_")
    }
}

/**
 * Wrapper for child device with additional info
 */
data class ChildDevice(
    val childUid: String,
    val deviceInfo: SyncedChildDevice,
    val familyId: String
) {
    val isOnline: Boolean
        get() = deviceInfo.isOnline ||
                (System.currentTimeMillis() - deviceInfo.lastSeen) < 5 * 60 * 1000 // 5 minutes

    val displayName: String
        get() = deviceInfo.childName.ifBlank { deviceInfo.deviceName.ifBlank { "Unknown Device" } }
}

/**
 * Wrapper for child video
 */
data class ChildVideo(
    val video: SyncedVideo,
    val firebaseKey: String
)

/**
 * Wrapper for child collection
 */
data class ChildCollection(
    val collection: SyncedCollection,
    val firebaseKey: String
)
