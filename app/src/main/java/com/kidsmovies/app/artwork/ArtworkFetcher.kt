package com.kidsmovies.app.artwork

import android.util.Log
import com.kidsmovies.app.data.database.entities.Video
import com.kidsmovies.app.data.database.entities.VideoCollection
import com.kidsmovies.app.data.repository.CollectionRepository
import com.kidsmovies.app.data.repository.VideoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Background service that fetches TMDB artwork for videos and collections
 * that don't have artwork yet.
 */
class ArtworkFetcher(
    private val tmdbArtworkManager: TmdbArtworkManager,
    private val videoRepository: VideoRepository,
    private val collectionRepository: CollectionRepository,
    private val scope: CoroutineScope
) {
    private val mutex = Mutex()
    private val pendingVideos = mutableSetOf<Long>()
    private val pendingCollections = mutableSetOf<Long>()

    companion object {
        private const val TAG = "ArtworkFetcher"
    }

    /**
     * Fetch artwork for a video if it doesn't have TMDB artwork yet
     */
    fun fetchForVideo(video: Video, collectionName: String? = null) {
        // Skip if already has TMDB artwork or custom thumbnail
        if (video.tmdbArtworkPath != null || video.customThumbnailPath != null) return

        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                if (video.id in pendingVideos) return@launch
                pendingVideos.add(video.id)
            }

            try {
                Log.d(TAG, "Fetching artwork for video: ${video.title}")
                val result = tmdbArtworkManager.getVideoArtwork(video.title, collectionName)

                if (result.localPath != null) {
                    videoRepository.updateTmdbArtwork(video.id, result.localPath)
                    Log.d(TAG, "Updated video artwork: ${video.title} -> ${result.localPath}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch artwork for video: ${video.title}", e)
            } finally {
                mutex.withLock {
                    pendingVideos.remove(video.id)
                }
            }
        }
    }

    /**
     * Fetch artwork for a collection if it doesn't have TMDB artwork yet
     */
    fun fetchForCollection(collection: VideoCollection, parentCollectionName: String? = null) {
        // Skip if already has thumbnail
        if (collection.thumbnailPath != null || collection.tmdbArtworkPath != null) return

        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                if (collection.id in pendingCollections) return@launch
                pendingCollections.add(collection.id)
            }

            try {
                Log.d(TAG, "Fetching artwork for collection: ${collection.name}")
                val result = tmdbArtworkManager.getCollectionArtwork(collection.name, parentCollectionName)

                if (result.localPath != null) {
                    collectionRepository.updateTmdbArtwork(collection.id, result.localPath)
                    Log.d(TAG, "Updated collection artwork: ${collection.name} -> ${result.localPath}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch artwork for collection: ${collection.name}", e)
            } finally {
                mutex.withLock {
                    pendingCollections.remove(collection.id)
                }
            }
        }
    }

    /**
     * Fetch artwork for multiple videos
     */
    fun fetchForVideos(videos: List<Video>, collectionName: String? = null) {
        videos.forEach { video ->
            fetchForVideo(video, collectionName)
        }
    }

    /**
     * Fetch artwork for multiple collections
     */
    fun fetchForCollections(collections: List<VideoCollection>) {
        collections.forEach { collection ->
            fetchForCollection(collection)
        }
    }

    /**
     * Fetch artwork for all videos and collections that need it
     */
    fun fetchAllMissing() {
        scope.launch(Dispatchers.IO) {
            try {
                // Fetch for videos without TMDB artwork
                val videos = videoRepository.getAllVideos()
                val videosNeedingArtwork = videos.filter {
                    it.tmdbArtworkPath == null && it.customThumbnailPath == null
                }
                Log.d(TAG, "Found ${videosNeedingArtwork.size} videos needing artwork")
                fetchForVideos(videosNeedingArtwork)

                // Fetch for collections without artwork
                val collections = collectionRepository.getAllCollections()
                val collectionsNeedingArtwork = collections.filter {
                    it.thumbnailPath == null && it.tmdbArtworkPath == null
                }
                Log.d(TAG, "Found ${collectionsNeedingArtwork.size} collections needing artwork")
                fetchForCollections(collectionsNeedingArtwork)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch missing artwork", e)
            }
        }
    }
}
