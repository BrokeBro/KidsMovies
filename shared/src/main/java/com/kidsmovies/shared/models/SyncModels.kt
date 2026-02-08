package com.kidsmovies.shared.models

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
    val childName: String = "", // Display name for the child
    val lastSeen: Long = 0,
    val appVersion: String = "",
    val isOnline: Boolean = false,
    val currentlyWatching: String? = null, // Video title if currently watching
    val todayWatchTime: Long = 0 // Minutes watched today
) {
    // No-arg constructor for Firebase
    constructor() : this("", "", "", 0, "", false, null, 0)
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
 * Family info stored in Firebase
 */
@IgnoreExtraProperties
data class Family(
    val familyId: String = "",
    val createdAt: Long = 0,
    val createdBy: String = "", // Parent UID who created the family
    val familyName: String = "My Family"
) {
    // No-arg constructor for Firebase
    constructor() : this("", 0, "", "My Family")
}

/**
 * Pairing code for connecting child devices
 */
@IgnoreExtraProperties
data class PairingCode(
    val code: String = "",
    val familyId: String = "",
    val createdAt: Long = 0,
    val expiresAt: Long = 0,
    val createdBy: String = "", // Parent UID
    val used: Boolean = false,
    val usedBy: String? = null // Child device UID that used this code
) {
    // No-arg constructor for Firebase
    constructor() : this("", "", 0, 0, "", false, null)

    fun isExpired(): Boolean = System.currentTimeMillis() > expiresAt
    fun isValid(): Boolean = !used && !isExpired()
}

/**
 * Firebase database paths
 */
object FirebasePaths {
    const val FAMILIES = "families"
    const val PAIRING_CODES = "pairingCodes"
    const val CHILDREN = "children"
    const val VIDEOS = "videos"
    const val COLLECTIONS = "collections"
    const val DEVICE_INFO = "deviceInfo"
    const val SYNC_REQUEST = "syncRequest"
    const val LOCKS = "locks"

    fun familyPath(familyId: String) = "$FAMILIES/$familyId"
    fun childPath(familyId: String, childUid: String) = "$FAMILIES/$familyId/$CHILDREN/$childUid"
    fun childVideosPath(familyId: String, childUid: String) = "${childPath(familyId, childUid)}/$VIDEOS"
    fun childCollectionsPath(familyId: String, childUid: String) = "${childPath(familyId, childUid)}/$COLLECTIONS"
    fun childDeviceInfoPath(familyId: String, childUid: String) = "${childPath(familyId, childUid)}/$DEVICE_INFO"
    fun childLocksPath(familyId: String, childUid: String) = "${childPath(familyId, childUid)}/$LOCKS"
    fun childSyncRequestPath(familyId: String, childUid: String) = "${childPath(familyId, childUid)}/$SYNC_REQUEST"
    fun pairingCodePath(code: String) = "$PAIRING_CODES/$code"
}
