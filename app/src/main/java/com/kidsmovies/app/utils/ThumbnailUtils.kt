package com.kidsmovies.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object ThumbnailUtils {
    private const val TAG = "ThumbnailUtils"

    fun generateThumbnail(context: Context, videoPath: String, videoId: Long): String? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(videoPath)

            // Get frame at 1 second or first frame
            val bitmap = retriever.getFrameAtTime(
                1000000, // 1 second in microseconds
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: retriever.getFrameAtTime(0)

            retriever.release()

            if (bitmap != null) {
                // Scale bitmap
                val scaledBitmap = Bitmap.createScaledBitmap(
                    bitmap,
                    Constants.THUMBNAIL_WIDTH,
                    Constants.THUMBNAIL_HEIGHT,
                    true
                )

                // Save to file
                val thumbnailDir = File(context.filesDir, Constants.THUMBNAIL_DIR)
                if (!thumbnailDir.exists()) {
                    thumbnailDir.mkdirs()
                }

                val thumbnailFile = File(thumbnailDir, "thumb_$videoId.jpg")
                FileOutputStream(thumbnailFile).use { out ->
                    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }

                bitmap.recycle()
                if (scaledBitmap != bitmap) {
                    scaledBitmap.recycle()
                }

                thumbnailFile.absolutePath
            } else {
                Log.w(TAG, "Could not extract frame from video: $videoPath")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating thumbnail for: $videoPath", e)
            null
        }
    }

    fun deleteThumbnail(thumbnailPath: String?): Boolean {
        if (thumbnailPath == null) return false
        return try {
            File(thumbnailPath).delete()
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting thumbnail: $thumbnailPath", e)
            false
        }
    }

    fun getVideoDuration(videoPath: String): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(videoPath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            duration?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Error getting video duration: $videoPath", e)
            0L
        }
    }

    fun clearAllThumbnails(context: Context) {
        try {
            val thumbnailDir = File(context.filesDir, Constants.THUMBNAIL_DIR)
            if (thumbnailDir.exists()) {
                thumbnailDir.listFiles()?.forEach { it.delete() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing thumbnails", e)
        }
    }
}
