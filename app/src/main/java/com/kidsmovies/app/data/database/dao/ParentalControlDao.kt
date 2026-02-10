package com.kidsmovies.app.data.database.dao

import androidx.room.*
import com.kidsmovies.app.data.database.entities.ParentalControl
import kotlinx.coroutines.flow.Flow

@Dao
interface ParentalControlDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(parentalControl: ParentalControl)

    @Update
    suspend fun update(parentalControl: ParentalControl)

    @Query("SELECT * FROM parental_control WHERE id = 1")
    fun getParentalControlFlow(): Flow<ParentalControl?>

    @Query("SELECT * FROM parental_control WHERE id = 1")
    suspend fun getParentalControl(): ParentalControl?

    @Query("UPDATE parental_control SET isAppEnabled = :isEnabled, lastOnlineCheck = :checkTime, lastKnownStatus = :isEnabled WHERE id = 1")
    suspend fun updateAppEnabled(isEnabled: Boolean, checkTime: Long = System.currentTimeMillis())

    @Query("UPDATE parental_control SET lastOnlineCheck = :checkTime WHERE id = 1")
    suspend fun updateLastOnlineCheck(checkTime: Long)

    @Query("UPDATE parental_control SET lastKnownStatus = :status WHERE id = 1")
    suspend fun updateLastKnownStatus(status: Boolean)

    @Query("UPDATE parental_control SET deviceId = :deviceId WHERE id = 1")
    suspend fun updateDeviceId(deviceId: String)

    @Query("UPDATE parental_control SET parentalServerUrl = :url WHERE id = 1")
    suspend fun updateServerUrl(url: String)

    @Query("UPDATE parental_control SET blockedMessage = :message WHERE id = 1")
    suspend fun updateBlockedMessage(message: String)

    @Query("UPDATE parental_control SET scheduleEnabled = :enabled, scheduleStartTime = :startTime, scheduleEndTime = :endTime WHERE id = 1")
    suspend fun updateSchedule(enabled: Boolean, startTime: String, endTime: String)

    @Query("SELECT isAppEnabled FROM parental_control WHERE id = 1")
    suspend fun isAppEnabled(): Boolean?

    @Query("SELECT lastKnownStatus FROM parental_control WHERE id = 1")
    suspend fun getLastKnownStatus(): Boolean?

    @Query("SELECT deviceId FROM parental_control WHERE id = 1")
    suspend fun getDeviceId(): String?
}
