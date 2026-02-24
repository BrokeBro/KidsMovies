package com.kidsmovies.app.data.repository

import com.kidsmovies.app.data.database.dao.AppSettingsDao
import com.kidsmovies.app.data.database.dao.ScanFolderDao
import com.kidsmovies.app.data.database.entities.AppSettings
import com.kidsmovies.app.data.database.entities.ScanFolder
import kotlinx.coroutines.flow.Flow

class SettingsRepository(
    private val appSettingsDao: AppSettingsDao,
    private val scanFolderDao: ScanFolderDao
) {
    // App Settings
    fun getSettingsFlow(): Flow<AppSettings?> = appSettingsDao.getSettingsFlow()

    suspend fun getSettings(): AppSettings? = appSettingsDao.getSettings()

    suspend fun saveSettings(settings: AppSettings) = appSettingsDao.insert(settings)

    suspend fun updateSettings(settings: AppSettings) = appSettingsDao.update(settings)

    suspend fun isSetupComplete(): Boolean = appSettingsDao.isSetupComplete() ?: false

    suspend fun setSetupComplete(complete: Boolean) = appSettingsDao.updateSetupComplete(complete)

    suspend fun getColorScheme(): String = appSettingsDao.getColorScheme() ?: "blue"

    suspend fun setColorScheme(colorScheme: String) = appSettingsDao.updateColorScheme(colorScheme)

    suspend fun setChildName(name: String) = appSettingsDao.updateChildName(name)

    suspend fun updateLastScanTime(time: Long) = appSettingsDao.updateLastScanTime(time)

    suspend fun setGridColumns(columns: Int) = appSettingsDao.updateGridColumns(columns)

    suspend fun setSortOrder(sortOrder: String) = appSettingsDao.updateSortOrder(sortOrder)

    suspend fun updateOneDriveSettings(url: String, enabled: Boolean) =
        appSettingsDao.updateOneDriveSettings(url, enabled)

    // Tab Visibility Settings
    suspend fun setShowAllMoviesTab(show: Boolean) = appSettingsDao.updateShowAllMoviesTab(show)

    suspend fun setShowFavouritesTab(show: Boolean) = appSettingsDao.updateShowFavouritesTab(show)

    suspend fun setShowCollectionsTab(show: Boolean) = appSettingsDao.updateShowCollectionsTab(show)

    suspend fun setShowRecentTab(show: Boolean) = appSettingsDao.updateShowRecentTab(show)

    suspend fun setShowOnlineTab(show: Boolean) = appSettingsDao.updateShowOnlineTab(show)

    suspend fun setTabOrder(tabOrder: String) = appSettingsDao.updateTabOrder(tabOrder)

    suspend fun getTabOrder(): String = appSettingsDao.getTabOrder() ?: "all_movies,favourites,collections,recent,online"

    // Scan Folders
    fun getAllFoldersFlow(): Flow<List<ScanFolder>> = scanFolderDao.getAllFoldersFlow()

    suspend fun getAllFolders(): List<ScanFolder> = scanFolderDao.getAllFolders()

    suspend fun getEnabledFolders(): List<ScanFolder> = scanFolderDao.getEnabledFolders()

    suspend fun addFolder(scanFolder: ScanFolder): Long = scanFolderDao.insert(scanFolder)

    suspend fun updateFolder(scanFolder: ScanFolder) = scanFolderDao.update(scanFolder)

    suspend fun deleteFolder(scanFolder: ScanFolder) = scanFolderDao.delete(scanFolder)

    suspend fun deleteFolderById(folderId: Long) = scanFolderDao.deleteById(folderId)

    suspend fun getFolderById(folderId: Long): ScanFolder? = scanFolderDao.getFolderById(folderId)

    suspend fun getFolderByPath(path: String): ScanFolder? = scanFolderDao.getFolderByPath(path)

    suspend fun folderExists(path: String): Boolean = scanFolderDao.folderExists(path)

    suspend fun setFolderEnabled(folderId: Long, enabled: Boolean) =
        scanFolderDao.updateEnabled(folderId, enabled)

    suspend fun getFolderCount(): Int = scanFolderDao.getFolderCount()

    // Franchise collection settings
    suspend fun setAutoCreateFranchiseCollections(enabled: Boolean) =
        appSettingsDao.updateAutoCreateFranchiseCollections(enabled)
}
