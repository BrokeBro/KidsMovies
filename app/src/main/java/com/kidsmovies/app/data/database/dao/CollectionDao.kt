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
}
