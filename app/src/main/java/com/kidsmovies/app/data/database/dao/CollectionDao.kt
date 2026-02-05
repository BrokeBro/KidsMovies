package com.kidsmovies.app.data.database.dao

import androidx.room.*
import com.kidsmovies.app.data.database.entities.Collection
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(collection: Collection): Long

    @Update
    suspend fun update(collection: Collection)

    @Delete
    suspend fun delete(collection: Collection)

    @Query("DELETE FROM collections WHERE id = :collectionId")
    suspend fun deleteById(collectionId: Long)

    @Query("SELECT * FROM collections ORDER BY sortOrder ASC, name ASC")
    fun getAllCollectionsFlow(): Flow<List<Collection>>

    @Query("SELECT * FROM collections ORDER BY sortOrder ASC, name ASC")
    suspend fun getAllCollections(): List<Collection>

    @Query("SELECT * FROM collections WHERE id = :collectionId")
    suspend fun getCollectionById(collectionId: Long): Collection?

    @Query("SELECT * FROM collections WHERE name = :name")
    suspend fun getCollectionByName(name: String): Collection?

    @Query("SELECT COUNT(*) FROM collections")
    suspend fun getCollectionCount(): Int

    @Query("UPDATE collections SET name = :name, dateModified = :dateModified WHERE id = :collectionId")
    suspend fun updateName(collectionId: Long, name: String, dateModified: Long = System.currentTimeMillis())

    @Query("UPDATE collections SET sortOrder = :sortOrder WHERE id = :collectionId")
    suspend fun updateSortOrder(collectionId: Long, sortOrder: Int)
}
