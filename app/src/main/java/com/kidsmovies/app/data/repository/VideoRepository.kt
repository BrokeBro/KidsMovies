package com.kidsmovies.app.data.repository

import com.kidsmovies.app.data.database.dao.VideoDao
import com.kidsmovies.app.data.database.dao.TagDao
import com.kidsmovies.app.data.database.entities.Video
import com.kidsmovies.app.data.database.entities.VideoTagCrossRef
import com.kidsmovies.app.data.database.entities.VideoWithTags
import kotlinx.coroutines.flow.Flow

class VideoRepository(
    private val videoDao: VideoDao,
    private val tagDao: TagDao
) {
    // Flow operations for reactive UI updates
    fun getAllVideosFlow(): Flow<List<Video>> = videoDao.getAllVideosFlow()

    fun getAllVideosIncludingDisabledFlow(): Flow<List<Video>> = videoDao.getAllVideosIncludingDisabledFlow()

    fun getFavouritesFlow(): Flow<List<Video>> = videoDao.getFavouritesFlow()

    fun getRecentlyPlayedFlow(limit: Int = 10): Flow<List<Video>> = videoDao.getRecentlyPlayedFlow(limit)

    fun searchVideosFlow(query: String): Flow<List<Video>> = videoDao.searchVideosFlow(query)

    fun getAllVideosWithTagsFlow(): Flow<List<VideoWithTags>> = videoDao.getAllVideosWithTagsFlow()

    // Sorted flows
    fun getVideosSorted(sortOrder: String): Flow<List<Video>> {
        return when (sortOrder) {
            "title_asc" -> videoDao.getVideosSortedByTitleAsc()
            "title_desc" -> videoDao.getVideosSortedByTitleDesc()
            "date_asc" -> videoDao.getVideosSortedByDateAsc()
            "date_desc" -> videoDao.getVideosSortedByDateDesc()
            "recent" -> videoDao.getVideosSortedByRecent()
            else -> videoDao.getVideosSortedByTitleAsc()
        }
    }

    // Suspend operations
    suspend fun getAllVideos(): List<Video> = videoDao.getAllVideos()

    suspend fun getAllVideosIncludingDisabled(): List<Video> = videoDao.getAllVideosIncludingDisabled()

    suspend fun getFavourites(): List<Video> = videoDao.getFavourites()

    suspend fun getVideoById(id: Long): Video? = videoDao.getVideoById(id)

    suspend fun getVideoByPath(path: String): Video? = videoDao.getVideoByPath(path)

    suspend fun getVideoWithTags(videoId: Long): VideoWithTags? = videoDao.getVideoWithTags(videoId)

    suspend fun searchVideos(query: String): List<Video> = videoDao.searchVideos(query)

    suspend fun getVideoCount(): Int = videoDao.getVideoCount()

    suspend fun getFavouriteCount(): Int = videoDao.getFavouriteCount()

    suspend fun videoExists(filePath: String): Boolean = videoDao.videoExists(filePath)

    suspend fun getAllFilePaths(): List<String> = videoDao.getAllFilePaths()

    // Insert/Update operations
    suspend fun insertVideo(video: Video): Long = videoDao.insert(video)

    suspend fun insertVideos(videos: List<Video>): List<Long> = videoDao.insertAll(videos)

    suspend fun updateVideo(video: Video) = videoDao.update(video)

    suspend fun updateFavourite(videoId: Long, isFavourite: Boolean) =
        videoDao.updateFavourite(videoId, isFavourite)

    suspend fun updateEnabled(videoId: Long, isEnabled: Boolean) =
        videoDao.updateEnabled(videoId, isEnabled)

    suspend fun updatePlayStats(videoId: Long) =
        videoDao.updatePlayStats(videoId, System.currentTimeMillis())

    suspend fun updateCustomThumbnail(videoId: Long, thumbnailPath: String?) =
        videoDao.updateCustomThumbnail(videoId, thumbnailPath)

    suspend fun updateThumbnail(videoId: Long, thumbnailPath: String?) =
        videoDao.updateThumbnail(videoId, thumbnailPath)

    // Delete operations
    suspend fun deleteVideo(video: Video) = videoDao.delete(video)

    suspend fun deleteVideoById(videoId: Long) = videoDao.deleteById(videoId)

    suspend fun deleteVideoByPath(filePath: String) = videoDao.deleteByPath(filePath)

    suspend fun deleteAllVideos() = videoDao.deleteAll()

    // Tag operations for videos
    suspend fun addTagToVideo(videoId: Long, tagId: Long) {
        tagDao.insertVideoTagCrossRef(VideoTagCrossRef(videoId, tagId))
    }

    suspend fun removeTagFromVideo(videoId: Long, tagId: Long) {
        tagDao.removeTagFromVideo(videoId, tagId)
    }

    suspend fun removeAllTagsFromVideo(videoId: Long) {
        tagDao.removeAllTagsFromVideo(videoId)
    }

    suspend fun getTagsForVideo(videoId: Long) = tagDao.getTagsForVideo(videoId)

    fun getTagsForVideoFlow(videoId: Long) = tagDao.getTagsForVideoFlow(videoId)
}
