package com.kidsmovies.app.sync

import com.google.firebase.database.IgnoreExtraProperties

/**
 * Video info synced to Firebase for parent visibility
 */
@IgnoreExtraProperties
data class SyncedVideo(
    val title: String = "",
    val collectionNames: List<String> = emptyList(),
    val isFavourite: Boolean = false,
    val isEnabled: Boolean = true,
    val duration: Long = 0,
    val playbackPosition: Long = 0,
    val lastWatched: Long? = null,
    val thumbnailUrl: String? = null
) {
    // No-arg constructor for Firebase
    constructor() : this("", emptyList(), false, true, 0, 0, null, null)
}

/**
 * Collection info synced to Firebase for parent visibility
 */
@IgnoreExtraProperties
data class SyncedCollection(
    val name: String = "",
    val type: String = "REGULAR", // REGULAR, TV_SHOW, SEASON
    val parentName: String? = null, // For seasons - parent TV show name
    val videoCount: Int = 0,
    val isEnabled: Boolean = true,
    val thumbnailUrl: String? = null
) {
    // No-arg constructor for Firebase
    constructor() : this("", "REGULAR", null, 0, true, null)
}

/**
 * Child device info synced to Firebase
 */
@IgnoreExtraProperties
data class SyncedChildDevice(
    val deviceName: String = "",
    val childUid: String = "",
    val lastSeen: Long = 0,
    val appVersion: String = "",
    val isOnline: Boolean = false,
    val currentlyWatching: String? = null, // Video title if currently watching
    val todayWatchTime: Long = 0 // Minutes watched today
) {
    // No-arg constructor for Firebase
    constructor() : this("", "", 0, "", false, null, 0)
}

/**
 * Sync request from parent app
 */
@IgnoreExtraProperties
data class SyncRequest(
    val requested: Boolean = false,
    val requestedAt: Long = 0,
    val requestedBy: String = "" // Parent UID
) {
    // No-arg constructor for Firebase
    constructor() : this(false, 0, "")
}

/**
 * Lock command from parent app
 */
@IgnoreExtraProperties
data class LockCommand(
    val videoTitle: String? = null,
    val collectionName: String? = null,
    val isLocked: Boolean = false,
    val lockedBy: String = "", // Parent UID
    val lockedAt: Long = 0,
    val warningMinutes: Int = 5, // Warning time before lock takes effect (0 = immediate)
    val allowFinishCurrentVideo: Boolean = false // Allow child to finish current video before lock
) {
    // No-arg constructor for Firebase
    constructor() : this(null, null, false, "", 0, 5, false)
}

/**
 * Pending lock that's been received but not yet applied (for warning period)
 */
data class PendingLock(
    val videoTitle: String? = null,
    val collectionName: String? = null,
    val appliesAt: Long, // Timestamp when lock should be applied
    val warningMinutes: Int,
    val allowFinishCurrentVideo: Boolean = false // Allow child to finish current video before lock
)
