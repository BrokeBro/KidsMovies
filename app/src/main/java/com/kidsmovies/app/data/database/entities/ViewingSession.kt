package com.kidsmovies.app.data.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tracks individual viewing sessions for metrics and parental controls.
 * A session starts when a video begins playing and ends when playback stops.
 */
@Entity(
    tableName = "viewing_sessions",
    foreignKeys = [
        ForeignKey(
            entity = Video::class,
            parentColumns = ["id"],
            childColumns = ["videoId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["videoId"]),
        Index(value = ["startTime"]),
        Index(value = ["collectionId"])
    ]
)
data class ViewingSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val videoId: Long,
    val collectionId: Long? = null, // Which collection the video was viewed from (if any)
    val videoTitle: String, // Store title for display even if video is deleted
    val collectionName: String? = null, // Store collection name for display
    val startTime: Long, // Timestamp when viewing started
    val endTime: Long? = null, // Timestamp when viewing ended (null if still watching)
    val durationWatched: Long = 0, // Actual milliseconds watched (excluding pauses)
    val videoDuration: Long = 0, // Total video duration for reference
    val completed: Boolean = false // Whether the video was watched to completion
) {
    /**
     * Get duration watched in minutes
     */
    fun getDurationWatchedMinutes(): Double {
        return durationWatched / 60000.0
    }

    /**
     * Get duration watched in hours
     */
    fun getDurationWatchedHours(): Double {
        return durationWatched / 3600000.0
    }

    /**
     * Get formatted duration watched
     */
    fun getFormattedDurationWatched(): String {
        val totalSeconds = durationWatched / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return when {
            hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, seconds)
            else -> String.format("%d:%02d", minutes, seconds)
        }
    }
}
