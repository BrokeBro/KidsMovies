package com.kidsmovies.app.data.database.dao

import androidx.room.*
import com.kidsmovies.app.data.database.entities.Video
import com.kidsmovies.app.data.database.entities.VideoCollection
import com.kidsmovies.app.data.database.entities.VideoCollectionCrossRef
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(collection: VideoCollection): Long

    @Update
    suspend fun update(collection: VideoCollection)

    @Delete
    suspend fun delete(collection: VideoCollection)

    @Query("DELETE FROM collections WHERE id = :collectionId")
    suspend fun deleteById(collectionId: Long)

    @Query("SELECT * FROM collections ORDER BY sortOrder ASC, name ASC")
    fun getAllCollectionsFlow(): Flow<List<VideoCollection>>

    @Query("SELECT * FROM collections ORDER BY sortOrder ASC, name ASC")
    suspend fun getAllCollections(): List<VideoCollection>

    @Query("SELECT * FROM collections WHERE id = :collectionId")
    suspend fun getCollectionById(collectionId: Long): VideoCollection?

    @Query("SELECT * FROM collections WHERE name = :name")
    suspend fun getCollectionByName(name: String): VideoCollection?

    @Query("SELECT COUNT(*) FROM collections")
    suspend fun getCollectionCount(): Int

    @Query("UPDATE collections SET name = :name, dateModified = :dateModified WHERE id = :collectionId")
    suspend fun updateName(collectionId: Long, name: String, dateModified: Long = System.currentTimeMillis())

    @Query("UPDATE collections SET sortOrder = :sortOrder WHERE id = :collectionId")
    suspend fun updateSortOrder(collectionId: Long, sortOrder: Int)

    @Query("UPDATE collections SET tmdbArtworkPath = :artworkPath WHERE id = :collectionId")
    suspend fun updateTmdbArtwork(collectionId: Long, artworkPath: String?)

    @Query("SELECT * FROM collections WHERE parentCollectionId = :parentId ORDER BY sortOrder ASC, name ASC")
    suspend fun getSubCollections(parentId: Long): List<VideoCollection>

    @Query("SELECT * FROM collections WHERE parentCollectionId = :parentId ORDER BY sortOrder ASC, name ASC")
    fun getSubCollectionsFlow(parentId: Long): Flow<List<VideoCollection>>

    @Query("SELECT * FROM collections WHERE parentCollectionId IS NULL ORDER BY sortOrder ASC, name ASC")
    fun getTopLevelCollectionsFlow(): Flow<List<VideoCollection>>

    // Video-Collection relationship methods
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertVideoCollectionCrossRef(crossRef: VideoCollectionCrossRef)

    @Delete
    suspend fun deleteVideoCollectionCrossRef(crossRef: VideoCollectionCrossRef)

    @Query("DELETE FROM video_collection_cross_ref WHERE videoId = :videoId AND collectionId = :collectionId")
    suspend fun removeVideoFromCollection(videoId: Long, collectionId: Long)

    @Query("DELETE FROM video_collection_cross_ref WHERE collectionId = :collectionId")
    suspend fun removeAllVideosFromCollection(collectionId: Long)

    @Query("SELECT v.* FROM videos v INNER JOIN video_collection_cross_ref vc ON v.id = vc.videoId WHERE vc.collectionId = :collectionId ORDER BY v.title ASC")
    fun getVideosInCollectionFlow(collectionId: Long): Flow<List<Video>>

    @Query("SELECT v.* FROM videos v INNER JOIN video_collection_cross_ref vc ON v.id = vc.videoId WHERE vc.collectionId = :collectionId ORDER BY v.title ASC")
    suspend fun getVideosInCollection(collectionId: Long): List<Video>

    @Query("SELECT COUNT(*) FROM video_collection_cross_ref WHERE collectionId = :collectionId")
    suspend fun getVideoCountInCollection(collectionId: Long): Int

    @Query("SELECT COUNT(*) FROM video_collection_cross_ref WHERE videoId = :videoId AND collectionId = :collectionId")
    suspend fun isVideoInCollection(videoId: Long, collectionId: Long): Int

    // TV Show and Season queries
    @Query("SELECT * FROM collections WHERE collectionType = 'TV_SHOW' AND parentCollectionId IS NULL ORDER BY sortOrder ASC, name ASC")
    suspend fun getTvShows(): List<VideoCollection>

    @Query("SELECT * FROM collections WHERE collectionType = 'TV_SHOW' AND parentCollectionId IS NULL ORDER BY sortOrder ASC, name ASC")
    fun getTvShowsFlow(): Flow<List<VideoCollection>>

    @Query("SELECT * FROM collections WHERE collectionType = 'SEASON' AND parentCollectionId = :tvShowId ORDER BY seasonNumber ASC, name ASC")
    suspend fun getSeasonsForShow(tvShowId: Long): List<VideoCollection>

    @Query("SELECT * FROM collections WHERE collectionType = 'SEASON' AND parentCollectionId = :tvShowId ORDER BY seasonNumber ASC, name ASC")
    fun getSeasonsForShowFlow(tvShowId: Long): Flow<List<VideoCollection>>

    @Query("SELECT * FROM collections WHERE collectionType = 'REGULAR' AND parentCollectionId IS NULL ORDER BY sortOrder ASC, name ASC")
    suspend fun getRegularCollections(): List<VideoCollection>

    @Query("SELECT * FROM collections WHERE collectionType = 'REGULAR' AND parentCollectionId IS NULL ORDER BY sortOrder ASC, name ASC")
    fun getRegularCollectionsFlow(): Flow<List<VideoCollection>>

    // Get videos in a season sorted by episode number
    @Query("""
        SELECT v.* FROM videos v
        INNER JOIN video_collection_cross_ref vc ON v.id = vc.videoId
        WHERE vc.collectionId = :collectionId
        ORDER BY
            CASE WHEN v.episodeNumber IS NOT NULL THEN v.episodeNumber ELSE 999999 END ASC,
            v.title ASC
    """)
    suspend fun getVideosInCollectionSorted(collectionId: Long): List<Video>

    @Query("""
        SELECT v.* FROM videos v
        INNER JOIN video_collection_cross_ref vc ON v.id = vc.videoId
        WHERE vc.collectionId = :collectionId
        ORDER BY
            CASE WHEN v.episodeNumber IS NOT NULL THEN v.episodeNumber ELSE 999999 END ASC,
            v.title ASC
    """)
    fun getVideosInCollectionSortedFlow(collectionId: Long): Flow<List<Video>>

    // Update collection type and parent
    @Query("UPDATE collections SET collectionType = :type, parentCollectionId = :parentId, seasonNumber = :seasonNumber WHERE id = :collectionId")
    suspend fun updateCollectionType(collectionId: Long, type: String, parentId: Long?, seasonNumber: Int?)

    @Query("UPDATE collections SET tmdbShowId = :tmdbShowId WHERE id = :collectionId")
    suspend fun updateTmdbShowId(collectionId: Long, tmdbShowId: Int?)
}
