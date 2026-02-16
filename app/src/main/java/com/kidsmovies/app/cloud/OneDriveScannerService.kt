package com.kidsmovies.app.cloud

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.kidsmovies.app.data.database.entities.CollectionType
import com.kidsmovies.app.data.database.entities.Video
import com.kidsmovies.app.data.database.entities.VideoCollection
import com.kidsmovies.app.data.repository.CollectionRepository
import com.kidsmovies.app.data.repository.VideoRepository
import com.kidsmovies.app.utils.EpisodeParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OneDriveScannerService(
    private val context: Context,
    private val graphApiClient: GraphApiClient,
    private val videoRepository: VideoRepository,
    private val collectionRepository: CollectionRepository,
    private val coroutineScope: CoroutineScope
) {

    companion object {
        private const val TAG = "OneDriveScannerService"
        private const val PREFS_NAME = "onedrive_config"
        private const val KEY_DRIVE_ID = "drive_id"
        private const val KEY_FOLDER_ID = "folder_id"
        private const val KEY_FOLDER_PATH = "folder_path"
        private const val KEY_LAST_SCAN = "last_scan_time"
        private const val KEY_IS_CONFIGURED = "is_configured"
        private const val SCAN_INTERVAL_MS = 30 * 60 * 1000L // 30 minutes
        private const val URL_EXPIRY_MS = 50 * 60 * 1000L // 50 minutes (URLs last ~1 hour)
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState

    val isConfigured: Boolean
        get() = prefs.getBoolean(KEY_IS_CONFIGURED, false)

    val configuredDriveId: String?
        get() = prefs.getString(KEY_DRIVE_ID, null)

    val configuredFolderId: String?
        get() = prefs.getString(KEY_FOLDER_ID, null)

    val configuredFolderPath: String?
        get() = prefs.getString(KEY_FOLDER_PATH, null)

    fun configure(driveId: String, folderId: String, folderPath: String) {
        prefs.edit()
            .putString(KEY_DRIVE_ID, driveId)
            .putString(KEY_FOLDER_ID, folderId)
            .putString(KEY_FOLDER_PATH, folderPath)
            .putBoolean(KEY_IS_CONFIGURED, true)
            .apply()
    }

    fun disconnect() {
        prefs.edit().clear().apply()
        coroutineScope.launch {
            // Remove all OneDrive videos from local database
            videoRepository.deleteBySourceType("onedrive")
        }
    }

    fun startPeriodicScan() {
        if (!isConfigured) return

        coroutineScope.launch {
            while (true) {
                val lastScan = prefs.getLong(KEY_LAST_SCAN, 0)
                val now = System.currentTimeMillis()

                if (now - lastScan >= SCAN_INTERVAL_MS) {
                    scan()
                }

                kotlinx.coroutines.delay(SCAN_INTERVAL_MS)
            }
        }
    }

    suspend fun scan() {
        val driveId = configuredDriveId ?: return
        val folderId = configuredFolderId ?: return

        if (_scanState.value is ScanState.Scanning) return

        _scanState.value = ScanState.Scanning(0, "Starting scan...")

        try {
            withContext(Dispatchers.IO) {
                // Get all video files from OneDrive folder
                val remoteVideos = graphApiClient.searchVideosRecursive(driveId, folderId)

                _scanState.value = ScanState.Scanning(
                    remoteVideos.size,
                    "Found ${remoteVideos.size} videos. Processing..."
                )

                // Get existing remote IDs in database
                val existingRemoteIds = videoRepository.getAllRemoteIds().toSet()
                val foundRemoteIds = remoteVideos.map { it.item.id }.toSet()

                // Remove videos that no longer exist in OneDrive
                val removedIds = existingRemoteIds - foundRemoteIds
                for (remoteId in removedIds) {
                    val video = videoRepository.getVideoByRemoteId(remoteId)
                    if (video != null) {
                        videoRepository.deleteVideoById(video.id)
                    }
                }

                // Add or update videos
                var addedCount = 0
                var updatedCount = 0

                for (remoteVideo in remoteVideos) {
                    val item = remoteVideo.item
                    val existing = videoRepository.getVideoByRemoteId(item.id)

                    if (existing != null) {
                        // Update download URL if expired or missing
                        if (existing.remoteUrl == null ||
                            existing.remoteUrlExpiry < System.currentTimeMillis()) {
                            val downloadUrl = item.downloadUrl
                            if (downloadUrl != null) {
                                videoRepository.updateRemoteUrl(
                                    item.id,
                                    downloadUrl,
                                    System.currentTimeMillis() + URL_EXPIRY_MS
                                )
                            }
                        }
                        updatedCount++
                    } else {
                        // Create new video entry
                        val title = item.name.substringBeforeLast('.')
                        val duration = item.video?.duration ?: 0
                        val mimeType = item.file?.mimeType ?: "video/*"

                        val video = Video(
                            title = title,
                            filePath = "onedrive://${driveId}/${item.id}",
                            duration = duration,
                            size = item.size,
                            dateAdded = System.currentTimeMillis(),
                            dateModified = System.currentTimeMillis(),
                            folderPath = remoteVideo.folderPath,
                            mimeType = mimeType,
                            sourceType = "onedrive",
                            remoteId = item.id,
                            remoteUrl = item.downloadUrl,
                            remoteUrlExpiry = if (item.downloadUrl != null)
                                System.currentTimeMillis() + URL_EXPIRY_MS else 0
                        )

                        val videoId = videoRepository.insertVideo(video)

                        // Try to detect episode info
                        val episodeInfo = EpisodeParser.parseEpisode(title)
                        if (episodeInfo.hasEpisodeInfo()) {
                            videoRepository.updateEpisodeInfo(
                                videoId,
                                episodeInfo.seasonNumber,
                                episodeInfo.episodeNumber
                            )
                        }

                        addedCount++
                    }
                }

                // Build collection hierarchy from folder structure
                buildCollectionHierarchy(remoteVideos)

                // Update last scan time
                prefs.edit().putLong(KEY_LAST_SCAN, System.currentTimeMillis()).apply()

                _scanState.value = ScanState.Complete(
                    totalVideos = remoteVideos.size,
                    added = addedCount,
                    updated = updatedCount,
                    removed = removedIds.size
                )

                Log.d(TAG, "Scan complete: ${remoteVideos.size} videos found, $addedCount added, $updatedCount updated, ${removedIds.size} removed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning OneDrive", e)
            _scanState.value = ScanState.Error(e.message ?: "Unknown error")
        }
    }

    private suspend fun buildCollectionHierarchy(remoteVideos: List<DriveItemWithPath>) {
        // Group videos by their folder path to detect hierarchy
        val folderGroups = remoteVideos.groupBy { it.folderPath }

        for ((folderPath, videos) in folderGroups) {
            if (folderPath.isEmpty()) continue // Root-level videos, no collection needed

            val pathParts = folderPath.split("/")

            if (pathParts.size >= 2) {
                // Looks like Show/Season structure
                val showName = pathParts[0]
                val seasonName = pathParts[1]

                // Create TV show collection
                val tvShow = getOrCreateCollection(
                    showName, CollectionType.TV_SHOW.name, null, null
                )

                // Try to parse season number
                val seasonInfo = EpisodeParser.parseSeason(seasonName)
                val seasonNumber = seasonInfo?.seasonNumber ?: 1

                // Create season collection
                val season = getOrCreateCollection(
                    seasonName, CollectionType.SEASON.name, tvShow.id, seasonNumber
                )

                // Link videos to season
                for (remoteVideo in videos) {
                    val video = videoRepository.getVideoByRemoteId(remoteVideo.item.id) ?: continue
                    if (!collectionRepository.isVideoInCollection(video.id, season.id)) {
                        collectionRepository.addVideoToCollection(season.id, video.id)
                    }
                }
            } else if (pathParts.size == 1) {
                // Single folder level - could be a show with episodes or a regular collection
                val folderName = pathParts[0]
                val hasEpisodes = videos.any {
                    EpisodeParser.parseEpisode(it.item.name.substringBeforeLast('.')).hasEpisodeInfo()
                }

                if (hasEpisodes) {
                    // Create as TV show with Season 1
                    val tvShow = getOrCreateCollection(
                        folderName, CollectionType.TV_SHOW.name, null, null
                    )
                    val season = getOrCreateCollection(
                        "Season 1", CollectionType.SEASON.name, tvShow.id, 1
                    )

                    for (remoteVideo in videos) {
                        val video = videoRepository.getVideoByRemoteId(remoteVideo.item.id) ?: continue
                        if (!collectionRepository.isVideoInCollection(video.id, season.id)) {
                            collectionRepository.addVideoToCollection(season.id, video.id)
                        }
                    }
                } else {
                    // Regular collection
                    val collection = getOrCreateCollection(
                        folderName, CollectionType.REGULAR.name, null, null
                    )

                    for (remoteVideo in videos) {
                        val video = videoRepository.getVideoByRemoteId(remoteVideo.item.id) ?: continue
                        if (!collectionRepository.isVideoInCollection(video.id, collection.id)) {
                            collectionRepository.addVideoToCollection(collection.id, video.id)
                        }
                    }
                }
            }
        }
    }

    private suspend fun getOrCreateCollection(
        name: String,
        type: String,
        parentId: Long?,
        seasonNumber: Int?
    ): VideoCollection {
        val existing = collectionRepository.getCollectionByName(name)
        if (existing != null) {
            // Update type if needed
            if (existing.collectionType != type) {
                collectionRepository.updateCollectionType(existing.id, type, parentId, seasonNumber)
            }
            return existing
        }

        val collection = VideoCollection(
            name = name,
            collectionType = type,
            parentCollectionId = parentId,
            seasonNumber = seasonNumber,
            sortOrder = seasonNumber ?: 0
        )
        val id = collectionRepository.insertCollection(collection)
        return collection.copy(id = id)
    }

    suspend fun refreshDownloadUrl(remoteId: String): String? {
        val driveId = configuredDriveId ?: return null
        return try {
            val url = graphApiClient.getDownloadUrl(driveId, remoteId)
            videoRepository.updateRemoteUrl(remoteId, url, System.currentTimeMillis() + URL_EXPIRY_MS)
            url
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing download URL for $remoteId", e)
            null
        }
    }

    sealed class ScanState {
        data object Idle : ScanState()
        data class Scanning(val videosFound: Int, val message: String) : ScanState()
        data class Complete(val totalVideos: Int, val added: Int, val updated: Int, val removed: Int) : ScanState()
        data class Error(val message: String) : ScanState()
    }
}
