package com.kidsmovies.app.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.kidsmovies.app.KidsMoviesApp
import com.kidsmovies.app.R
import com.kidsmovies.app.data.database.entities.CollectionType
import com.kidsmovies.app.data.database.entities.Video
import com.kidsmovies.app.data.database.entities.VideoCollection
import com.kidsmovies.app.data.repository.CollectionRepository
import com.kidsmovies.app.data.repository.VideoRepository
import com.kidsmovies.app.utils.Constants
import com.kidsmovies.app.utils.EpisodeParser
import com.kidsmovies.app.utils.FileUtils
import com.kidsmovies.app.utils.ThumbnailUtils
import kotlinx.coroutines.*
import java.io.File

class VideoScannerService : Service() {

    companion object {
        const val TAG = "VideoScannerService"
        const val ACTION_SCAN_COMPLETE = "com.kidsmovies.app.SCAN_COMPLETE"
        const val ACTION_SCAN_PROGRESS = "com.kidsmovies.app.SCAN_PROGRESS"
        const val EXTRA_VIDEOS_FOUND = "videos_found"
        const val EXTRA_VIDEOS_ADDED = "videos_added"
        const val EXTRA_VIDEOS_REMOVED = "videos_removed"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_CURRENT_FILE = "current_file"

        fun startScan(context: Context) {
            val intent = Intent(context, VideoScannerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var app: KidsMoviesApp

    override fun onCreate() {
        super.onCreate()
        app = application as KidsMoviesApp
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(Constants.NOTIFICATION_SCAN_SERVICE, createNotification("Starting scan..."))

        serviceScope.launch {
            try {
                scanVideos()
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning videos", e)
            } finally {
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private suspend fun scanVideos() {
        val videoRepository = app.videoRepository
        val collectionRepository = app.collectionRepository
        val settingsRepository = app.settingsRepository

        // Get configured scan folders - only scan user-selected folders
        val enabledFolders = settingsRepository.getEnabledFolders()

        if (enabledFolders.isEmpty()) {
            // No folders configured - send completion with 0 videos
            // User should add their own folders via Settings > Scan Folders
            sendCompletionBroadcast(0, 0, 0)
            return
        }

        // Collect all video files
        val allVideoFiles = mutableListOf<File>()
        enabledFolders.forEach { folder ->
            val files = FileUtils.getVideoFiles(folder.path, folder.includeSubfolders)
            allVideoFiles.addAll(files)
        }

        // Get existing video paths
        val existingPaths = videoRepository.getAllFilePaths().toSet()

        // Find new videos
        val newVideoFiles = allVideoFiles.filter { !existingPaths.contains(it.absolutePath) }

        // Find removed videos
        val currentPaths = allVideoFiles.map { it.absolutePath }.toSet()
        val removedPaths = existingPaths.filter { !currentPaths.contains(it) && !File(it).exists() }

        var addedCount = 0
        var removedCount = 0

        // Remove videos that no longer exist
        removedPaths.forEach { path ->
            videoRepository.deleteVideoByPath(path)
            removedCount++
        }

        // Track folders containing new videos for hierarchy detection
        val foldersWithNewVideos = mutableSetOf<String>()

        // Add new videos
        val totalNew = newVideoFiles.size
        newVideoFiles.forEachIndexed { index, file ->
            try {
                updateNotification("Scanning: ${file.name} (${index + 1}/$totalNew)")
                sendProgressBroadcast(index + 1, totalNew, file.name)

                val video = createVideoFromFile(file)
                val videoId = videoRepository.insertVideo(video)

                // Generate thumbnail
                val thumbnailPath = ThumbnailUtils.generateThumbnail(this, file.absolutePath, videoId)
                if (thumbnailPath != null) {
                    videoRepository.updateThumbnail(videoId, thumbnailPath)
                }

                // Track the folder for hierarchy detection
                file.parent?.let { foldersWithNewVideos.add(it) }

                addedCount++
            } catch (e: Exception) {
                Log.e(TAG, "Error adding video: ${file.absolutePath}", e)
            }
        }

        // Detect and create TV show/season hierarchy for new videos
        if (foldersWithNewVideos.isNotEmpty()) {
            updateNotification("Detecting TV shows and seasons...")
            detectAndCreateHierarchy(foldersWithNewVideos, videoRepository, collectionRepository)
        }

        // Detect and create franchise collections from TMDB data
        val settings = settingsRepository.getSettings()
        if (settings?.autoCreateFranchiseCollections == true) {
            updateNotification("Detecting movie franchises...")
            app.franchiseCollectionManager.detectAndCreateFranchises()
        }

        // Update last scan time
        settingsRepository.updateLastScanTime(System.currentTimeMillis())

        // Send completion broadcast
        sendCompletionBroadcast(allVideoFiles.size, addedCount, removedCount)
    }

    /**
     * Detect folder hierarchy and create TV Show/Season collections automatically.
     * Analyzes folder structure to find patterns like:
     * - Show Name/Season 1/episode.mp4
     * - Show Name/S01/episode.mp4
     */
    private suspend fun detectAndCreateHierarchy(
        foldersWithVideos: Set<String>,
        videoRepository: VideoRepository,
        collectionRepository: CollectionRepository
    ) {
        // Group videos by their parent folders to detect season structures
        val folderToVideos = mutableMapOf<String, MutableList<Video>>()

        for (folderPath in foldersWithVideos) {
            val folder = File(folderPath)
            if (!folder.exists()) continue

            // Get videos in this folder
            val videosInFolder = videoRepository.getAllVideos().filter {
                File(it.filePath).parent == folderPath
            }
            if (videosInFolder.isNotEmpty()) {
                folderToVideos[folderPath] = videosInFolder.toMutableList()
            }
        }

        // For each folder with videos, check if it looks like a season folder
        for ((folderPath, videos) in folderToVideos) {
            val folder = File(folderPath)
            val folderName = folder.name
            val parentFolder = folder.parentFile

            // Try to parse season info from folder name
            val seasonInfo = EpisodeParser.parseSeason(folderName)

            if (seasonInfo != null && parentFolder != null) {
                // This looks like a season folder
                val showName = seasonInfo.showName ?: parentFolder.name

                // Check if parent has other season-like folders (confirms it's a TV show)
                val siblingFolders = parentFolder.listFiles { file -> file.isDirectory }?.map { it.name } ?: emptyList()
                val hasSiblingSeasons = siblingFolders.any { sibling ->
                    sibling != folderName && EpisodeParser.parseSeason(sibling) != null
                }

                // Create TV show collection if needed
                val tvShowCollection = getOrCreateTvShowCollection(showName, collectionRepository)

                // Create season collection
                val seasonCollection = getOrCreateSeasonCollection(
                    tvShowId = tvShowCollection.id,
                    seasonNumber = seasonInfo.seasonNumber,
                    seasonName = folderName,
                    collectionRepository = collectionRepository
                )

                // Add videos to season and parse episode info
                for (video in videos) {
                    val episodeInfo = EpisodeParser.parseEpisode(video.title)

                    // Update video with episode info
                    if (episodeInfo.hasEpisodeInfo()) {
                        videoRepository.updateEpisodeInfo(
                            video.id,
                            episodeInfo.seasonNumber ?: seasonInfo.seasonNumber,
                            episodeInfo.episodeNumber
                        )
                    }

                    // Add to season collection if not already
                    if (!collectionRepository.isVideoInCollection(video.id, seasonCollection.id)) {
                        collectionRepository.addVideoToCollection(seasonCollection.id, video.id)
                    }
                }

                Log.d(TAG, "Created TV hierarchy: $showName > Season ${seasonInfo.seasonNumber} with ${videos.size} episodes")
            } else {
                // Not a season folder by name - check if videos have episode patterns
                val hasEpisodes = videos.any { EpisodeParser.parseEpisode(it.title).hasEpisodeInfo() }

                if (hasEpisodes && parentFolder != null) {
                    // Check if sibling folders look like seasons (this folder might be a non-standard season name)
                    val siblingFolders = parentFolder.listFiles { file -> file.isDirectory }?.map { it.name } ?: emptyList()
                    val hasSiblingSeasons = siblingFolders.any { sibling ->
                        sibling != folderName && EpisodeParser.parseSeason(sibling) != null
                    }

                    if (hasSiblingSeasons) {
                        // This folder is a sibling of season folders - likely a season with a custom name
                        // Try to determine season number from filenames
                        val seasonNumbers = videos.mapNotNull { EpisodeParser.parseEpisode(it.title).seasonNumber }
                        val inferredSeason = seasonNumbers.groupBy { it }.maxByOrNull { it.value.size }?.key

                        if (inferredSeason != null) {
                            val showName = parentFolder.name
                            val tvShowCollection = getOrCreateTvShowCollection(showName, collectionRepository)
                            val seasonCollection = getOrCreateSeasonCollection(
                                tvShowId = tvShowCollection.id,
                                seasonNumber = inferredSeason,
                                seasonName = folderName,
                                collectionRepository = collectionRepository
                            )

                            for (video in videos) {
                                val episodeInfo = EpisodeParser.parseEpisode(video.title)
                                if (episodeInfo.hasEpisodeInfo()) {
                                    videoRepository.updateEpisodeInfo(
                                        video.id,
                                        episodeInfo.seasonNumber ?: inferredSeason,
                                        episodeInfo.episodeNumber
                                    )
                                }
                                if (!collectionRepository.isVideoInCollection(video.id, seasonCollection.id)) {
                                    collectionRepository.addVideoToCollection(seasonCollection.id, video.id)
                                }
                            }

                            Log.d(TAG, "Created TV hierarchy (inferred): ${showName} > $folderName (Season $inferredSeason) with ${videos.size} episodes")
                        }
                    } else {
                        // No sibling seasons - standalone folder with episodes
                        // Group by season number from filenames to create proper seasons
                        val showName = folderName
                        val tvShowCollection = getOrCreateTvShowCollection(showName, collectionRepository)

                        val videosWithEpisodes = videos.mapNotNull { video ->
                            val episodeInfo = EpisodeParser.parseEpisode(video.title)
                            if (episodeInfo.hasEpisodeInfo()) Triple(video, episodeInfo, episodeInfo.seasonNumber ?: 1) else null
                        }

                        val seasonGroups = videosWithEpisodes.groupBy { it.third }

                        for ((seasonNum, seasonVideos) in seasonGroups) {
                            val seasonCollection = getOrCreateSeasonCollection(
                                tvShowId = tvShowCollection.id,
                                seasonNumber = seasonNum,
                                seasonName = "Season $seasonNum",
                                collectionRepository = collectionRepository
                            )

                            for ((video, episodeInfo, _) in seasonVideos) {
                                videoRepository.updateEpisodeInfo(
                                    video.id,
                                    episodeInfo.seasonNumber ?: seasonNum,
                                    episodeInfo.episodeNumber
                                )
                                if (!collectionRepository.isVideoInCollection(video.id, seasonCollection.id)) {
                                    collectionRepository.addVideoToCollection(seasonCollection.id, video.id)
                                }
                            }
                        }

                        Log.d(TAG, "Created show: $showName with ${seasonGroups.size} season(s), ${videos.size} episodes")
                    }
                }
            }
        }
    }

    /**
     * Get or create a TV Show collection.
     */
    private suspend fun getOrCreateTvShowCollection(
        showName: String,
        collectionRepository: CollectionRepository
    ): VideoCollection {
        // Check if collection already exists
        val existing = collectionRepository.getCollectionByName(showName)
        if (existing != null) {
            // If it's already a TV show, return it
            if (existing.isTvShow()) {
                return existing
            }
            // Convert to TV show if it was a regular collection
            collectionRepository.updateCollectionType(
                existing.id,
                CollectionType.TV_SHOW.name,
                null,
                null
            )
            return existing.copy(collectionType = CollectionType.TV_SHOW.name)
        }

        // Create new TV show collection
        val newCollection = VideoCollection(
            name = showName,
            collectionType = CollectionType.TV_SHOW.name
        )
        val id = collectionRepository.insertCollection(newCollection)
        return newCollection.copy(id = id)
    }

    /**
     * Get or create a Season collection linked to a TV Show.
     */
    private suspend fun getOrCreateSeasonCollection(
        tvShowId: Long,
        seasonNumber: Int,
        seasonName: String,
        collectionRepository: CollectionRepository
    ): VideoCollection {
        // Check if season already exists for this TV show
        val existingSeasons = collectionRepository.getSeasonsForShow(tvShowId)
        val existing = existingSeasons.find { it.seasonNumber == seasonNumber }
        if (existing != null) {
            return existing
        }

        // Create new season collection
        val newSeason = VideoCollection(
            name = seasonName,
            collectionType = CollectionType.SEASON.name,
            parentCollectionId = tvShowId,
            seasonNumber = seasonNumber,
            sortOrder = seasonNumber // Sort by season number
        )
        val id = collectionRepository.insertCollection(newSeason)
        return newSeason.copy(id = id)
    }

    private fun createVideoFromFile(file: File): Video {
        val duration = ThumbnailUtils.getVideoDuration(file.absolutePath)
        val title = FileUtils.getFileNameWithoutExtension(file.absolutePath)

        return Video(
            title = title,
            filePath = file.absolutePath,
            duration = duration,
            size = file.length(),
            dateAdded = System.currentTimeMillis(),
            dateModified = file.lastModified(),
            folderPath = file.parent ?: "",
            mimeType = FileUtils.getMimeType(file.absolutePath)
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.CHANNEL_SCAN_SERVICE,
                "Video Scanner",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while scanning for videos"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(message: String): android.app.Notification {
        return NotificationCompat.Builder(this, Constants.CHANNEL_SCAN_SERVICE)
            .setContentTitle("Kids Movies")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(message: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(Constants.NOTIFICATION_SCAN_SERVICE, createNotification(message))
    }

    private fun sendProgressBroadcast(current: Int, total: Int, currentFile: String) {
        val intent = Intent(ACTION_SCAN_PROGRESS).apply {
            putExtra(EXTRA_PROGRESS, (current * 100) / total)
            putExtra(EXTRA_CURRENT_FILE, currentFile)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun sendCompletionBroadcast(totalFound: Int, added: Int, removed: Int) {
        val intent = Intent(ACTION_SCAN_COMPLETE).apply {
            putExtra(EXTRA_VIDEOS_FOUND, totalFound)
            putExtra(EXTRA_VIDEOS_ADDED, added)
            putExtra(EXTRA_VIDEOS_REMOVED, removed)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
}
