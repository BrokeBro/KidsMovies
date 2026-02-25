package com.kidsmovies.app.artwork

import android.util.Log
import com.kidsmovies.app.data.database.entities.CollectionType
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
 * Manages automatic creation of franchise collections by looking up movies on TMDB
 * and grouping videos that belong to the same movie franchise.
 *
 * For example, if the user has "Toy Story", "Toy Story 2", "Toy Story 3", and "Toy Story 4",
 * this manager will detect they all belong to the TMDB "Toy Story Collection" and
 * auto-create a franchise collection grouping them together.
 */
class FranchiseCollectionManager(
    private val tmdbService: TmdbService,
    private val tmdbArtworkManager: TmdbArtworkManager,
    private val videoRepository: VideoRepository,
    private val collectionRepository: CollectionRepository,
    private val scope: CoroutineScope
) {
    private val mutex = Mutex()
    private var isRunning = false

    companion object {
        private const val TAG = "FranchiseManager"
    }

    /**
     * Scan all videos without a TMDB movie ID and try to match them to TMDB movies.
     * For any movies that belong to a TMDB collection (franchise), create or update
     * the franchise collection and add the video to it.
     */
    fun detectAndCreateFranchises() {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                if (isRunning) return@launch
                isRunning = true
            }

            try {
                Log.d(TAG, "Starting franchise detection...")
                processUnmatchedVideos()
                groupVideosIntoFranchises()
                Log.d(TAG, "Franchise detection complete")
            } catch (e: Exception) {
                Log.e(TAG, "Franchise detection failed", e)
            } finally {
                mutex.withLock {
                    isRunning = false
                }
            }
        }
    }

    /**
     * Process videos that haven't been matched to a TMDB movie yet.
     * Searches TMDB for each video title, stores the TMDB movie ID,
     * and identifies franchise membership.
     */
    private suspend fun processUnmatchedVideos() {
        val unmatchedVideos = videoRepository.getVideosWithoutTmdbMovieId()
        Log.d(TAG, "Found ${unmatchedVideos.size} videos without TMDB movie ID")

        for (video in unmatchedVideos) {
            // Skip videos that have episode info (these are TV episodes, not movies)
            if (video.hasEpisodeInfo()) continue

            try {
                val cleanTitle = cleanMovieTitle(video.title)
                val results = tmdbService.searchMovie(cleanTitle)
                val bestMatch = findBestMatch(cleanTitle, results)

                if (bestMatch != null) {
                    videoRepository.updateTmdbMovieId(video.id, bestMatch.id)
                    Log.d(TAG, "Matched '${video.title}' -> TMDB movie '${bestMatch.title}' (${bestMatch.id})")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error matching video: ${video.title}", e)
            }
        }
    }

    /**
     * Group matched videos into franchise collections based on TMDB collection data.
     */
    private suspend fun groupVideosIntoFranchises() {
        val matchedVideos = videoRepository.getVideosWithTmdbMovieId()
        Log.d(TAG, "Processing ${matchedVideos.size} matched videos for franchise grouping")

        // Track which TMDB collection IDs we've already processed
        val processedTmdbCollections = mutableSetOf<Int>()

        for (video in matchedVideos) {
            val tmdbMovieId = video.tmdbMovieId ?: continue

            try {
                // Get full movie details to check franchise membership
                val movieDetails = tmdbService.getMovieDetails(tmdbMovieId) ?: continue
                val franchise = movieDetails.belongsToCollection ?: continue

                // Skip if we've already processed this franchise
                if (franchise.id in processedTmdbCollections) {
                    // Still need to ensure video is in the collection
                    ensureVideoInFranchise(video, franchise.id)
                    continue
                }
                processedTmdbCollections.add(franchise.id)

                // Get or create the franchise collection
                val franchiseCollection = getOrCreateFranchiseCollection(franchise)

                // Get all movies in this franchise from TMDB
                val collectionDetails = tmdbService.getCollectionDetails(franchise.id)
                val franchiseMovieIds = collectionDetails?.parts?.map { it.id }?.toSet() ?: setOf(tmdbMovieId)

                // Find all local videos that belong to this franchise
                val allMatched = matchedVideos.filter { it.tmdbMovieId in franchiseMovieIds }

                for (matchedVideo in allMatched) {
                    if (!collectionRepository.isVideoInCollection(matchedVideo.id, franchiseCollection.id)) {
                        collectionRepository.addVideoToCollection(franchiseCollection.id, matchedVideo.id)
                        Log.d(TAG, "Added '${matchedVideo.title}' to franchise '${franchiseCollection.name}'")
                    }
                }

                Log.d(TAG, "Franchise '${franchise.name}': ${allMatched.size} local videos of ${franchiseMovieIds.size} total movies")
            } catch (e: Exception) {
                Log.e(TAG, "Error processing franchise for video: ${video.title}", e)
            }
        }
    }

    /**
     * Ensure a video is added to its franchise collection (for already-processed franchises).
     */
    private suspend fun ensureVideoInFranchise(video: Video, tmdbCollectionId: Int) {
        val franchiseCollection = collectionRepository.getCollectionByTmdbCollectionId(tmdbCollectionId) ?: return
        if (!collectionRepository.isVideoInCollection(video.id, franchiseCollection.id)) {
            collectionRepository.addVideoToCollection(franchiseCollection.id, video.id)
            Log.d(TAG, "Added '${video.title}' to existing franchise '${franchiseCollection.name}'")
        }
    }

    /**
     * Get or create a franchise collection for a TMDB collection.
     */
    private suspend fun getOrCreateFranchiseCollection(
        franchise: TmdbService.BelongsToCollection
    ): VideoCollection {
        // Check if franchise collection already exists by TMDB ID
        val existing = collectionRepository.getCollectionByTmdbCollectionId(franchise.id)
        if (existing != null) return existing

        // Check if a collection with the same name exists
        val byName = collectionRepository.getCollectionByName(franchise.name)
        if (byName != null) {
            // Update it with the TMDB collection ID and franchise type
            collectionRepository.updateTmdbCollectionId(byName.id, franchise.id)
            if (!byName.isFranchise()) {
                collectionRepository.updateCollectionType(
                    byName.id,
                    CollectionType.FRANCHISE.name,
                    null,
                    null
                )
            }
            return byName.copy(tmdbCollectionId = franchise.id, collectionType = CollectionType.FRANCHISE.name)
        }

        // Create new franchise collection
        val newCollection = VideoCollection(
            name = franchise.name,
            collectionType = CollectionType.FRANCHISE.name,
            tmdbCollectionId = franchise.id
        )
        val id = collectionRepository.insertCollection(newCollection)
        val created = newCollection.copy(id = id)

        // Fetch artwork for the franchise collection
        val artworkUrl = tmdbService.getImageUrl(franchise.posterPath, TmdbService.POSTER_SIZE_LARGE)
        if (artworkUrl != null) {
            val artworkResult = tmdbArtworkManager.getCollectionArtwork(franchise.name)
            if (artworkResult.localPath != null) {
                collectionRepository.updateTmdbArtwork(id, artworkResult.localPath)
            }
        }

        Log.d(TAG, "Created franchise collection: '${franchise.name}' (TMDB ID: ${franchise.id})")
        return created
    }

    /**
     * Find the best matching TMDB movie for a given title.
     * Uses title similarity to pick the closest match.
     */
    private fun findBestMatch(
        cleanTitle: String,
        results: List<TmdbService.MovieResult>
    ): TmdbService.MovieResult? {
        if (results.isEmpty()) return null

        val normalizedTitle = cleanTitle.lowercase().trim()

        // First pass: exact title match
        val exactMatch = results.find {
            it.title.lowercase().trim() == normalizedTitle
        }
        if (exactMatch != null) return exactMatch

        // Second pass: title starts with or contains the search query
        val containsMatch = results.find {
            it.title.lowercase().trim().contains(normalizedTitle) ||
                normalizedTitle.contains(it.title.lowercase().trim())
        }
        if (containsMatch != null) return containsMatch

        // Fall back to first result if it's a reasonable match
        val firstResult = results.first()
        val similarity = calculateSimilarity(normalizedTitle, firstResult.title.lowercase().trim())
        return if (similarity > 0.5) firstResult else null
    }

    /**
     * Calculate string similarity using Jaccard index on word sets.
     */
    private fun calculateSimilarity(a: String, b: String): Double {
        val wordsA = a.split(Regex("\\s+")).toSet()
        val wordsB = b.split(Regex("\\s+")).toSet()
        val intersection = wordsA.intersect(wordsB).size
        val union = wordsA.union(wordsB).size
        return if (union == 0) 0.0 else intersection.toDouble() / union.toDouble()
    }

    /**
     * Clean a video title for TMDB search.
     * Removes file extensions, quality tags, year tags, etc.
     */
    private fun cleanMovieTitle(title: String): String {
        var cleaned = title

        // Remove file extension
        cleaned = cleaned.replace(Regex("""\.[a-zA-Z0-9]{2,4}$"""), "")

        // Replace separators with spaces
        cleaned = cleaned.replace(Regex("""[-._]+"""), " ")

        // Remove quality/format words
        val stripWords = listOf(
            "1080p", "720p", "480p", "2160p", "4k", "uhd", "hdr",
            "bluray", "blu-ray", "bdrip", "brrip", "dvdrip", "webrip", "web-dl",
            "hdtv", "x264", "x265", "hevc", "aac", "ac3", "dts",
            "extended", "unrated", "directors cut", "remastered"
        )
        for (word in stripWords) {
            cleaned = cleaned.replace(Regex("""\b$word\b""", RegexOption.IGNORE_CASE), "")
        }

        // Remove year in parentheses or brackets
        cleaned = cleaned.replace(Regex("""\s*[\[(]\d{4}[\])]"""), "")

        // Remove standalone year at end
        cleaned = cleaned.replace(Regex("""\s+\d{4}\s*$"""), "")

        // Clean up extra spaces
        cleaned = cleaned.replace(Regex("""\s+"""), " ").trim()

        return cleaned
    }
}
