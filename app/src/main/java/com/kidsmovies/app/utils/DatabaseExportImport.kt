package com.kidsmovies.app.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.kidsmovies.app.KidsMoviesApp
import com.kidsmovies.app.data.database.entities.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

object DatabaseExportImport {
    private const val TAG = "DatabaseExportImport"

    data class ExportData(
        val exportVersion: Int = 1,
        val exportDate: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
        val appSettings: AppSettings?,
        val parentalControl: ParentalControl?,
        val tags: List<Tag>,
        val scanFolders: List<ScanFolder>,
        val videos: List<Video>,
        val videoTagCrossRefs: List<VideoTagCrossRefExport>
    )

    data class VideoTagCrossRefExport(
        val videoId: Long,
        val tagId: Long
    )

    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .create()

    suspend fun exportToFile(context: Context, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            val app = KidsMoviesApp.instance
            val database = app.database

            // Collect all data
            val appSettings = database.appSettingsDao().getSettings()
            val parentalControl = database.parentalControlDao().getParentalControl()
            val tags = database.tagDao().getAllTags()
            val scanFolders = database.scanFolderDao().getAllFolders()
            val videos = database.videoDao().getAllVideos()

            // Collect video-tag relationships
            val videoTagCrossRefs = mutableListOf<VideoTagCrossRefExport>()
            videos.forEach { video ->
                val videoTags = database.tagDao().getTagsForVideo(video.id)
                videoTags.forEach { tag ->
                    videoTagCrossRefs.add(VideoTagCrossRefExport(video.id, tag.id))
                }
            }

            val exportData = ExportData(
                appSettings = appSettings,
                parentalControl = parentalControl,
                tags = tags,
                scanFolders = scanFolders,
                videos = videos,
                videoTagCrossRefs = videoTagCrossRefs
            )

            val json = gson.toJson(exportData)

            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    writer.write(json)
                }
            }

            Result.success("Export completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting database", e)
            Result.failure(e)
        }
    }

    suspend fun exportToInternalFile(context: Context): Result<File> = withContext(Dispatchers.IO) {
        try {
            val app = KidsMoviesApp.instance
            val database = app.database

            // Collect all data
            val appSettings = database.appSettingsDao().getSettings()
            val parentalControl = database.parentalControlDao().getParentalControl()
            val tags = database.tagDao().getAllTags()
            val scanFolders = database.scanFolderDao().getAllFolders()
            val videos = database.videoDao().getAllVideos()

            // Collect video-tag relationships
            val videoTagCrossRefs = mutableListOf<VideoTagCrossRefExport>()
            videos.forEach { video ->
                val videoTags = database.tagDao().getTagsForVideo(video.id)
                videoTags.forEach { tag ->
                    videoTagCrossRefs.add(VideoTagCrossRefExport(video.id, tag.id))
                }
            }

            val exportData = ExportData(
                appSettings = appSettings,
                parentalControl = parentalControl,
                tags = tags,
                scanFolders = scanFolders,
                videos = videos,
                videoTagCrossRefs = videoTagCrossRefs
            )

            val json = gson.toJson(exportData)

            val exportDir = File(context.filesDir, "exports")
            if (!exportDir.exists()) {
                exportDir.mkdirs()
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val exportFile = File(exportDir, "kidsmovies_backup_$timestamp.json")

            FileWriter(exportFile).use { writer ->
                writer.write(json)
            }

            Result.success(exportFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting database", e)
            Result.failure(e)
        }
    }

    suspend fun importFromFile(context: Context, uri: Uri, clearExisting: Boolean = false): Result<ImportResult> = withContext(Dispatchers.IO) {
        try {
            val json = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    reader.readText()
                }
            } ?: return@withContext Result.failure(Exception("Could not read file"))

            importFromJson(json, clearExisting)
        } catch (e: Exception) {
            Log.e(TAG, "Error importing database", e)
            Result.failure(e)
        }
    }

    suspend fun importFromJson(json: String, clearExisting: Boolean = false): Result<ImportResult> = withContext(Dispatchers.IO) {
        try {
            val exportData = gson.fromJson(json, ExportData::class.java)
            val app = KidsMoviesApp.instance
            val database = app.database

            var videosImported = 0
            var tagsImported = 0
            var foldersImported = 0

            if (clearExisting) {
                database.videoDao().deleteAll()
                database.tagDao().deleteAllUserTags()
                database.scanFolderDao().deleteAll()
            }

            // Import app settings (merge with existing)
            exportData.appSettings?.let { settings ->
                val existingSettings = database.appSettingsDao().getSettings()
                if (existingSettings != null) {
                    database.appSettingsDao().update(settings.copy(id = 1))
                } else {
                    database.appSettingsDao().insert(settings.copy(id = 1))
                }
            }

            // Import parental control (merge with existing)
            exportData.parentalControl?.let { control ->
                val existingControl = database.parentalControlDao().getParentalControl()
                if (existingControl != null) {
                    database.parentalControlDao().update(control.copy(id = 1))
                } else {
                    database.parentalControlDao().insert(control.copy(id = 1))
                }
            }

            // Import tags
            val tagIdMapping = mutableMapOf<Long, Long>()
            exportData.tags.forEach { tag ->
                if (!tag.isSystemTag) { // Don't overwrite system tags
                    val existingTag = database.tagDao().getTagByName(tag.name)
                    if (existingTag == null) {
                        val newId = database.tagDao().insert(tag.copy(id = 0))
                        tagIdMapping[tag.id] = newId
                        tagsImported++
                    } else {
                        tagIdMapping[tag.id] = existingTag.id
                    }
                } else {
                    val systemTag = database.tagDao().getTagByName(tag.name)
                    if (systemTag != null) {
                        tagIdMapping[tag.id] = systemTag.id
                    }
                }
            }

            // Import scan folders
            exportData.scanFolders.forEach { folder ->
                if (!database.scanFolderDao().folderExists(folder.path)) {
                    database.scanFolderDao().insert(folder.copy(id = 0))
                    foldersImported++
                }
            }

            // Import videos
            val videoIdMapping = mutableMapOf<Long, Long>()
            exportData.videos.forEach { video ->
                val existingVideo = database.videoDao().getVideoByPath(video.filePath)
                if (existingVideo == null) {
                    // Check if file still exists
                    if (File(video.filePath).exists()) {
                        val newId = database.videoDao().insert(video.copy(id = 0))
                        videoIdMapping[video.id] = newId
                        videosImported++
                    }
                } else {
                    videoIdMapping[video.id] = existingVideo.id
                    // Update existing video with imported data (except id and path)
                    database.videoDao().update(existingVideo.copy(
                        isFavourite = video.isFavourite,
                        isEnabled = video.isEnabled,
                        customThumbnailPath = video.customThumbnailPath
                    ))
                }
            }

            // Import video-tag relationships
            exportData.videoTagCrossRefs.forEach { crossRef ->
                val newVideoId = videoIdMapping[crossRef.videoId]
                val newTagId = tagIdMapping[crossRef.tagId]
                if (newVideoId != null && newTagId != null) {
                    if (!database.tagDao().videoHasTag(newVideoId, newTagId)) {
                        database.tagDao().insertVideoTagCrossRef(VideoTagCrossRef(newVideoId, newTagId))
                    }
                }
            }

            Result.success(ImportResult(videosImported, tagsImported, foldersImported))
        } catch (e: Exception) {
            Log.e(TAG, "Error importing database", e)
            Result.failure(e)
        }
    }

    data class ImportResult(
        val videosImported: Int,
        val tagsImported: Int,
        val foldersImported: Int
    )
}
