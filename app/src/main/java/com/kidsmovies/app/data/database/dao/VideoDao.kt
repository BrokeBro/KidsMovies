package com.kidsmovies.app.data.database.dao

import androidx.room.*
import com.kidsmovies.app.data.database.entities.Video
import com.kidsmovies.app.data.database.entities.VideoWithTags
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoDao {

    // Insert operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(video: Video): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(videos: List<Video>): List<Long>

    // Update operations
    @Update
    suspend fun update(video: Video)

    @Query("UPDATE videos SET isFavourite = :isFavourite WHERE id = :videoId")
    suspend fun updateFavourite(videoId: Long, isFavourite: Boolean)

    @Query("UPDATE videos SET isEnabled = :isEnabled WHERE id = :videoId")
    suspend fun updateEnabled(videoId: Long, isEnabled: Boolean)

    @Query("UPDATE videos SET isHidden = :isHidden WHERE id = :videoId")
    suspend fun updateHidden(videoId: Long, isHidden: Boolean)

    @Query("UPDATE videos SET lastPlayed = :lastPlayed, playCount = playCount + 1 WHERE id = :videoId")
    suspend fun updatePlayStats(videoId: Long, lastPlayed: Long)

    @Query("UPDATE videos SET playbackPosition = :position WHERE id = :videoId")
    suspend fun updatePlaybackPosition(videoId: Long, position: Long)

    @Query("UPDATE videos SET customThumbnailPath = :thumbnailPath WHERE id = :videoId")
    suspend fun updateCustomThumbnail(videoId: Long, thumbnailPath: String?)

    @Query("UPDATE videos SET thumbnailPath = :thumbnailPath WHERE id = :videoId")
    suspend fun updateThumbnail(videoId: Long, thumbnailPath: String?)

    @Query("UPDATE videos SET tmdbArtworkPath = :artworkPath WHERE id = :videoId")
    suspend fun updateTmdbArtwork(videoId: Long, artworkPath: String?)

    @Query("UPDATE videos SET seasonNumber = :seasonNumber, episodeNumber = :episodeNumber WHERE id = :videoId")
    suspend fun updateEpisodeInfo(videoId: Long, seasonNumber: Int?, episodeNumber: Int?)

    @Query("UPDATE videos SET tmdbEpisodeId = :tmdbEpisodeId WHERE id = :videoId")
    suspend fun updateTmdbEpisodeId(videoId: Long, tmdbEpisodeId: Int?)

    @Query("UPDATE videos SET collectionId = :collectionId WHERE id = :videoId")
    suspend fun updateCollection(videoId: Long, collectionId: Long?)

    @Query("UPDATE videos SET collectionId = :collectionId WHERE id IN (:videoIds)")
    suspend fun updateCollectionForVideos(videoIds: List<Long>, collectionId: Long?)

    // Delete operations
    @Delete
    suspend fun delete(video: Video)

    @Query("DELETE FROM videos WHERE id = :videoId")
    suspend fun deleteById(videoId: Long)

    @Query("DELETE FROM videos WHERE id IN (:videoIds)")
    suspend fun deleteByIds(videoIds: List<Long>)

    @Query("DELETE FROM videos WHERE filePath = :filePath")
    suspend fun deleteByPath(filePath: String)

    @Query("DELETE FROM videos WHERE folderPath = :folderPath")
    suspend fun deleteByFolderPath(folderPath: String)

    @Query("DELETE FROM videos WHERE folderPath LIKE :folderPathPrefix || '%'")
    suspend fun deleteByFolderPathPrefix(folderPathPrefix: String)

    @Query("DELETE FROM videos")
    suspend fun deleteAll()

    // Query operations - All videos (excludes hidden, includes locked so they show with lock icon)
    @Query("SELECT * FROM videos WHERE isHidden = 0 ORDER BY title ASC")
    fun getAllVideosFlow(): Flow<List<Video>>

    @Query("SELECT * FROM videos ORDER BY title ASC")
    fun getAllVideosIncludingHiddenFlow(): Flow<List<Video>>

    @Query("SELECT * FROM videos ORDER BY title ASC")
    suspend fun getAllVideos(): List<Video>

    @Query("SELECT * FROM videos WHERE isHidden = 0 ORDER BY title ASC")
    suspend fun getVisibleVideos(): List<Video>

    // Query for enabled-only (used for specific cases)
    @Query("SELECT * FROM videos WHERE isEnabled = 1 ORDER BY title ASC")
    suspend fun getEnabledVideos(): List<Video>

    // Query operations - Favourites (excludes hidden, shows locked with lock icon)
    @Query("SELECT * FROM videos WHERE isFavourite = 1 AND isHidden = 0 ORDER BY title ASC")
    fun getFavouritesFlow(): Flow<List<Video>>

    @Query("SELECT * FROM videos WHERE isFavourite = 1 AND isHidden = 0 ORDER BY title ASC")
    suspend fun getFavourites(): List<Video>

    // Query operations - Recently played (excludes hidden, shows locked with lock icon)
    @Query("SELECT * FROM videos WHERE lastPlayed IS NOT NULL AND isHidden = 0 ORDER BY lastPlayed DESC LIMIT :limit")
    fun getRecentlyPlayedFlow(limit: Int = 10): Flow<List<Video>>

    @Query("SELECT * FROM videos WHERE lastPlayed IS NOT NULL AND isHidden = 0 ORDER BY lastPlayed DESC LIMIT :limit")
    suspend fun getRecentlyPlayed(limit: Int = 10): List<Video>

    // Query operations - By folder (excludes hidden, shows locked with lock icon)
    @Query("SELECT * FROM videos WHERE folderPath = :folderPath AND isHidden = 0 ORDER BY title ASC")
    fun getVideosByFolderFlow(folderPath: String): Flow<List<Video>>

    // Query operations - By collection (excludes hidden, shows locked with lock icon)
    @Query("SELECT * FROM videos WHERE collectionId = :collectionId AND isHidden = 0 ORDER BY title ASC")
    fun getVideosByCollectionFlow(collectionId: Long): Flow<List<Video>>

    @Query("SELECT * FROM videos WHERE collectionId = :collectionId AND isHidden = 0 ORDER BY title ASC")
    suspend fun getVideosByCollection(collectionId: Long): List<Video>

    @Query("SELECT * FROM videos WHERE collectionId IS NULL AND isHidden = 0 ORDER BY title ASC")
    fun getUncollectedVideosFlow(): Flow<List<Video>>

    // Query operations - Search (excludes hidden, shows locked with lock icon)
    @Query("SELECT * FROM videos WHERE title LIKE '%' || :query || '%' AND isHidden = 0 ORDER BY title ASC")
    fun searchVideosFlow(query: String): Flow<List<Video>>

    @Query("SELECT * FROM videos WHERE title LIKE '%' || :query || '%' AND isHidden = 0 ORDER BY title ASC")
    suspend fun searchVideos(query: String): List<Video>

    // Query operations - Single video
    @Query("SELECT * FROM videos WHERE id = :videoId")
    suspend fun getVideoById(videoId: Long): Video?

    @Query("SELECT * FROM videos WHERE id = :videoId")
    fun getVideoByIdFlow(videoId: Long): Flow<Video?>

    @Query("SELECT * FROM videos WHERE filePath = :filePath")
    suspend fun getVideoByPath(filePath: String): Video?

    @Query("SELECT * FROM videos WHERE title = :title LIMIT 1")
    suspend fun getVideoByTitle(title: String): Video?

    // Query operations - With tags (excludes hidden, includes locked videos)
    @Transaction
    @Query("SELECT * FROM videos WHERE isHidden = 0 ORDER BY title ASC")
    fun getAllVideosWithTagsFlow(): Flow<List<VideoWithTags>>

    @Transaction
    @Query("SELECT * FROM videos WHERE id = :videoId")
    suspend fun getVideoWithTags(videoId: Long): VideoWithTags?

    // Count operations
    @Query("SELECT COUNT(*) FROM videos")
    suspend fun getVideoCount(): Int

    @Query("SELECT COUNT(*) FROM videos WHERE isFavourite = 1")
    suspend fun getFavouriteCount(): Int

    @Query("SELECT COUNT(*) FROM videos WHERE collectionId = :collectionId")
    suspend fun getVideoCountInCollection(collectionId: Long): Int

    // Sorting queries (excludes hidden, includes locked videos)
    @Query("SELECT * FROM videos WHERE isHidden = 0 ORDER BY title ASC")
    fun getVideosSortedByTitleAsc(): Flow<List<Video>>

    @Query("SELECT * FROM videos WHERE isHidden = 0 ORDER BY title DESC")
    fun getVideosSortedByTitleDesc(): Flow<List<Video>>

    @Query("SELECT * FROM videos WHERE isHidden = 0 ORDER BY dateAdded ASC")
    fun getVideosSortedByDateAsc(): Flow<List<Video>>

    @Query("SELECT * FROM videos WHERE isHidden = 0 ORDER BY dateAdded DESC")
    fun getVideosSortedByDateDesc(): Flow<List<Video>>

    @Query("SELECT * FROM videos WHERE isHidden = 0 ORDER BY CASE WHEN lastPlayed IS NULL THEN 1 ELSE 0 END, lastPlayed DESC")
    fun getVideosSortedByRecent(): Flow<List<Video>>

    // Check if video exists
    @Query("SELECT EXISTS(SELECT 1 FROM videos WHERE filePath = :filePath)")
    suspend fun videoExists(filePath: String): Boolean

    // Get all file paths (for scanning comparison)
    @Query("SELECT filePath FROM videos")
    suspend fun getAllFilePaths(): List<String>

    // OneDrive/SharePoint remote video queries
    @Query("SELECT * FROM videos WHERE source_type = :sourceType AND isHidden = 0 ORDER BY title ASC")
    fun getVideosBySourceFlow(sourceType: String): Flow<List<Video>>

    @Query("SELECT * FROM videos WHERE source_type = :sourceType ORDER BY title ASC")
    suspend fun getVideosBySource(sourceType: String): List<Video>

    @Query("SELECT * FROM videos WHERE remote_id = :remoteId LIMIT 1")
    suspend fun getVideoByRemoteId(remoteId: String): Video?

    @Query("UPDATE videos SET remote_url = :url, remote_url_expiry = :expiry WHERE remote_id = :remoteId")
    suspend fun updateRemoteUrl(remoteId: String, url: String, expiry: Long)

    @Query("DELETE FROM videos WHERE source_type = :sourceType")
    suspend fun deleteBySourceType(sourceType: String)

    @Query("SELECT remote_id FROM videos WHERE source_type = 'onedrive'")
    suspend fun getAllRemoteIds(): List<String>
}
