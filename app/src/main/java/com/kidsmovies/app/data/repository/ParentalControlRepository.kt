package com.kidsmovies.app.data.repository

import com.kidsmovies.app.data.database.dao.ParentalControlDao
import com.kidsmovies.app.data.database.entities.ParentalControl
import kotlinx.coroutines.flow.Flow

class ParentalControlRepository(
    private val parentalControlDao: ParentalControlDao
) {
    fun getParentalControlFlow(): Flow<ParentalControl?> = parentalControlDao.getParentalControlFlow()

    suspend fun getParentalControl(): ParentalControl? = parentalControlDao.getParentalControl()

    suspend fun saveParentalControl(parentalControl: ParentalControl) =
        parentalControlDao.insert(parentalControl)

    suspend fun updateParentalControl(parentalControl: ParentalControl) =
        parentalControlDao.update(parentalControl)

    suspend fun isAppEnabled(): Boolean = parentalControlDao.isAppEnabled() ?: true

    suspend fun getLastKnownStatus(): Boolean = parentalControlDao.getLastKnownStatus() ?: true

    suspend fun setAppEnabled(enabled: Boolean) {
        parentalControlDao.updateAppEnabled(enabled, System.currentTimeMillis())
    }

    suspend fun updateLastOnlineCheck(checkTime: Long) =
        parentalControlDao.updateLastOnlineCheck(checkTime)

    suspend fun updateLastKnownStatus(status: Boolean) =
        parentalControlDao.updateLastKnownStatus(status)

    suspend fun getDeviceId(): String? = parentalControlDao.getDeviceId()

    suspend fun setDeviceId(deviceId: String) = parentalControlDao.updateDeviceId(deviceId)

    suspend fun setServerUrl(url: String) = parentalControlDao.updateServerUrl(url)

    suspend fun setBlockedMessage(message: String) = parentalControlDao.updateBlockedMessage(message)

    suspend fun updateSchedule(enabled: Boolean, startTime: String, endTime: String) =
        parentalControlDao.updateSchedule(enabled, startTime, endTime)
}
