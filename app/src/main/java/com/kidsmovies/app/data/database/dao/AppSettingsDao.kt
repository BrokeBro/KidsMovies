package com.kidsmovies.app.data.database.dao

import androidx.room.*
import com.kidsmovies.app.data.database.entities.AppSettings
import kotlinx.coroutines.flow.Flow

@Dao
interface AppSettingsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(settings: AppSettings)

    @Update
    suspend fun update(settings: AppSettings)

    @Query("SELECT * FROM app_settings WHERE id = 1")
    fun getSettingsFlow(): Flow<AppSettings?>

    @Query("SELECT * FROM app_settings WHERE id = 1")
    suspend fun getSettings(): AppSettings?

    @Query("UPDATE app_settings SET isSetupComplete = :isComplete WHERE id = 1")
    suspend fun updateSetupComplete(isComplete: Boolean)

    @Query("UPDATE app_settings SET colorScheme = :colorScheme WHERE id = 1")
    suspend fun updateColorScheme(colorScheme: String)

    @Query("UPDATE app_settings SET childName = :childName WHERE id = 1")
    suspend fun updateChildName(childName: String)

    @Query("UPDATE app_settings SET lastScanTime = :scanTime WHERE id = 1")
    suspend fun updateLastScanTime(scanTime: Long)

    @Query("UPDATE app_settings SET gridColumns = :columns WHERE id = 1")
    suspend fun updateGridColumns(columns: Int)

    @Query("UPDATE app_settings SET sortOrder = :sortOrder WHERE id = 1")
    suspend fun updateSortOrder(sortOrder: String)

    @Query("UPDATE app_settings SET oneDriveFolderUrl = :url, oneDriveEnabled = :enabled WHERE id = 1")
    suspend fun updateOneDriveSettings(url: String, enabled: Boolean)

    @Query("SELECT isSetupComplete FROM app_settings WHERE id = 1")
    suspend fun isSetupComplete(): Boolean?

    @Query("SELECT colorScheme FROM app_settings WHERE id = 1")
    suspend fun getColorScheme(): String?

    @Query("UPDATE app_settings SET showAllMoviesTab = :show WHERE id = 1")
    suspend fun updateShowAllMoviesTab(show: Boolean)

    @Query("UPDATE app_settings SET showFavouritesTab = :show WHERE id = 1")
    suspend fun updateShowFavouritesTab(show: Boolean)

    @Query("UPDATE app_settings SET showCollectionsTab = :show WHERE id = 1")
    suspend fun updateShowCollectionsTab(show: Boolean)

    @Query("UPDATE app_settings SET showRecentTab = :show WHERE id = 1")
    suspend fun updateShowRecentTab(show: Boolean)

    @Query("UPDATE app_settings SET showOnlineTab = :show WHERE id = 1")
    suspend fun updateShowOnlineTab(show: Boolean)

    @Query("UPDATE app_settings SET tabOrder = :tabOrder WHERE id = 1")
    suspend fun updateTabOrder(tabOrder: String)

    @Query("SELECT tabOrder FROM app_settings WHERE id = 1")
    suspend fun getTabOrder(): String?
}
