package com.kidsmovies.app.data.repository

import com.kidsmovies.app.data.database.dao.CollectionDao
import com.kidsmovies.app.data.database.entities.Collection
import kotlinx.coroutines.flow.Flow

class CollectionRepository(private val collectionDao: CollectionDao) {

    fun getAllCollectionsFlow(): Flow<List<Collection>> = collectionDao.getAllCollectionsFlow()

    suspend fun getAllCollections(): List<Collection> = collectionDao.getAllCollections()

    suspend fun getCollectionById(collectionId: Long): Collection? = collectionDao.getCollectionById(collectionId)

    suspend fun getCollectionByName(name: String): Collection? = collectionDao.getCollectionByName(name)

    suspend fun getCollectionCount(): Int = collectionDao.getCollectionCount()

    suspend fun insertCollection(collection: Collection): Long = collectionDao.insert(collection)

    suspend fun updateCollection(collection: Collection) = collectionDao.update(collection)

    suspend fun updateCollectionName(collectionId: Long, name: String) = collectionDao.updateName(collectionId, name)

    suspend fun updateSortOrder(collectionId: Long, sortOrder: Int) = collectionDao.updateSortOrder(collectionId, sortOrder)

    suspend fun deleteCollection(collection: Collection) = collectionDao.delete(collection)

    suspend fun deleteCollectionById(collectionId: Long) = collectionDao.deleteById(collectionId)
}
