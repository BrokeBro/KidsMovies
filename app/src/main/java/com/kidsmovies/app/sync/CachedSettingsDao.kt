package com.kidsmovies.app.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CachedSettingsDao {
    // Global settings
    @Query("SELECT * FROM cached_global_settings WHERE id = 1")
    suspend fun getGlobalSettings(): CachedGlobalSettings?

    @Query("SELECT * FROM cached_global_settings WHERE id = 1")
    fun getGlobalSettingsFlow(): Flow<CachedGlobalSettings?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveGlobalSettings(settings: CachedGlobalSettings)

    // Device overrides
    @Query("SELECT * FROM cached_device_overrides WHERE id = 1")
    suspend fun getDeviceOverrides(): CachedDeviceOverrides?

    @Query("SELECT * FROM cached_device_overrides WHERE id = 1")
    fun getDeviceOverridesFlow(): Flow<CachedDeviceOverrides?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDeviceOverrides(overrides: CachedDeviceOverrides)

    // Schedules
    @Query("SELECT * FROM cached_schedules WHERE isActive = 1")
    suspend fun getActiveSchedules(): List<CachedSchedule>

    @Query("SELECT * FROM cached_schedules WHERE isActive = 1")
    fun getActiveSchedulesFlow(): Flow<List<CachedSchedule>>

    @Query("SELECT * FROM cached_schedules")
    suspend fun getAllSchedules(): List<CachedSchedule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSchedule(schedule: CachedSchedule)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSchedules(schedules: List<CachedSchedule>)

    @Query("DELETE FROM cached_schedules")
    suspend fun clearSchedules()

    @Query("DELETE FROM cached_schedules WHERE scheduleId NOT IN (:scheduleIds)")
    suspend fun deleteSchedulesNotIn(scheduleIds: List<String>)

    // Combined queries for enforcement
    @Transaction
    suspend fun getEnforcementSettings(): EnforcementSettings {
        val global = getGlobalSettings() ?: CachedGlobalSettings()
        val deviceOverrides = getDeviceOverrides()
        val schedules = getActiveSchedules()
        val lastSynced = maxOf(
            global.lastSyncedAt,
            deviceOverrides?.lastSyncedAt ?: 0,
            schedules.maxOfOrNull { it.lastSyncedAt } ?: 0
        )
        return EnforcementSettings(global, deviceOverrides, schedules, lastSynced)
    }

    // Clear all cached settings
    @Transaction
    suspend fun clearAllSettings() {
        clearSchedules()
        saveGlobalSettings(CachedGlobalSettings())
        saveDeviceOverrides(CachedDeviceOverrides())
    }
}
