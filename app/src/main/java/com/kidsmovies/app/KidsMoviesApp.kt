package com.kidsmovies.app

import android.app.Application
import android.util.Log
import com.kidsmovies.app.artwork.ArtworkFetcher
import com.kidsmovies.app.artwork.TmdbArtworkManager
import com.kidsmovies.app.artwork.TmdbService
import com.kidsmovies.app.cloud.GraphApiClient
import com.kidsmovies.app.cloud.OneDriveScannerService
import com.kidsmovies.app.data.database.AppDatabase
import com.kidsmovies.app.data.repository.*
import com.kidsmovies.app.enforcement.ContentFilter
import com.kidsmovies.app.enforcement.ScheduleEvaluator
import com.kidsmovies.app.enforcement.ViewingTimerManager
import com.kidsmovies.app.pairing.PairingRepository
import com.kidsmovies.app.sync.ContentSyncManager
import com.kidsmovies.app.sync.SettingsSyncManager
import com.kidsmovies.app.sync.SyncWorker
import com.kidsmovies.shared.auth.MsalAuthManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class KidsMoviesApp : Application() {

    companion object {
        lateinit var instance: KidsMoviesApp
            private set
    }

    // Application scope for background operations
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val database by lazy { AppDatabase.getInstance(this) }

    // Existing repositories
    val videoRepository by lazy { VideoRepository(database.videoDao()) }
    val tagRepository by lazy { TagRepository(database.tagDao()) }
    val settingsRepository by lazy { SettingsRepository(database.appSettingsDao(), database.scanFolderDao()) }
    val parentalControlRepository by lazy { ParentalControlRepository(database.parentalControlDao()) }
    val collectionRepository by lazy { CollectionRepository(database.collectionDao()) }
    val metricsRepository by lazy { MetricsRepository(database.viewingSessionDao()) }

    // TMDB artwork services
    val tmdbService by lazy { TmdbService() }
    val tmdbArtworkManager by lazy { TmdbArtworkManager(this, tmdbService) }
    val artworkFetcher by lazy {
        ArtworkFetcher(
            tmdbArtworkManager,
            videoRepository,
            collectionRepository,
            applicationScope
        )
    }

    // Parent control components
    val pairingRepository by lazy { PairingRepository(database.pairingDao()) }

    val settingsSyncManager by lazy {
        SettingsSyncManager(
            database.pairingDao(),
            database.cachedSettingsDao(),
            applicationScope
        )
    }

    val contentSyncManager by lazy {
        ContentSyncManager(
            database.pairingDao(),
            videoRepository,
            collectionRepository,
            applicationScope
        )
    }

    val scheduleEvaluator by lazy { ScheduleEvaluator() }
    val contentFilter by lazy { ContentFilter() }

    // OneDrive/SharePoint streaming components
    val msalAuthManager by lazy { MsalAuthManager(this) }
    val graphApiClient by lazy { GraphApiClient(msalAuthManager) }
    var oneDriveScannerService: OneDriveScannerService? = null
        private set

    val viewingTimerManager by lazy {
        ViewingTimerManager(
            database.cachedSettingsDao(),
            scheduleEvaluator,
            deviceId,
            applicationScope
        )
    }

    // Device ID for this child app (lazy loaded after pairing)
    private var _deviceId: String = ""
    val deviceId: String
        get() = _deviceId

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize pairing state and start components if paired
        applicationScope.launch {
            initializeParentControl()
            initializeOneDrive()
        }
    }

    private suspend fun initializeParentControl() {
        // Initialize pairing state if not exists
        pairingRepository.initializePairingState()

        // Get device ID from pairing state
        val pairingState = pairingRepository.getPairingState()
        _deviceId = pairingState?.childUid ?: ""

        // If paired, start listening for settings and schedule sync
        if (pairingState?.isPaired == true) {
            settingsSyncManager.startListening()
            contentSyncManager.startListening()
            viewingTimerManager.start()

            // Schedule periodic sync every 10 minutes
            SyncWorker.schedulePeriodicSync(this)

            // Perform immediate sync on app start
            SyncWorker.requestImmediateSync(this)
        }
    }

    private suspend fun initializeOneDrive() {
        try {
            // Use dynamic MSAL config with client ID from SharedPreferences
            val clientId = getSharedPreferences("onedrive_config", MODE_PRIVATE)
                .getString("msal_client_id", null)
            if (clientId == null) {
                Log.w("KidsMoviesApp", "OneDrive initialization skipped: no client ID configured")
                return
            }
            val configFile = MsalAuthManager.generateConfig(this, clientId)
            msalAuthManager.initialize(configFile)

            // Create scanner service
            val scanner = OneDriveScannerService(
                this,
                graphApiClient,
                videoRepository,
                collectionRepository,
                applicationScope
            )
            oneDriveScannerService = scanner

            // If configured and signed in, start scanning
            if (scanner.isConfigured && msalAuthManager.isSignedIn()) {
                scanner.scan()
                scanner.startPeriodicScan()
            }
        } catch (e: Exception) {
            Log.w("KidsMoviesApp", "OneDrive initialization skipped: ${e.message}")
        }
    }

    /**
     * Called after successful pairing to start components
     */
    fun onPairingComplete(childUid: String) {
        _deviceId = childUid
        applicationScope.launch {
            settingsSyncManager.startListening()
            contentSyncManager.startListening()
            viewingTimerManager.start()

            // Schedule periodic sync
            SyncWorker.schedulePeriodicSync(this@KidsMoviesApp)

            // Perform immediate sync
            SyncWorker.requestImmediateSync(this@KidsMoviesApp)
        }
    }

    /**
     * Called when a video starts playing - triggers sync to update "currently watching"
     */
    fun onVideoStarted(videoTitle: String) {
        applicationScope.launch {
            contentSyncManager.updateCurrentlyWatching(videoTitle)
            // Also trigger a sync when video starts (per user requirement)
            SyncWorker.requestImmediateSync(this@KidsMoviesApp)
        }
    }

    /**
     * Called when a video stops playing
     */
    fun onVideoStopped() {
        applicationScope.launch {
            contentSyncManager.updateCurrentlyWatching(null)
        }
    }
}
