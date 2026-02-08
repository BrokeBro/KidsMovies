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

    suspend fun updateTmdbArtwork(collectionId: Long, artworkPath: String?) = collectionDao.updateTmdbArtwork(collectionId, artworkPath)

    suspend fun getSubCollections(parentId: Long): List<VideoCollection> = collectionDao.getSubCollections(parentId)

    fun getSubCollectionsFlow(parentId: Long): Flow<List<VideoCollection>> = collectionDao.getSubCollectionsFlow(parentId)

    fun getTopLevelCollectionsFlow(): Flow<List<VideoCollection>> = collectionDao.getTopLevelCollectionsFlow()

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

    // TV Show and Season methods
    suspend fun getTvShows(): List<VideoCollection> = collectionDao.getTvShows()

    fun getTvShowsFlow(): Flow<List<VideoCollection>> = collectionDao.getTvShowsFlow()

    suspend fun getSeasonsForShow(tvShowId: Long): List<VideoCollection> =
        collectionDao.getSeasonsForShow(tvShowId)

    fun getSeasonsForShowFlow(tvShowId: Long): Flow<List<VideoCollection>> =
        collectionDao.getSeasonsForShowFlow(tvShowId)

    suspend fun getRegularCollections(): List<VideoCollection> = collectionDao.getRegularCollections()

    fun getRegularCollectionsFlow(): Flow<List<VideoCollection>> = collectionDao.getRegularCollectionsFlow()

    suspend fun getVideosInCollectionSorted(collectionId: Long): List<Video> =
        collectionDao.getVideosInCollectionSorted(collectionId)

    fun getVideosInCollectionSortedFlow(collectionId: Long): Flow<List<Video>> =
        collectionDao.getVideosInCollectionSortedFlow(collectionId)

    suspend fun updateCollectionType(collectionId: Long, type: String, parentId: Long?, seasonNumber: Int?) =
        collectionDao.updateCollectionType(collectionId, type, parentId, seasonNumber)

    suspend fun updateTmdbShowId(collectionId: Long, tmdbShowId: Int?) =
        collectionDao.updateTmdbShowId(collectionId, tmdbShowId)

    suspend fun updateEnabled(collectionId: Long, isEnabled: Boolean) =
        collectionDao.updateEnabled(collectionId, isEnabled)

    /**
     * Get episode count for all seasons of a TV show.
     * Returns a map of seasonId to episode count.
     */
    suspend fun getSeasonEpisodeCounts(tvShowId: Long): Map<Long, Int> {
        val seasons = getSeasonsForShow(tvShowId)
        return seasons.associate { season ->
            season.id to getVideoCountInCollection(season.id)
        }
    }
}
