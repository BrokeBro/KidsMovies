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
 * Lock command from parent app for individual videos/collections
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
 * App-level lock command - locks the entire kids app
 */
@IgnoreExtraProperties
data class AppLockCommand(
    val isLocked: Boolean = false,
    val lockedBy: String = "",
    val lockedAt: Long = 0,
    val unlockAt: Long? = null, // Scheduled unlock time (null = manual unlock only)
    val message: String = "App is locked by parent", // Message shown to child
    val warningMinutes: Int = 5, // Warning before lock takes effect
    val allowFinishCurrentVideo: Boolean = false
) {
    // No-arg constructor for Firebase
    constructor() : this(false, "", 0, null, "App is locked by parent", 5, false)
}

/**
 * Schedule settings for when the app can be used
 */
@IgnoreExtraProperties
data class ScheduleSettings(
    val enabled: Boolean = false,
    val allowedDays: List<Int> = listOf(0, 1, 2, 3, 4, 5, 6), // 0=Sunday, 6=Saturday
    val allowedStartHour: Int = 8, // 8 AM
    val allowedStartMinute: Int = 0,
    val allowedEndHour: Int = 20, // 8 PM
    val allowedEndMinute: Int = 0,
    val timezone: String = "UTC"
) {
    // No-arg constructor for Firebase
    constructor() : this(false, listOf(0, 1, 2, 3, 4, 5, 6), 8, 0, 20, 0, "UTC")
}

/**
 * Daily time limit settings
 */
@IgnoreExtraProperties
data class TimeLimitSettings(
    val enabled: Boolean = false,
    val dailyLimitMinutes: Int = 120, // 2 hours default
    val weekendLimitMinutes: Int = 180, // 3 hours on weekends
    val warningAtMinutesRemaining: Int = 10 // Warn when 10 minutes left
) {
    // No-arg constructor for Firebase
    constructor() : this(false, 120, 180, 10)
}

/**
 * Viewing metrics for a child device
 */
@IgnoreExtraProperties
data class ViewingMetrics(
    val todayWatchTimeMinutes: Long = 0,
    val weekWatchTimeMinutes: Long = 0,
    val totalWatchTimeMinutes: Long = 0,
    val lastWatchDate: String = "", // YYYY-MM-DD format
    val videosWatchedToday: Int = 0,
    val mostWatchedVideo: String? = null,
    val lastVideoWatched: String? = null,
    val lastWatchedAt: Long = 0
) {
    // No-arg constructor for Firebase
    constructor() : this(0, 0, 0, "", 0, null, null, 0)
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
    const val APP_LOCK = "appLock"
    const val SCHEDULE = "schedule"
    const val TIME_LIMITS = "timeLimits"
    const val METRICS = "metrics"
    const val SETTINGS = "settings"

    fun familyPath(familyId: String) = "$FAMILIES/$familyId"
    fun childPath(familyId: String, childUid: String) = "$FAMILIES/$familyId/$CHILDREN/$childUid"
    fun childVideosPath(familyId: String, childUid: String) = "${childPath(familyId, childUid)}/$VIDEOS"
    fun childCollectionsPath(familyId: String, childUid: String) = "${childPath(familyId, childUid)}/$COLLECTIONS"
    fun childDeviceInfoPath(familyId: String, childUid: String) = "${childPath(familyId, childUid)}/$DEVICE_INFO"
    fun childLocksPath(familyId: String, childUid: String) = "${childPath(familyId, childUid)}/$LOCKS"
    fun childSyncRequestPath(familyId: String, childUid: String) = "${childPath(familyId, childUid)}/$SYNC_REQUEST"
    fun childAppLockPath(familyId: String, childUid: String) = "${childPath(familyId, childUid)}/$APP_LOCK"
    fun childSchedulePath(familyId: String, childUid: String) = "${childPath(familyId, childUid)}/$SETTINGS/$SCHEDULE"
    fun childTimeLimitsPath(familyId: String, childUid: String) = "${childPath(familyId, childUid)}/$SETTINGS/$TIME_LIMITS"
    fun childMetricsPath(familyId: String, childUid: String) = "${childPath(familyId, childUid)}/$METRICS"
    fun pairingCodePath(code: String) = "$PAIRING_CODES/$code"
}
