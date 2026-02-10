package com.kidsmovies.app.sync

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Global settings cached from Firebase
 */
@Entity(tableName = "cached_global_settings")
data class CachedGlobalSettings(
    @PrimaryKey val id: Int = 1,  // Singleton
    val updatedAt: Long = 0,
    val appEnabled: Boolean = true,
    val softOffEnabled: Boolean = true,
    val lastSyncedAt: Long = 0
)

/**
 * Device-specific overrides cached from Firebase
 */
@Entity(tableName = "cached_device_overrides")
data class CachedDeviceOverrides(
    @PrimaryKey val id: Int = 1,  // Singleton for this device
    val appEnabled: Boolean = true,
    val maxViewingMinutesOverride: Int? = null,
    val allowedCollectionsJson: String? = null,  // JSON array of collection IDs
    val isRevoked: Boolean = false,
    val lastSyncedAt: Long = 0
)

/**
 * Schedule cached from Firebase
 */
@Entity(tableName = "cached_schedules")
@TypeConverters(ScheduleConverters::class)
data class CachedSchedule(
    @PrimaryKey val scheduleId: String,
    val label: String = "",
    val daysOfWeek: List<Int> = emptyList(),  // 1=Mon, 7=Sun
    val startTime: String = "00:00",           // 24h format HH:mm
    val endTime: String = "23:59",             // 24h format HH:mm
    val maxViewingMinutes: Int? = null,        // null = unlimited within window
    val allowedCollectionsJson: String? = null, // JSON array, null = all
    val blockedVideosJson: String? = null,      // JSON array
    val allowedVideosJson: String? = null,      // JSON array, null = all in allowed collections
    val appliesToDevicesJson: String? = null,   // JSON array, null = all devices
    val isActive: Boolean = true,
    val lastSyncedAt: Long = 0
) {
    fun getAllowedCollections(): List<String>? {
        return allowedCollectionsJson?.let {
            Gson().fromJson(it, object : TypeToken<List<String>>() {}.type)
        }
    }

    fun getBlockedVideos(): List<String>? {
        return blockedVideosJson?.let {
            Gson().fromJson(it, object : TypeToken<List<String>>() {}.type)
        }
    }

    fun getAllowedVideos(): List<String>? {
        return allowedVideosJson?.let {
            Gson().fromJson(it, object : TypeToken<List<String>>() {}.type)
        }
    }

    fun getAppliesToDevices(): List<String>? {
        return appliesToDevicesJson?.let {
            Gson().fromJson(it, object : TypeToken<List<String>>() {}.type)
        }
    }

    fun appliesToDevice(deviceId: String): Boolean {
        val devices = getAppliesToDevices()
        return devices == null || devices.contains(deviceId)
    }
}

/**
 * Type converters for Room
 */
class ScheduleConverters {
    private val gson = Gson()

    @TypeConverter
    fun fromIntList(list: List<Int>?): String? {
        return list?.let { gson.toJson(it) }
    }

    @TypeConverter
    fun toIntList(json: String?): List<Int>? {
        return json?.let {
            gson.fromJson(it, object : TypeToken<List<Int>>() {}.type)
        }
    }
}

/**
 * Aggregated settings state used by the enforcement layer
 */
data class EnforcementSettings(
    val global: CachedGlobalSettings,
    val deviceOverrides: CachedDeviceOverrides?,
    val schedules: List<CachedSchedule>,
    val lastSyncedAt: Long
) {
    val isAppEnabled: Boolean
        get() = global.appEnabled && (deviceOverrides?.appEnabled ?: true)

    val isDeviceRevoked: Boolean
        get() = deviceOverrides?.isRevoked ?: false

    val isSoftOffEnabled: Boolean
        get() = global.softOffEnabled
}
