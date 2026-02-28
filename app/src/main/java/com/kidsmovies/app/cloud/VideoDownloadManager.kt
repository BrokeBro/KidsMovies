package com.kidsmovies.app.cloud

import android.content.Context
import android.util.Log
import com.kidsmovies.app.data.database.entities.Video
import com.kidsmovies.app.data.repository.SettingsRepository
import com.kidsmovies.app.data.repository.VideoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class VideoDownloadManager(
    private val context: Context,
    private val videoRepository: VideoRepository,
    private val settingsRepository: SettingsRepository,
    private val oneDriveScannerService: OneDriveScannerService,
    private val coroutineScope: CoroutineScope
) {

    companion object {
        private const val TAG = "VideoDownloadManager"
        private const val BUFFER_SIZE = 65536
        private const val READ_TIMEOUT_MS = 5 * 60 * 1000 // 5 minutes for large files
        private const val CONNECT_TIMEOUT_MS = 30000
    }

    private val _downloadStates = MutableStateFlow<Map<Long, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<Long, DownloadState>> = _downloadStates

    /**
     * Download a remote video to the starred download folder, maintaining
     * the OneDrive folder structure locally.
     */
    fun downloadVideo(video: Video) {
        if (!video.isRemote()) return

        coroutineScope.launch {
            val downloadFolder = settingsRepository.getDownloadFolder()
            if (downloadFolder == null) {
                updateState(video.id, DownloadState.Error("No download folder set. Star a folder in Folder Settings."))
                return@launch
            }

            updateState(video.id, DownloadState.Downloading(0))

            try {
                // Build local path maintaining OneDrive folder structure
                val localPath = buildLocalPath(downloadFolder.path, video)
                val localFile = File(localPath)
                val tempFile = File(localPath + ".tmp")

                // Create parent directories
                localFile.parentFile?.mkdirs()

                // Get a fresh download URL
                val remoteId = video.remoteId ?: throw IllegalStateException("No remote ID")
                val downloadUrl = oneDriveScannerService.refreshDownloadUrl(remoteId)
                    ?: throw IllegalStateException("Could not get download URL")

                // Download to a temp file first, rename on success
                withContext(Dispatchers.IO) {
                    val url = URL(downloadUrl)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    connection.instanceFollowRedirects = true

                    try {
                        val responseCode = connection.responseCode
                        if (responseCode != HttpURLConnection.HTTP_OK) {
                            throw IllegalStateException("Server returned HTTP $responseCode")
                        }

                        // Use Content-Length header for proper long support
                        val totalSize = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                        var downloadedSize = 0L

                        connection.inputStream.use { input ->
                            FileOutputStream(tempFile).use { output ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                var bytesRead: Int

                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    output.write(buffer, 0, bytesRead)
                                    downloadedSize += bytesRead

                                    if (totalSize > 0) {
                                        val progress = ((downloadedSize * 100) / totalSize).toInt()
                                            .coerceIn(0, 100)
                                        updateState(video.id, DownloadState.Downloading(progress))
                                    }
                                }

                                output.flush()
                                output.fd.sync()
                            }
                        }

                        // Verify downloaded size matches expected
                        val actualSize = tempFile.length()
                        if (totalSize > 0 && actualSize < totalSize) {
                            tempFile.delete()
                            throw IllegalStateException(
                                "Download incomplete: got $actualSize bytes of $totalSize expected"
                            )
                        }

                        if (actualSize == 0L) {
                            tempFile.delete()
                            throw IllegalStateException("Downloaded file is empty")
                        }

                        // Rename temp file to final path
                        if (localFile.exists()) {
                            localFile.delete()
                        }
                        if (!tempFile.renameTo(localFile)) {
                            // Fallback: copy and delete
                            tempFile.copyTo(localFile, overwrite = true)
                            tempFile.delete()
                        }
                    } finally {
                        connection.disconnect()
                    }
                }

                // Update the video with the local download path
                videoRepository.updateLocalDownloadPath(video.id, localPath)
                updateState(video.id, DownloadState.Complete(localPath))
                Log.d(TAG, "Downloaded ${video.title} to $localPath (${localFile.length()} bytes)")

            } catch (e: Exception) {
                Log.e(TAG, "Error downloading ${video.title}", e)
                updateState(video.id, DownloadState.Error(e.message ?: "Download failed"))

                // Clean up any temp files on error
                try {
                    val localPath = buildLocalPath(
                        settingsRepository.getDownloadFolder()?.path ?: "", video
                    )
                    File(localPath + ".tmp").delete()
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Remove a downloaded local copy of a video.
     */
    suspend fun removeDownload(video: Video) {
        val localPath = video.localDownloadPath ?: return

        withContext(Dispatchers.IO) {
            val file = File(localPath)
            if (file.exists()) {
                file.delete()
            }

            // Clean up empty parent directories
            var parent = file.parentFile
            while (parent != null && parent.isDirectory && (parent.listFiles()?.isEmpty() == true)) {
                parent.delete()
                parent = parent.parentFile
            }
        }

        videoRepository.updateLocalDownloadPath(video.id, null)
        updateState(video.id, DownloadState.NotDownloaded)
        Log.d(TAG, "Removed download for ${video.title}")
    }

    /**
     * Build the local file path, maintaining the OneDrive folder structure.
     * E.g., if the video's folderPath is "ShowName/Season 1" and the download folder
     * is "/storage/emulated/0/Movies", the result would be
     * "/storage/emulated/0/Movies/ShowName/Season 1/video.mp4"
     */
    private fun buildLocalPath(downloadFolderPath: String, video: Video): String {
        val fileName = video.title + getExtension(video)

        return if (video.folderPath.isNotEmpty()) {
            "$downloadFolderPath/${video.folderPath}/$fileName"
        } else {
            "$downloadFolderPath/$fileName"
        }
    }

    private fun getExtension(video: Video): String {
        // Try to extract extension from the original filePath
        val originalPath = video.filePath
        return when {
            video.mimeType.contains("mp4") -> ".mp4"
            video.mimeType.contains("mkv") -> ".mkv"
            video.mimeType.contains("avi") -> ".avi"
            video.mimeType.contains("webm") -> ".webm"
            originalPath.contains('.') -> {
                val ext = originalPath.substringAfterLast('.')
                if (ext.length <= 5) ".$ext" else ".mp4"
            }
            else -> ".mp4"
        }
    }

    private fun updateState(videoId: Long, state: DownloadState) {
        _downloadStates.value = _downloadStates.value.toMutableMap().apply {
            put(videoId, state)
        }
    }

    sealed class DownloadState {
        data object NotDownloaded : DownloadState()
        data class Downloading(val progressPercent: Int) : DownloadState()
        data class Complete(val localPath: String) : DownloadState()
        data class Error(val message: String) : DownloadState()
    }
}
