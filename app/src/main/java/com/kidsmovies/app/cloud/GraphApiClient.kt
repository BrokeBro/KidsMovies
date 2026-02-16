package com.kidsmovies.app.cloud

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.kidsmovies.shared.auth.MsalAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class GraphApiClient(private val authManager: MsalAuthManager) {

    companion object {
        private const val TAG = "GraphApiClient"
        private const val GRAPH_BASE_URL = "https://graph.microsoft.com/v1.0"
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "mpg", "mpeg", "3gp"
        )
    }

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
    @SerializedName("@microsoft.graph.downloadUrl") val downloadUrl: String? = null
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
