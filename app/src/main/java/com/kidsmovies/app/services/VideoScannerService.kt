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
import com.kidsmovies.app.data.database.entities.ScanFolder
import com.kidsmovies.app.data.database.entities.Video
import com.kidsmovies.app.utils.Constants
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
        val settingsRepository = app.settingsRepository

        // Get configured scan folders
        val scanFolders = settingsRepository.getEnabledFolders()

        if (scanFolders.isEmpty()) {
            // If no folders configured, use default folders
            val defaultFolders = FileUtils.getDefaultStorageFolders()
            defaultFolders.forEach { path ->
                if (!settingsRepository.folderExists(path)) {
                    settingsRepository.addFolder(
                        ScanFolder(
                            path = path,
                            name = FileUtils.getFolderName(path),
                            includeSubfolders = true,
                            isEnabled = true
                        )
                    )
                }
            }
        }

        val enabledFolders = settingsRepository.getEnabledFolders()

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

                addedCount++
            } catch (e: Exception) {
                Log.e(TAG, "Error adding video: ${file.absolutePath}", e)
            }
        }

        // Update last scan time
        settingsRepository.updateLastScanTime(System.currentTimeMillis())

        // Send completion broadcast
        sendCompletionBroadcast(allVideoFiles.size, addedCount, removedCount)
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
