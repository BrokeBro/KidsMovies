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

    // Content rating models for artwork safety filtering
    data class ReleaseDatesResponse(
        val results: List<ReleaseDateCountry>
    )

    data class ReleaseDateCountry(
        @SerializedName("iso_3166_1") val country: String,
        @SerializedName("release_dates") val releaseDates: List<ReleaseDateEntry>
    )

    data class ReleaseDateEntry(
        val certification: String,
        val type: Int // 1=Premiere, 3=Theatrical, 4=Digital, 5=Physical, 6=TV
    )

    data class TvContentRatingsResponse(
        val results: List<TvContentRating>
    )

    data class TvContentRating(
        @SerializedName("iso_3166_1") val country: String,
        val rating: String
    )

    // Movie details with release dates appended
    data class MovieDetailsWithReleaseDatesResponse(
        val id: Int,
        val title: String,
        @SerializedName("poster_path") val posterPath: String?,
        @SerializedName("backdrop_path") val backdropPath: String?,
        @SerializedName("belongs_to_collection") val belongsToCollection: BelongsToCollection?,
        @SerializedName("release_date") val releaseDate: String?,
        val overview: String?,
        @SerializedName("release_dates") val releaseDates: ReleaseDatesResponse?
    )

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

    // Company/Studio search for collection artwork
    data class SearchCompanyResponse(
        val results: List<CompanyResult>
    )

    data class CompanyResult(
        val id: Int,
        val name: String,
        @SerializedName("logo_path") val logoPath: String?,
        @SerializedName("origin_country") val originCountry: String?
    )

    // TMDB Collection (movie series like "Toy Story Collection")
    data class SearchCollectionResponse(
        val results: List<CollectionResult>
    )

    data class CollectionResult(
        val id: Int,
        val name: String,
        @SerializedName("poster_path") val posterPath: String?,
        @SerializedName("backdrop_path") val backdropPath: String?
    )

    // Movie details response (includes belongs_to_collection for franchise detection)
    data class MovieDetailsResponse(
        val id: Int,
        val title: String,
        @SerializedName("poster_path") val posterPath: String?,
        @SerializedName("backdrop_path") val backdropPath: String?,
        @SerializedName("belongs_to_collection") val belongsToCollection: BelongsToCollection?,
        @SerializedName("release_date") val releaseDate: String?,
        val overview: String?
    )

    data class BelongsToCollection(
        val id: Int,
        val name: String,
        @SerializedName("poster_path") val posterPath: String?,
        @SerializedName("backdrop_path") val backdropPath: String?
    )

    // Collection details response (list of movies in a franchise)
    data class CollectionDetailsResponse(
        val id: Int,
        val name: String,
        val overview: String?,
        @SerializedName("poster_path") val posterPath: String?,
        @SerializedName("backdrop_path") val backdropPath: String?,
        val parts: List<CollectionPart>?
    )

    data class CollectionPart(
        val id: Int,
        val title: String,
        @SerializedName("poster_path") val posterPath: String?,
        @SerializedName("release_date") val releaseDate: String?,
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
     * Search for companies/studios (e.g., Disney, Pixar, DreamWorks)
     */
    suspend fun searchCompany(query: String): List<CompanyResult> = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty()) {
            Log.w(TAG, "TMDB API key not configured")
            return@withContext emptyList()
        }

        try {
            val url = "$BASE_URL/search/company?api_key=$apiKey&query=${query.encodeUrl()}"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext emptyList()
                val searchResponse = gson.fromJson(body, SearchCompanyResponse::class.java)
                searchResponse.results
            } else {
                Log.e(TAG, "Company search failed: ${response.code}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Company search error", e)
            emptyList()
        }
    }

    /**
     * Search for TMDB collections (movie series like "Toy Story Collection")
     */
    suspend fun searchCollection(query: String): List<CollectionResult> = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty()) {
            Log.w(TAG, "TMDB API key not configured")
            return@withContext emptyList()
        }

        try {
            val url = "$BASE_URL/search/collection?api_key=$apiKey&query=${query.encodeUrl()}"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext emptyList()
                val searchResponse = gson.fromJson(body, SearchCollectionResponse::class.java)
                searchResponse.results
            } else {
                Log.e(TAG, "Collection search failed: ${response.code}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Collection search error", e)
            emptyList()
        }
    }

    /**
     * Get movie details including franchise/collection info
     */
    suspend fun getMovieDetails(movieId: Int): MovieDetailsResponse? = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty()) return@withContext null

        try {
            val url = "$BASE_URL/movie/$movieId?api_key=$apiKey"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext null
                gson.fromJson(body, MovieDetailsResponse::class.java)
            } else {
                Log.e(TAG, "Movie details failed: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Movie details error", e)
            null
        }
    }

    /**
     * Get movie details with release dates (certifications) in a single API call.
     */
    suspend fun getMovieDetailsWithReleaseDates(movieId: Int): MovieDetailsWithReleaseDatesResponse? =
        withContext(Dispatchers.IO) {
            if (apiKey.isEmpty()) return@withContext null

            try {
                val url = "$BASE_URL/movie/$movieId?api_key=$apiKey&append_to_response=release_dates"
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext null
                    gson.fromJson(body, MovieDetailsWithReleaseDatesResponse::class.java)
                } else {
                    Log.e(TAG, "Movie details with release dates failed: ${response.code}")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Movie details with release dates error", e)
                null
            }
        }

    /**
     * Get TV show content ratings
     */
    suspend fun getTvContentRatings(tvId: Int): TvContentRatingsResponse? =
        withContext(Dispatchers.IO) {
            if (apiKey.isEmpty()) return@withContext null

            try {
                val url = "$BASE_URL/tv/$tvId/content_ratings?api_key=$apiKey"
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext null
                    gson.fromJson(body, TvContentRatingsResponse::class.java)
                } else {
                    Log.e(TAG, "TV content ratings failed: ${response.code}")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "TV content ratings error", e)
                null
            }
        }

    /**
     * Extract movie certification for a country from release dates response.
     * Prefers theatrical (3) or digital (4) release certifications.
     */
    fun extractMovieCertification(
        releaseDates: ReleaseDatesResponse?,
        countryCode: String = "US"
    ): String? {
        val country = releaseDates?.results?.find { it.country == countryCode } ?: return null
        // Prefer theatrical (3) > digital (4) > any non-empty
        val preferred = country.releaseDates
            .filter { it.certification.isNotBlank() }
            .sortedBy { when (it.type) { 3 -> 0; 4 -> 1; else -> 2 } }
        return preferred.firstOrNull()?.certification
    }

    /**
     * Extract TV rating for a country from content ratings response.
     */
    fun extractTvRating(
        contentRatings: TvContentRatingsResponse?,
        countryCode: String = "US"
    ): String? {
        return contentRatings?.results
            ?.find { it.country == countryCode }
            ?.rating
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Get collection details (all movies in a franchise)
     */
    suspend fun getCollectionDetails(collectionId: Int): CollectionDetailsResponse? =
        withContext(Dispatchers.IO) {
            if (apiKey.isEmpty()) return@withContext null

            try {
                val url = "$BASE_URL/collection/$collectionId?api_key=$apiKey"
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext null
                    gson.fromJson(body, CollectionDetailsResponse::class.java)
                } else {
                    Log.e(TAG, "Collection details failed: ${response.code}")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Collection details error", e)
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
