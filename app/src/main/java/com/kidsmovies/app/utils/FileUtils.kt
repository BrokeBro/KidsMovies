package com.kidsmovies.app.utils

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File

object FileUtils {

    fun isVideoFile(fileName: String): Boolean {
        val lowerName = fileName.lowercase()
        return Constants.VIDEO_EXTENSIONS.any { lowerName.endsWith(it) }
    }

    fun getVideoFiles(folderPath: String, includeSubfolders: Boolean): List<File> {
        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) {
            return emptyList()
        }

        val videos = mutableListOf<File>()

        if (includeSubfolders) {
            folder.walkTopDown().forEach { file ->
                if (file.isFile && isVideoFile(file.name)) {
                    videos.add(file)
                }
            }
        } else {
            folder.listFiles()?.forEach { file ->
                if (file.isFile && isVideoFile(file.name)) {
                    videos.add(file)
                }
            }
        }

        return videos
    }

    fun getFolderName(folderPath: String): String {
        return File(folderPath).name
    }

    fun getFileNameWithoutExtension(filePath: String): String {
        val file = File(filePath)
        val name = file.nameWithoutExtension
        // Clean up common video naming patterns
        return name
            .replace("_", " ")
            .replace("-", " ")
            .replace(".", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun getFileSizeBytes(filePath: String): Long {
        return try {
            File(filePath).length()
        } catch (e: Exception) {
            0L
        }
    }

    fun getMimeType(filePath: String): String {
        val extension = File(filePath).extension.lowercase()
        return when (extension) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/avi"
            "mov" -> "video/quicktime"
            "wmv" -> "video/x-ms-wmv"
            "flv" -> "video/x-flv"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            "3g2" -> "video/3gpp2"
            "mpeg", "mpg" -> "video/mpeg"
            else -> "video/*"
        }
    }

    fun getDefaultStorageFolders(): List<String> {
        val folders = mutableListOf<String>()

        // Internal storage common folders
        val internalStorage = Environment.getExternalStorageDirectory().absolutePath
        val commonFolders = listOf("Movies", "Download", "DCIM", "Video", "Downloads")

        commonFolders.forEach { folder ->
            val path = "$internalStorage/$folder"
            if (File(path).exists()) {
                folders.add(path)
            }
        }

        return folders
    }

    fun getFileFromUri(context: Context, uri: Uri): File? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val fileName = getFileNameFromUri(context, uri)
            val tempFile = File(context.cacheDir, fileName ?: "temp_file")

            inputStream?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            tempFile
        } catch (e: Exception) {
            null
        }
    }

    fun getFileNameFromUri(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        result = it.getString(index)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }

    fun queryVideosFromMediaStore(context: Context): List<MediaStoreVideo> {
        val videos = mutableListOf<MediaStoreVideo>()

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.MIME_TYPE
        )

        val sortOrder = "${MediaStore.Video.Media.DISPLAY_NAME} ASC"

        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val mimeTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)

            while (cursor.moveToNext()) {
                videos.add(
                    MediaStoreVideo(
                        id = cursor.getLong(idColumn),
                        displayName = cursor.getString(nameColumn),
                        filePath = cursor.getString(dataColumn),
                        duration = cursor.getLong(durationColumn),
                        size = cursor.getLong(sizeColumn),
                        dateAdded = cursor.getLong(dateAddedColumn) * 1000,
                        dateModified = cursor.getLong(dateModifiedColumn) * 1000,
                        mimeType = cursor.getString(mimeTypeColumn) ?: "video/*"
                    )
                )
            }
        }

        return videos
    }

    data class MediaStoreVideo(
        val id: Long,
        val displayName: String,
        val filePath: String,
        val duration: Long,
        val size: Long,
        val dateAdded: Long,
        val dateModified: Long,
        val mimeType: String
    )
}
