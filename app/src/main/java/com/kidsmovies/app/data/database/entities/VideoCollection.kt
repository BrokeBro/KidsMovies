package com.kidsmovies.app.data.database.entities

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

/**
 * Collection types for organizing content
 */
enum class CollectionType {
    REGULAR,    // Normal collection (movies, mixed content)
    TV_SHOW,    // Parent container for seasons
    SEASON      // Contains episodes, linked to a TV_SHOW parent
}

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
    val collectionType: String = CollectionType.REGULAR.name, // REGULAR, TV_SHOW, or SEASON
    val seasonNumber: Int? = null, // Season number for SEASON type collections
    val tmdbShowId: Int? = null, // TMDB TV show ID for fetching correct artwork
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

    /**
     * Get the collection type as enum
     */
    fun getType(): CollectionType {
        return try {
            CollectionType.valueOf(collectionType)
        } catch (e: Exception) {
            CollectionType.REGULAR
        }
    }

    /**
     * Check if this is a TV show (has seasons)
     */
    fun isTvShow(): Boolean = getType() == CollectionType.TV_SHOW

    /**
     * Check if this is a season (part of a TV show)
     */
    fun isSeason(): Boolean = getType() == CollectionType.SEASON
}
