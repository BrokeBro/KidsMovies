package com.kidsmovies.app.data.repository

import com.kidsmovies.app.data.database.dao.VideoDao
import com.kidsmovies.app.data.database.entities.Video
import com.kidsmovies.app.data.database.entities.VideoWithTags
import kotlinx.coroutines.flow.Flow

class VideoRepository(private val videoDao: VideoDao) {

    // Flow getters for observing data
    fun getAllVideosFlow(): Flow<List<Video>> = videoDao.getAllVideosFlow()

    fun getAllVideosIncludingDisabledFlow(): Flow<List<Video>> = videoDao.getAllVideosIncludingDisabledFlow()

    fun getFavouritesFlow(): Flow<List<Video>> = videoDao.getFavouritesFlow()

    fun getRecentlyPlayedFlow(limit: Int = 10): Flow<List<Video>> = videoDao.getRecentlyPlayedFlow(limit)

    fun getVideosByFolderFlow(folderPath: String): Flow<List<Video>> = videoDao.getVideosByFolderFlow(folderPath)

    fun getVideosByCollectionFlow(collectionId: Long): Flow<List<Video>> = videoDao.getVideosByCollectionFlow(collectionId)

    fun getUncollectedVideosFlow(): Flow<List<Video>> = videoDao.getUncollectedVideosFlow()

    fun searchVideosFlow(query: String): Flow<List<Video>> = videoDao.searchVideosFlow(query)

    fun getAllVideosWithTagsFlow(): Flow<List<VideoWithTags>> = videoDao.getAllVideosWithTagsFlow()

    // Sorting options
    fun getVideosSortedByTitleAsc(): Flow<List<Video>> = videoDao.getVideosSortedByTitleAsc()
    fun getVideosSortedByTitleDesc(): Flow<List<Video>> = videoDao.getVideosSortedByTitleDesc()
    fun getVideosSortedByDateAsc(): Flow<List<Video>> = videoDao.getVideosSortedByDateAsc()
    fun getVideosSortedByDateDesc(): Flow<List<Video>> = videoDao.getVideosSortedByDateDesc()
    fun getVideosSortedByRecent(): Flow<List<Video>> = videoDao.getVideosSortedByRecent()

    // Suspend functions for one-time operations
    suspend fun getAllVideos(): List<Video> = videoDao.getAllVideos()

    suspend fun getAllVideosIncludingDisabled(): List<Video> = videoDao.getAllVideosIncludingDisabled()

    suspend fun getFavourites(): List<Video> = videoDao.getFavourites()

    suspend fun getRecentlyPlayed(limit: Int = 10): List<Video> = videoDao.getRecentlyPlayed(limit)

    suspend fun getVideosByCollection(collectionId: Long): List<Video> = videoDao.getVideosByCollection(collectionId)

    suspend fun searchVideos(query: String): List<Video> = videoDao.searchVideos(query)

    suspend fun getVideoById(videoId: Long): Video? = videoDao.getVideoById(videoId)

    suspend fun getVideoByPath(filePath: String): Video? = videoDao.getVideoByPath(filePath)

    suspend fun getVideoByTitle(title: String): Video? = videoDao.getVideoByTitle(title)

    suspend fun getVideoWithTags(videoId: Long): VideoWithTags? = videoDao.getVideoWithTags(videoId)

    suspend fun getAllFilePaths(): List<String> = videoDao.getAllFilePaths()

    suspend fun getVideoCount(): Int = videoDao.getVideoCount()

    suspend fun getFavouriteCount(): Int = videoDao.getFavouriteCount()

    suspend fun getVideoCountInCollection(collectionId: Long): Int = videoDao.getVideoCountInCollection(collectionId)

    suspend fun videoExists(filePath: String): Boolean = videoDao.videoExists(filePath)

    // Insert operations
    suspend fun insertVideo(video: Video): Long = videoDao.insert(video)

    suspend fun insertVideos(videos: List<Video>): List<Long> = videoDao.insertAll(videos)

    // Update operations
    suspend fun updateVideo(video: Video) = videoDao.update(video)

    suspend fun updateFavourite(videoId: Long, isFavourite: Boolean) = videoDao.updateFavourite(videoId, isFavourite)

    suspend fun updateEnabled(videoId: Long, isEnabled: Boolean) = videoDao.updateEnabled(videoId, isEnabled)

    suspend fun updatePlayStats(videoId: Long) = videoDao.updatePlayStats(videoId, System.currentTimeMillis())

    suspend fun updatePlaybackPosition(videoId: Long, position: Long) = videoDao.updatePlaybackPosition(videoId, position)

    suspend fun updateThumbnail(videoId: Long, thumbnailPath: String?) = videoDao.updateThumbnail(videoId, thumbnailPath)

    suspend fun updateCustomThumbnail(videoId: Long, thumbnailPath: String?) = videoDao.updateCustomThumbnail(videoId, thumbnailPath)

    suspend fun updateTmdbArtwork(videoId: Long, artworkPath: String?) = videoDao.updateTmdbArtwork(videoId, artworkPath)

    suspend fun updateEpisodeInfo(videoId: Long, seasonNumber: Int?, episodeNumber: Int?) =
        videoDao.updateEpisodeInfo(videoId, seasonNumber, episodeNumber)

    suspend fun updateTmdbEpisodeId(videoId: Long, tmdbEpisodeId: Int?) =
        videoDao.updateTmdbEpisodeId(videoId, tmdbEpisodeId)

    suspend fun updateCollection(videoId: Long, collectionId: Long?) = videoDao.updateCollection(videoId, collectionId)

    suspend fun updateCollectionForVideos(videoIds: List<Long>, collectionId: Long?) = videoDao.updateCollectionForVideos(videoIds, collectionId)

    // Delete operations
    suspend fun deleteVideo(video: Video) = videoDao.delete(video)

    suspend fun deleteVideoById(videoId: Long) = videoDao.deleteById(videoId)

    suspend fun deleteVideosByIds(videoIds: List<Long>) = videoDao.deleteByIds(videoIds)

    suspend fun deleteVideoByPath(filePath: String) = videoDao.deleteByPath(filePath)

    suspend fun deleteVideosByFolderPath(folderPath: String) = videoDao.deleteByFolderPath(folderPath)

    suspend fun deleteVideosByFolderPathPrefix(folderPathPrefix: String) = videoDao.deleteByFolderPathPrefix(folderPathPrefix)

    suspend fun deleteAllVideos() = videoDao.deleteAll()
}
