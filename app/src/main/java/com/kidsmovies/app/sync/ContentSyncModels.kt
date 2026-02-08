package com.kidsmovies.app.sync

// Re-export shared models for convenience
// The actual model classes are defined in the shared module
typealias SyncedVideo = com.kidsmovies.shared.models.SyncedVideo
typealias SyncedCollection = com.kidsmovies.shared.models.SyncedCollection
typealias SyncedChildDevice = com.kidsmovies.shared.models.SyncedChildDevice
typealias SyncRequest = com.kidsmovies.shared.models.SyncRequest
typealias LockCommand = com.kidsmovies.shared.models.LockCommand

/**
 * Pending lock that's been received but not yet applied (for warning period)
 * This is specific to the kids app for local lock management.
 */
data class PendingLock(
    val videoTitle: String? = null,
    val collectionName: String? = null,
    val appliesAt: Long, // Timestamp when lock should be applied
    val warningMinutes: Int,
    val allowFinishCurrentVideo: Boolean = false // Allow child to finish current video before lock
)
