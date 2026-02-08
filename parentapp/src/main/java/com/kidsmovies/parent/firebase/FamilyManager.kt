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
        database.getReference("${FirebasePaths.childLocksPath(familyId, childUid)}/$lockId")
            .setValue(lockCommand)
            .await()
    }

    /**
     * Lock or unlock a collection
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

        val lockCommand = LockCommand(
            videoTitle = null,
            collectionName = collectionName,
            isLocked = isLocked,
            lockedBy = userId,
            lockedAt = System.currentTimeMillis(),
            warningMinutes = warningMinutes,
            allowFinishCurrentVideo = allowFinishCurrentVideo
        )

        val lockId = "collection_${sanitizeFirebaseKey(collectionName)}"
        database.getReference("${FirebasePaths.childLocksPath(familyId, childUid)}/$lockId")
            .setValue(lockCommand)
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
