package com.kidsmovies.app.data.repository

import com.kidsmovies.app.data.database.dao.CollectionDao
import com.kidsmovies.app.data.database.entities.VideoCollection
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
}
