package com.kidsmovies.app.data.repository

import com.kidsmovies.app.data.database.dao.CollectionDao
import com.kidsmovies.app.data.database.entities.Video
import com.kidsmovies.app.data.database.entities.VideoCollection
import com.kidsmovies.app.data.database.entities.VideoCollectionCrossRef
import kotlinx.coroutines.flow.Flow

class CollectionRepository(private val collectionDao: CollectionDao) {

    fun getAllCollectionsFlow(): Flow<List<VideoCollection>> = collectionDao.getAllCollectionsFlow()

    suspend fun getAllCollections(): List<VideoCollection> = collectionDao.getAllCollections()

    suspend fun getCollectionById(collectionId: Long): VideoCollection? = collectionDao.getCollectionById(collectionId)

    suspend fun getCollectionByName(name: String): VideoCollection? = collectionDao.getCollectionByName(name)

    suspend fun getCollectionCount(): Int = collectionDao.getCollectionCount()

    suspend fun insertCollection(collection: VideoCollection): Long = collectionDao.insert(collection)

    suspend fun updateCollection(collection: VideoCollection) = collectionDao.update(collection)

    suspend fun updateCollectionName(collectionId: Long, name: String) = collectionDao.updateName(collectionId, name)

    suspend fun updateSortOrder(collectionId: Long, sortOrder: Int) = collectionDao.updateSortOrder(collectionId, sortOrder)

    suspend fun deleteCollection(collection: VideoCollection) = collectionDao.delete(collection)

    suspend fun deleteCollectionById(collectionId: Long) = collectionDao.deleteById(collectionId)

    // Video-Collection relationship methods
    suspend fun addVideoToCollection(collectionId: Long, videoId: Long) {
        collectionDao.insertVideoCollectionCrossRef(VideoCollectionCrossRef(videoId, collectionId))
    }

    suspend fun removeVideoFromCollection(collectionId: Long, videoId: Long) {
        collectionDao.removeVideoFromCollection(videoId, collectionId)
    }

    suspend fun removeAllVideosFromCollection(collectionId: Long) {
        collectionDao.removeAllVideosFromCollection(collectionId)
    }

    fun getVideosInCollectionFlow(collectionId: Long): Flow<List<Video>> =
        collectionDao.getVideosInCollectionFlow(collectionId)

    suspend fun getVideosInCollection(collectionId: Long): List<Video> =
        collectionDao.getVideosInCollection(collectionId)

    suspend fun getVideoCountInCollection(collectionId: Long): Int =
        collectionDao.getVideoCountInCollection(collectionId)

    suspend fun isVideoInCollection(videoId: Long, collectionId: Long): Boolean =
        collectionDao.isVideoInCollection(videoId, collectionId) > 0
}
