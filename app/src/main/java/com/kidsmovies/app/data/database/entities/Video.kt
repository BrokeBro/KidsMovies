package com.kidsmovies.app.data.database.entities

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(tableName = "videos")
data class Video(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val filePath: String,
    val thumbnailPath: String? = null,
    val customThumbnailPath: String? = null,
    val tmdbArtworkPath: String? = null, // Auto-fetched from TMDB
    val duration: Long = 0, // in milliseconds
    val size: Long = 0, // in bytes
    val dateAdded: Long = System.currentTimeMillis(),
    val dateModified: Long = System.currentTimeMillis(),
    val lastPlayed: Long? = null,
    val playCount: Int = 0,
    val playbackPosition: Long = 0, // Resume position in milliseconds
    val isFavourite: Boolean = false,
    val isEnabled: Boolean = true, // For parental control (locked = visible but can't play)
    val isHidden: Boolean = false, // For parental control (hidden = completely invisible to child)
    val folderPath: String = "",
    val mimeType: String = "video/*",
    val collectionId: Long? = null, // For grouping into collections
    val seasonNumber: Int? = null, // Detected season number for TV episodes
    val episodeNumber: Int? = null, // Detected episode number for sorting
    val tmdbEpisodeId: Int? = null // TMDB episode ID for fetching correct artwork
) : Parcelable {

    fun getDisplayThumbnail(): String? {
        // Priority: user custom > TMDB artwork > auto-generated
        return customThumbnailPath ?: tmdbArtworkPath ?: thumbnailPath
    }

    fun getFormattedDuration(): String {
        val totalSeconds = duration / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    fun getFormattedSize(): String {
        val kb = size / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0

        return when {
            gb >= 1 -> String.format("%.1f GB", gb)
            mb >= 1 -> String.format("%.1f MB", mb)
            else -> String.format("%.1f KB", kb)
        }
    }

    fun hasResumePosition(): Boolean = playbackPosition > 0

    /**
     * Check if this video has episode info detected
     */
    fun hasEpisodeInfo(): Boolean = episodeNumber != null

    /**
     * Get formatted episode string (e.g., "S01E05" or "E05")
     */
    fun getEpisodeLabel(): String? {
        if (episodeNumber == null) return null
        return if (seasonNumber != null) {
            "S${seasonNumber.toString().padStart(2, '0')}E${episodeNumber.toString().padStart(2, '0')}"
        } else {
            "E${episodeNumber.toString().padStart(2, '0')}"
        }
    }
}
