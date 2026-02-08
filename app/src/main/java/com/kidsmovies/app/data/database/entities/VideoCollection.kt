package com.kidsmovies.app.data.database.entities

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(tableName = "collections")
data class VideoCollection(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val thumbnailPath: String? = null, // Custom thumbnail for the collection
    val tmdbArtworkPath: String? = null, // Auto-fetched from TMDB
    val parentCollectionId: Long? = null, // For TV show seasons (nested collections)
    val sortOrder: Int = 0, // For ordering collections on the home screen
    val dateCreated: Long = System.currentTimeMillis(),
    val dateModified: Long = System.currentTimeMillis()
) : Parcelable {

    /**
     * Get the best available thumbnail.
     * Priority: user custom > TMDB artwork
     */
    fun getDisplayThumbnail(): String? {
        return thumbnailPath ?: tmdbArtworkPath
    }

    /**
     * Check if this is a sub-collection (e.g., a TV season)
     */
    fun isSubCollection(): Boolean = parentCollectionId != null
}
