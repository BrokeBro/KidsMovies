package com.kidsmovies.app.artwork

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Service for interacting with The Movie Database (TMDB) API.
 * TMDB provides movie and TV show metadata including artwork.
 */
class TmdbService(private val apiKey: String = DEFAULT_API_KEY) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    companion object {
        private const val TAG = "TmdbService"
        private const val BASE_URL = "https://api.themoviedb.org/3"
        const val IMAGE_BASE_URL = "https://image.tmdb.org/t/p"

        // Default API key (free tier)
        private const val DEFAULT_API_KEY = "55bd6f8e28b603cf918a41cf4679a782"

        // Image sizes available from TMDB
        const val POSTER_SIZE_SMALL = "/w185"
        const val POSTER_SIZE_MEDIUM = "/w342"
        const val POSTER_SIZE_LARGE = "/w500"
        const val POSTER_SIZE_ORIGINAL = "/original"
        const val BACKDROP_SIZE = "/w780"
    }

    // Response models
    data class SearchMovieResponse(
        val results: List<MovieResult>
    )

    data class MovieResult(
        val id: Int,
        val title: String,
        @SerializedName("original_title") val originalTitle: String?,
        @SerializedName("poster_path") val posterPath: String?,
        @SerializedName("backdrop_path") val backdropPath: String?,
        @SerializedName("release_date") val releaseDate: String?,
        val overview: String?
    )

    data class SearchTvResponse(
        val results: List<TvResult>
    )

    data class TvResult(
        val id: Int,
        val name: String,
        @SerializedName("original_name") val originalName: String?,
        @SerializedName("poster_path") val posterPath: String?,
        @SerializedName("backdrop_path") val backdropPath: String?,
        @SerializedName("first_air_date") val firstAirDate: String?,
        val overview: String?
    )

    data class TvDetailsResponse(
        val id: Int,
        val name: String,
        @SerializedName("poster_path") val posterPath: String?,
        @SerializedName("backdrop_path") val backdropPath: String?,
        val seasons: List<SeasonInfo>?
    )

    data class SeasonInfo(
        val id: Int,
        @SerializedName("season_number") val seasonNumber: Int,
        val name: String,
        @SerializedName("poster_path") val posterPath: String?,
        @SerializedName("episode_count") val episodeCount: Int?
    )

    data class SeasonDetailsResponse(
        val id: Int,
        @SerializedName("season_number") val seasonNumber: Int,
        val name: String,
        @SerializedName("poster_path") val posterPath: String?,
        val episodes: List<EpisodeInfo>?
    )

    data class EpisodeInfo(
        val id: Int,
        @SerializedName("episode_number") val episodeNumber: Int,
        val name: String,
        @SerializedName("still_path") val stillPath: String?,
        val overview: String?
    )

    /**
     * Search for movies by title
     */
    suspend fun searchMovie(query: String): List<MovieResult> = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty()) {
            Log.w(TAG, "TMDB API key not configured")
            return@withContext emptyList()
        }

        try {
            val url = "$BASE_URL/search/movie?api_key=$apiKey&query=${query.encodeUrl()}"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext emptyList()
                val searchResponse = gson.fromJson(body, SearchMovieResponse::class.java)
                searchResponse.results
            } else {
                Log.e(TAG, "Movie search failed: ${response.code}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Movie search error", e)
            emptyList()
        }
    }

    /**
     * Search for TV shows by title
     */
    suspend fun searchTv(query: String): List<TvResult> = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty()) {
            Log.w(TAG, "TMDB API key not configured")
            return@withContext emptyList()
        }

        try {
            val url = "$BASE_URL/search/tv?api_key=$apiKey&query=${query.encodeUrl()}"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext emptyList()
                val searchResponse = gson.fromJson(body, SearchTvResponse::class.java)
                searchResponse.results
            } else {
                Log.e(TAG, "TV search failed: ${response.code}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "TV search error", e)
            emptyList()
        }
    }

    /**
     * Get TV show details including seasons
     */
    suspend fun getTvDetails(tvId: Int): TvDetailsResponse? = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty()) return@withContext null

        try {
            val url = "$BASE_URL/tv/$tvId?api_key=$apiKey"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext null
                gson.fromJson(body, TvDetailsResponse::class.java)
            } else {
                Log.e(TAG, "TV details failed: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "TV details error", e)
            null
        }
    }

    /**
     * Get season details including episodes
     */
    suspend fun getSeasonDetails(tvId: Int, seasonNumber: Int): SeasonDetailsResponse? =
        withContext(Dispatchers.IO) {
            if (apiKey.isEmpty()) return@withContext null

            try {
                val url = "$BASE_URL/tv/$tvId/season/$seasonNumber?api_key=$apiKey"
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext null
                    gson.fromJson(body, SeasonDetailsResponse::class.java)
                } else {
                    Log.e(TAG, "Season details failed: ${response.code}")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Season details error", e)
                null
            }
        }

    /**
     * Build full image URL from TMDB path
     */
    fun getImageUrl(path: String?, size: String = POSTER_SIZE_MEDIUM): String? {
        return path?.let { "$IMAGE_BASE_URL$size$it" }
    }

    private fun String.encodeUrl(): String {
        return java.net.URLEncoder.encode(this, "UTF-8")
    }
}
