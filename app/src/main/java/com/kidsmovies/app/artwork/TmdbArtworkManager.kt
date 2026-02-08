package com.kidsmovies.app.artwork

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Manages automatic artwork fetching from TMDB.
 * Handles name matching, season detection, and local caching.
 */
class TmdbArtworkManager(
    private val context: Context,
    private val tmdbService: TmdbService
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val artworkDir: File by lazy {
        File(context.filesDir, "tmdb_artwork").also { it.mkdirs() }
    }

    companion object {
        private const val TAG = "TmdbArtworkManager"

        // Patterns to detect TV show episodes
        private val EPISODE_PATTERNS = listOf(
            Regex("""[Ss](\d{1,2})[Ee](\d{1,2})"""),  // S01E01
            Regex("""(\d{1,2})x(\d{1,2})"""),          // 1x01
            Regex("""[Ss]eason\s*(\d{1,2}).*[Ee]pisode\s*(\d{1,2})""", RegexOption.IGNORE_CASE),
            Regex("""[Ee]pisode\s*(\d{1,3})""", RegexOption.IGNORE_CASE)  // Episode 01 (no season)
        )

        // Patterns to detect season folders/collections
        private val SEASON_PATTERNS = listOf(
            Regex("""[Ss]eason\s*(\d{1,2})""", RegexOption.IGNORE_CASE),
            Regex("""[Ss](\d{1,2})(?![Ee])"""),  // S01 but not S01E
            Regex("""Series\s*(\d{1,2})""", RegexOption.IGNORE_CASE)
        )

        // Words to strip when cleaning titles
        private val STRIP_WORDS = listOf(
            "1080p", "720p", "480p", "2160p", "4k", "uhd", "hdr",
            "bluray", "blu-ray", "bdrip", "brrip", "dvdrip", "webrip", "web-dl",
            "hdtv", "x264", "x265", "hevc", "aac", "ac3", "dts",
            "extended", "unrated", "directors cut", "remastered"
        )
    }

    /**
     * Result of artwork lookup
     */
    data class ArtworkResult(
        val localPath: String?,
        val tmdbId: Int?,
        val type: ContentType,
        val title: String?
    )

    enum class ContentType {
        MOVIE, TV_SHOW, TV_SEASON, TV_EPISODE, UNKNOWN
    }

    /**
     * Get artwork for a video title.
     * Returns cached path if exists, otherwise fetches from TMDB.
     */
    suspend fun getVideoArtwork(
        videoTitle: String,
        collectionName: String? = null
    ): ArtworkResult = withContext(Dispatchers.IO) {
        val cleanTitle = cleanTitle(videoTitle)
        val cacheKey = generateCacheKey("video", cleanTitle)

        // Check cache first
        val cachedPath = getCachedArtwork(cacheKey)
        if (cachedPath != null) {
            return@withContext ArtworkResult(cachedPath, null, ContentType.UNKNOWN, cleanTitle)
        }

        // Detect if this is a TV episode
        val episodeInfo = detectEpisode(videoTitle)
        if (episodeInfo != null) {
            val showName = episodeInfo.showName.ifEmpty { collectionName ?: "" }
            return@withContext fetchTvEpisodeArtwork(showName, episodeInfo.season, episodeInfo.episode, cacheKey)
        }

        // Try as a movie first
        val movieResult = fetchMovieArtwork(cleanTitle, cacheKey)
        if (movieResult.localPath != null) {
            return@withContext movieResult
        }

        // Try as a TV show (for videos that might be show names without episode info)
        val tvResult = fetchTvShowArtwork(cleanTitle, cacheKey)
        if (tvResult.localPath != null) {
            return@withContext tvResult
        }

        ArtworkResult(null, null, ContentType.UNKNOWN, cleanTitle)
    }

    /**
     * Get artwork for a collection (may be a TV show with seasons)
     */
    suspend fun getCollectionArtwork(
        collectionName: String,
        parentCollectionName: String? = null
    ): ArtworkResult = withContext(Dispatchers.IO) {
        val cleanName = cleanTitle(collectionName)
        val cacheKey = generateCacheKey("collection", cleanName)

        // Check cache first
        val cachedPath = getCachedArtwork(cacheKey)
        if (cachedPath != null) {
            return@withContext ArtworkResult(cachedPath, null, ContentType.UNKNOWN, cleanName)
        }

        // Detect if this is a season folder
        val seasonNumber = detectSeasonNumber(collectionName)
        if (seasonNumber != null && parentCollectionName != null) {
            // This is a season folder, fetch season artwork
            val showName = cleanTitle(parentCollectionName)
            return@withContext fetchTvSeasonArtwork(showName, seasonNumber, cacheKey)
        }

        // Try as a TV show first (collections are often TV shows)
        val tvResult = fetchTvShowArtwork(cleanName, cacheKey)
        if (tvResult.localPath != null) {
            return@withContext tvResult
        }

        // Try as a movie
        val movieResult = fetchMovieArtwork(cleanName, cacheKey)
        if (movieResult.localPath != null) {
            return@withContext movieResult
        }

        ArtworkResult(null, null, ContentType.UNKNOWN, cleanName)
    }

    /**
     * Detect season number from a collection name
     */
    fun detectSeasonNumber(name: String): Int? {
        for (pattern in SEASON_PATTERNS) {
            val match = pattern.find(name)
            if (match != null) {
                return match.groupValues[1].toIntOrNull()
            }
        }
        return null
    }

    /**
     * Check if a collection is likely a TV show (has season sub-collections)
     */
    fun isLikelyTvShow(collectionName: String, subCollectionNames: List<String>): Boolean {
        // If any sub-collection looks like a season, this is likely a TV show
        return subCollectionNames.any { detectSeasonNumber(it) != null }
    }

    private data class EpisodeInfo(
        val showName: String,
        val season: Int?,
        val episode: Int
    )

    private fun detectEpisode(title: String): EpisodeInfo? {
        for (pattern in EPISODE_PATTERNS) {
            val match = pattern.find(title)
            if (match != null) {
                val showName = title.substring(0, match.range.first).trim()
                    .replace(Regex("""[-._]"""), " ")
                    .trim()

                return when (match.groupValues.size) {
                    3 -> EpisodeInfo(
                        showName = showName,
                        season = match.groupValues[1].toIntOrNull(),
                        episode = match.groupValues[2].toIntOrNull() ?: 1
                    )
                    2 -> EpisodeInfo(
                        showName = showName,
                        season = null,
                        episode = match.groupValues[1].toIntOrNull() ?: 1
                    )
                    else -> null
                }
            }
        }
        return null
    }

    private suspend fun fetchMovieArtwork(title: String, cacheKey: String): ArtworkResult {
        val results = tmdbService.searchMovie(title)
        val movie = results.firstOrNull() ?: return ArtworkResult(null, null, ContentType.MOVIE, title)

        val imageUrl = tmdbService.getImageUrl(movie.posterPath, TmdbService.POSTER_SIZE_LARGE)
            ?: return ArtworkResult(null, movie.id, ContentType.MOVIE, movie.title)

        val localPath = downloadAndCache(imageUrl, cacheKey)
        return ArtworkResult(localPath, movie.id, ContentType.MOVIE, movie.title)
    }

    private suspend fun fetchTvShowArtwork(title: String, cacheKey: String): ArtworkResult {
        val results = tmdbService.searchTv(title)
        val show = results.firstOrNull() ?: return ArtworkResult(null, null, ContentType.TV_SHOW, title)

        val imageUrl = tmdbService.getImageUrl(show.posterPath, TmdbService.POSTER_SIZE_LARGE)
            ?: return ArtworkResult(null, show.id, ContentType.TV_SHOW, show.name)

        val localPath = downloadAndCache(imageUrl, cacheKey)
        return ArtworkResult(localPath, show.id, ContentType.TV_SHOW, show.name)
    }

    private suspend fun fetchTvSeasonArtwork(
        showName: String,
        seasonNumber: Int,
        cacheKey: String
    ): ArtworkResult {
        // First find the show
        val shows = tmdbService.searchTv(showName)
        val show = shows.firstOrNull() ?: return ArtworkResult(null, null, ContentType.TV_SEASON, showName)

        // Get show details with seasons
        val details = tmdbService.getTvDetails(show.id)
            ?: return ArtworkResult(null, show.id, ContentType.TV_SEASON, show.name)

        // Find the matching season
        val season = details.seasons?.find { it.seasonNumber == seasonNumber }

        // Use season poster if available, otherwise use show poster
        val posterPath = season?.posterPath ?: details.posterPath
        val imageUrl = tmdbService.getImageUrl(posterPath, TmdbService.POSTER_SIZE_LARGE)
            ?: return ArtworkResult(null, show.id, ContentType.TV_SEASON, season?.name ?: show.name)

        val localPath = downloadAndCache(imageUrl, cacheKey)
        return ArtworkResult(localPath, show.id, ContentType.TV_SEASON, season?.name ?: show.name)
    }

    private suspend fun fetchTvEpisodeArtwork(
        showName: String,
        seasonNumber: Int?,
        episodeNumber: Int,
        cacheKey: String
    ): ArtworkResult {
        if (showName.isEmpty()) {
            return ArtworkResult(null, null, ContentType.TV_EPISODE, null)
        }

        // First find the show
        val shows = tmdbService.searchTv(showName)
        val show = shows.firstOrNull()
            ?: return ArtworkResult(null, null, ContentType.TV_EPISODE, showName)

        // If no season specified, use season 1
        val season = seasonNumber ?: 1

        // Get season details with episodes
        val seasonDetails = tmdbService.getSeasonDetails(show.id, season)

        // Find the episode
        val episode = seasonDetails?.episodes?.find { it.episodeNumber == episodeNumber }

        // Use episode still if available, otherwise season/show poster
        val imagePath = episode?.stillPath
            ?: seasonDetails?.posterPath
            ?: show.posterPath

        val imageUrl = tmdbService.getImageUrl(
            imagePath,
            if (episode?.stillPath != null) TmdbService.BACKDROP_SIZE else TmdbService.POSTER_SIZE_LARGE
        ) ?: return ArtworkResult(null, show.id, ContentType.TV_EPISODE, episode?.name ?: show.name)

        val localPath = downloadAndCache(imageUrl, cacheKey)
        return ArtworkResult(localPath, show.id, ContentType.TV_EPISODE, episode?.name ?: show.name)
    }

    private fun cleanTitle(title: String): String {
        var cleaned = title

        // Remove file extension
        cleaned = cleaned.replace(Regex("""\.[a-zA-Z0-9]{2,4}$"""), "")

        // Remove episode patterns (to get show name)
        for (pattern in EPISODE_PATTERNS) {
            cleaned = pattern.replace(cleaned, "")
        }

        // Replace separators with spaces
        cleaned = cleaned.replace(Regex("""[-._]"""), " ")

        // Remove quality/format words
        for (word in STRIP_WORDS) {
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

    private fun generateCacheKey(type: String, name: String): String {
        val normalized = name.lowercase()
            .replace(Regex("""[^a-z0-9]"""), "_")
            .replace(Regex("""_+"""), "_")
            .take(50)
        return "${type}_$normalized"
    }

    private fun getCachedArtwork(cacheKey: String): String? {
        val file = File(artworkDir, "$cacheKey.jpg")
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }

    private suspend fun downloadAndCache(url: String, cacheKey: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val file = File(artworkDir, "$cacheKey.jpg")
                    response.body?.byteStream()?.use { input ->
                        FileOutputStream(file).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(TAG, "Cached artwork: $cacheKey")
                    file.absolutePath
                } else {
                    Log.e(TAG, "Download failed: ${response.code}")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download error", e)
                null
            }
        }

    /**
     * Clear all cached artwork
     */
    fun clearCache() {
        artworkDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Get cache size in bytes
     */
    fun getCacheSize(): Long {
        return artworkDir.listFiles()?.sumOf { it.length() } ?: 0
    }
}
