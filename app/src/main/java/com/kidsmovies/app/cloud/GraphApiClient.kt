package com.kidsmovies.app.cloud

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.kidsmovies.shared.auth.MsalAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class GraphApiClient(private val authManager: MsalAuthManager) {

    companion object {
        private const val TAG = "GraphApiClient"
        private const val GRAPH_BASE_URL = "https://graph.microsoft.com/v1.0"
        private const val ONEDRIVE_API_BASE = "https://api.onedrive.com/v1.0"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) KidsMovies/1.0"
        private const val SESSION_VALIDITY_MS = 25 * 60 * 1000L // 25 minutes
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "mpg", "mpeg", "3gp"
        )

        fun encodeSharingUrl(url: String): String {
            val cleanUrl = url.split('?').first()
            val base64 = Base64.encodeToString(cleanUrl.toByteArray(), Base64.NO_WRAP)
            return "u!" + base64.trimEnd('=').replace('/', '_').replace('+', '-')
        }
    }

    /**
     * SharePoint host for direct tenant API access (e.g., "contoso-my.sharepoint.com").
     * When set, share methods will try the SharePoint-native API first.
     */
    var sharePointHost: String? = null

    /** Original sharing URL (with ?e= token) used to establish cookie-based sessions. */
    var shareUrl: String? = null

    private fun getSharePointApiBase(): String? {
        return sharePointHost?.let { "https://$it/_api/v2.0" }
    }

    /**
     * Re-encode from the original share URL, stripping query parameters.
     * Handles backward compatibility with old stored encodings that included ?e=.
     */
    fun getEffectiveEncodedShareId(storedEncodedId: String): String {
        val url = shareUrl ?: return storedEncodedId
        return encodeSharingUrl(url)
    }

    // --- Cookie-based session for SharePoint Business "Anyone" links ---

    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore.getOrPut(url.host) { mutableListOf() }.apply {
                val newNames = cookies.map { it.name }.toSet()
                removeAll { it.name in newNames }
                addAll(cookies)
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookieStore[url.host] ?: emptyList()
        }
    }

    private val sharePointClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private var sessionEstablishedAt: Long = 0
    private var lastSessionUrl: String? = null

    /**
     * Establish a SharePoint session by visiting the original sharing URL.
     * SharePoint validates the sharing token and sets FedAuth/rtFa cookies.
     */
    suspend fun establishSharePointSession(originalShareUrl: String): Boolean {
        // Reuse existing session if still valid
        if (lastSessionUrl == originalShareUrl &&
            System.currentTimeMillis() - sessionEstablishedAt < SESSION_VALIDITY_MS) {
            return true
        }

        return withContext(Dispatchers.IO) {
            try {
                cookieStore.clear()
                val request = Request.Builder()
                    .url(originalShareUrl)
                    .addHeader("User-Agent", USER_AGENT)
                    .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .build()

                val response = sharePointClient.newCall(request).execute()
                response.body?.close()

                Log.d(TAG, "SharePoint session establishment: HTTP ${response.code}")

                if (response.isSuccessful || response.code in 300..399) {
                    sessionEstablishedAt = System.currentTimeMillis()
                    lastSessionUrl = originalShareUrl
                    true
                } else {
                    Log.w(TAG, "Failed to establish SharePoint session: HTTP ${response.code}")
                    false
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error establishing SharePoint session: ${e.message}")
                false
            }
        }
    }

    fun invalidateSession() {
        cookieStore.clear()
        sessionEstablishedAt = 0
        lastSessionUrl = null
    }

    private suspend fun executeSharePointSessionRequest(url: String): String {
        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", USER_AGENT)
            .build()

        return withContext(Dispatchers.IO) {
            val response = sharePointClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val body = response.body?.string() ?: ""
                throw GraphApiException("SharePoint session API error ${response.code}: $body")
            }
            response.body?.string() ?: throw GraphApiException("Empty response body")
        }
    }

    // --- Standard HTTP clients ---

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    private suspend fun getAccessToken(): String {
        return authManager.acquireTokenSilently()
            ?: throw IllegalStateException("No access token available. Please sign in first.")
    }

    private suspend fun executeRequest(url: String): String {
        val token = getAccessToken()
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .build()

        return withContext(Dispatchers.IO) {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val body = response.body?.string() ?: ""
                throw GraphApiException("Graph API error ${response.code}: $body")
            }
            response.body?.string() ?: throw GraphApiException("Empty response body")
        }
    }

    private suspend fun executeUnauthenticatedRequest(url: String): String {
        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .addHeader("User-Agent", USER_AGENT)
            .build()

        return withContext(Dispatchers.IO) {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val body = response.body?.string() ?: ""
                throw GraphApiException("API error ${response.code}: $body")
            }
            response.body?.string() ?: throw GraphApiException("Empty response body")
        }
    }

    suspend fun listChildren(driveId: String, itemId: String): List<DriveItem> {
        val url = "$GRAPH_BASE_URL/drives/$driveId/items/$itemId/children?\$select=id,name,size,file,folder,video,@microsoft.graph.downloadUrl&\$top=200"
        val responseBody = executeRequest(url)
        val result = gson.fromJson(responseBody, DriveItemListResponse::class.java)
        return result.value
    }

    suspend fun getDriveItem(driveId: String, itemId: String): DriveItem {
        val url = "$GRAPH_BASE_URL/drives/$driveId/items/$itemId?\$select=id,name,size,file,folder,video,parentReference,@microsoft.graph.downloadUrl"
        val responseBody = executeRequest(url)
        return gson.fromJson(responseBody, DriveItem::class.java)
    }

    suspend fun getDownloadUrl(driveId: String, itemId: String): String {
        val item = getDriveItem(driveId, itemId)
        return item.downloadUrl
            ?: throw GraphApiException("No download URL available for item $itemId")
    }

    suspend fun searchVideosRecursive(
        driveId: String,
        folderId: String,
        results: MutableList<DriveItemWithPath> = mutableListOf(),
        currentPath: String = ""
    ): List<DriveItemWithPath> {
        try {
            val children = listChildren(driveId, folderId)

            for (child in children) {
                if (child.folder != null) {
                    // Recurse into subfolders
                    val subPath = if (currentPath.isEmpty()) child.name else "$currentPath/${child.name}"
                    searchVideosRecursive(driveId, child.id, results, subPath)
                } else if (child.file != null && isVideoFile(child.name)) {
                    results.add(DriveItemWithPath(child, currentPath))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning folder $folderId at path '$currentPath'", e)
        }

        return results
    }

    suspend fun getMyDrives(): List<Drive> {
        val url = "$GRAPH_BASE_URL/me/drives"
        val responseBody = executeRequest(url)
        val result = gson.fromJson(responseBody, DriveListResponse::class.java)
        return result.value
    }

    suspend fun getSharedDrives(): List<Drive> {
        // Get sites the user has access to and their drives
        val drives = mutableListOf<Drive>()
        try {
            // First get personal OneDrive
            val personalDrives = getMyDrives()
            drives.addAll(personalDrives)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting personal drives", e)
        }
        return drives
    }

    suspend fun getRootChildren(driveId: String): List<DriveItem> {
        val url = "$GRAPH_BASE_URL/drives/$driveId/root/children?\$select=id,name,size,file,folder,video,@microsoft.graph.downloadUrl&\$top=200"
        val responseBody = executeRequest(url)
        val result = gson.fromJson(responseBody, DriveItemListResponse::class.java)
        return result.value
    }

    // --- Public share methods (supports both OneDrive Personal and SharePoint/OneDrive for Business) ---

    suspend fun listShareChildren(encodedShareId: String, itemId: String): List<DriveItem> {
        val effectiveId = getEffectiveEncodedShareId(encodedShareId)

        // Try SharePoint-native API with session cookies first (business "Anyone" shares)
        val spBase = getSharePointApiBase()
        if (spBase != null && shareUrl != null) {
            try {
                val spUrl = "$spBase/shares/$effectiveId/items/$itemId/children?\$select=id,name,size,file,folder,video,@content.downloadUrl&\$top=200"
                val responseBody = executeSharePointSessionRequest(spUrl)
                val result = gson.fromJson(responseBody, DriveItemListResponse::class.java)
                return result.value
            } catch (e: Exception) {
                Log.w(TAG, "SharePoint session API share listing failed: ${e.message}")
            }
        }

        // Try SharePoint-native API without session
        if (spBase != null) {
            try {
                val spUrl = "$spBase/shares/$effectiveId/items/$itemId/children?\$select=id,name,size,file,folder,video,@content.downloadUrl&\$top=200"
                val responseBody = executeUnauthenticatedRequest(spUrl)
                val result = gson.fromJson(responseBody, DriveItemListResponse::class.java)
                return result.value
            } catch (e: Exception) {
                Log.w(TAG, "SharePoint API share listing failed: ${e.message}")
            }
        }

        // Try Graph API (supports OneDrive Personal, requires auth for business)
        try {
            val graphUrl = "$GRAPH_BASE_URL/shares/$effectiveId/items/$itemId/children?\$select=id,name,size,file,folder,video,@microsoft.graph.downloadUrl&\$top=200"
            val responseBody = executeUnauthenticatedRequest(graphUrl)
            val result = gson.fromJson(responseBody, DriveItemListResponse::class.java)
            return result.value
        } catch (e: Exception) {
            Log.w(TAG, "Graph API share listing failed: ${e.message}")
        }

        // Fallback to OneDrive consumer API for personal OneDrive public links
        val fallbackUrl = "$ONEDRIVE_API_BASE/shares/$effectiveId/items/$itemId/children?\$select=id,name,size,file,folder,video,@content.downloadUrl&\$top=200"
        val responseBody = executeUnauthenticatedRequest(fallbackUrl)
        val result = gson.fromJson(responseBody, DriveItemListResponse::class.java)
        return result.value
    }

    suspend fun getShareItem(encodedShareId: String, itemId: String): DriveItem {
        val effectiveId = getEffectiveEncodedShareId(encodedShareId)

        // Try SharePoint-native API with session cookies first (business "Anyone" shares)
        val spBase = getSharePointApiBase()
        if (spBase != null && shareUrl != null) {
            try {
                val spUrl = "$spBase/shares/$effectiveId/items/$itemId?\$select=id,name,size,file,folder,video,parentReference,@content.downloadUrl"
                val responseBody = executeSharePointSessionRequest(spUrl)
                return gson.fromJson(responseBody, DriveItem::class.java)
            } catch (e: Exception) {
                Log.w(TAG, "SharePoint session API share item failed: ${e.message}")
            }
        }

        // Try SharePoint-native API without session
        if (spBase != null) {
            try {
                val spUrl = "$spBase/shares/$effectiveId/items/$itemId?\$select=id,name,size,file,folder,video,parentReference,@content.downloadUrl"
                val responseBody = executeUnauthenticatedRequest(spUrl)
                return gson.fromJson(responseBody, DriveItem::class.java)
            } catch (e: Exception) {
                Log.w(TAG, "SharePoint API share item failed: ${e.message}")
            }
        }

        // Try Graph API (supports OneDrive Personal, requires auth for business)
        try {
            val graphUrl = "$GRAPH_BASE_URL/shares/$effectiveId/items/$itemId?\$select=id,name,size,file,folder,video,parentReference,@microsoft.graph.downloadUrl"
            val responseBody = executeUnauthenticatedRequest(graphUrl)
            return gson.fromJson(responseBody, DriveItem::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Graph API share item failed: ${e.message}")
        }

        // Fallback to OneDrive consumer API for personal OneDrive public links
        val fallbackUrl = "$ONEDRIVE_API_BASE/shares/$effectiveId/items/$itemId?\$select=id,name,size,file,folder,video,parentReference,@content.downloadUrl"
        val responseBody = executeUnauthenticatedRequest(fallbackUrl)
        return gson.fromJson(responseBody, DriveItem::class.java)
    }

    suspend fun getShareDownloadUrl(encodedShareId: String, itemId: String): String {
        val item = getShareItem(encodedShareId, itemId)
        return item.downloadUrl ?: item.contentDownloadUrl
            ?: throw GraphApiException("No download URL available for shared item $itemId")
    }

    suspend fun searchVideosRecursiveViaShare(
        encodedShareId: String,
        folderId: String,
        results: MutableList<DriveItemWithPath> = mutableListOf(),
        currentPath: String = ""
    ): List<DriveItemWithPath> {
        try {
            val children = listShareChildren(encodedShareId, folderId)

            for (child in children) {
                if (child.folder != null) {
                    val subPath = if (currentPath.isEmpty()) child.name else "$currentPath/${child.name}"
                    searchVideosRecursiveViaShare(encodedShareId, child.id, results, subPath)
                } else if (child.file != null && isVideoFile(child.name)) {
                    results.add(DriveItemWithPath(child, currentPath))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning shared folder $folderId at path '$currentPath'", e)
        }

        return results
    }

    private fun isVideoFile(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in VIDEO_EXTENSIONS
    }
}

// Data classes for Graph API responses

data class DriveItemListResponse(
    val value: List<DriveItem> = emptyList(),
    @SerializedName("@odata.nextLink") val nextLink: String? = null
)

data class DriveListResponse(
    val value: List<Drive> = emptyList()
)

data class Drive(
    val id: String = "",
    val name: String = "",
    val driveType: String = "", // "personal", "business", "documentLibrary"
    val owner: DriveOwner? = null
)

data class DriveOwner(
    val user: DriveUser? = null
)

data class DriveUser(
    val displayName: String = ""
)

data class DriveItem(
    val id: String = "",
    val name: String = "",
    val size: Long = 0,
    val file: FileInfo? = null,
    val folder: FolderInfo? = null,
    val video: VideoInfo? = null,
    val parentReference: ParentReference? = null,
    @SerializedName("@microsoft.graph.downloadUrl") val downloadUrl: String? = null,
    @SerializedName("@content.downloadUrl") val contentDownloadUrl: String? = null
)

data class FileInfo(
    val mimeType: String = ""
)

data class FolderInfo(
    val childCount: Int = 0
)

data class VideoInfo(
    val duration: Long = 0, // milliseconds
    val width: Int = 0,
    val height: Int = 0
)

data class ParentReference(
    val driveId: String = "",
    val id: String = "",
    val path: String = ""
)

data class DriveItemWithPath(
    val item: DriveItem,
    val folderPath: String // Relative path from root folder
)

class GraphApiException(message: String) : Exception(message)
