package com.kidsmovies.app.data.database.dao

import androidx.room.*
import com.kidsmovies.app.data.database.entities.ScanFolder
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanFolderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(scanFolder: ScanFolder): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(scanFolders: List<ScanFolder>): List<Long>

    @Update
    suspend fun update(scanFolder: ScanFolder)

    @Delete
    suspend fun delete(scanFolder: ScanFolder)

    @Query("DELETE FROM scan_folders WHERE id = :folderId")
    suspend fun deleteById(folderId: Long)

    @Query("DELETE FROM scan_folders")
    suspend fun deleteAll()

    @Query("SELECT * FROM scan_folders ORDER BY name ASC")
    fun getAllFoldersFlow(): Flow<List<ScanFolder>>

    @Query("SELECT * FROM scan_folders ORDER BY name ASC")
    suspend fun getAllFolders(): List<ScanFolder>

    @Query("SELECT * FROM scan_folders WHERE isEnabled = 1 ORDER BY name ASC")
    suspend fun getEnabledFolders(): List<ScanFolder>

    @Query("SELECT * FROM scan_folders WHERE id = :folderId")
    suspend fun getFolderById(folderId: Long): ScanFolder?

    @Query("SELECT * FROM scan_folders WHERE path = :path")
    suspend fun getFolderByPath(path: String): ScanFolder?

    @Query("UPDATE scan_folders SET isEnabled = :isEnabled WHERE id = :folderId")
    suspend fun updateEnabled(folderId: Long, isEnabled: Boolean)

    @Query("SELECT EXISTS(SELECT 1 FROM scan_folders WHERE path = :path)")
    suspend fun folderExists(path: String): Boolean

    @Query("SELECT COUNT(*) FROM scan_folders")
    suspend fun getFolderCount(): Int

    @Query("UPDATE scan_folders SET isDownloadFolder = 0")
    suspend fun clearAllDownloadFolders()

    @Query("UPDATE scan_folders SET isDownloadFolder = :isDownloadFolder WHERE id = :folderId")
    suspend fun updateDownloadFolder(folderId: Long, isDownloadFolder: Boolean)

    @Query("SELECT * FROM scan_folders WHERE isDownloadFolder = 1 LIMIT 1")
    suspend fun getDownloadFolder(): ScanFolder?
}
