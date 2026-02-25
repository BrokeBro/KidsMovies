package com.kidsmovies.app

import android.app.Application
import android.util.Log
import com.kidsmovies.app.artwork.ArtworkFetcher
import com.kidsmovies.app.artwork.FranchiseCollectionManager
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
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

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

    // Franchise collection manager
    val franchiseCollectionManager by lazy {
        FranchiseCollectionManager(
            tmdbService,
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

    // StateFlow so OnlineVideosFragment can observe when the scanner becomes available
    private val _oneDriveScannerReady = MutableStateFlow(false)
    val oneDriveScannerReady: StateFlow<Boolean> = _oneDriveScannerReady

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

    private suspend fun syncOneDriveConfigFromFirebase() {
        try {
            val pairingState = pairingRepository.getPairingState()
            if (pairingState == null || !pairingState.isPaired) return
            val familyId = pairingState.familyId ?: return

            val snapshot = FirebaseDatabase.getInstance()
                .getReference("families/$familyId/oneDriveConfig")
                .get().await()

            if (!snapshot.exists()) return

            syncOneDriveConfigFromSnapshot(snapshot)
        } catch (e: Exception) {
            Log.w("KidsMoviesApp", "Could not sync OneDrive config from Firebase: ${e.message}")
        }
    }

    private fun syncOneDriveConfigFromSnapshot(snapshot: DataSnapshot) {
        if (!snapshot.exists()) return

        val prefs = getSharedPreferences("onedrive_config", MODE_PRIVATE)
        val editor = prefs.edit()

        snapshot.child("driveId").getValue(String::class.java)?.let { editor.putString("drive_id", it) }
        snapshot.child("folderId").getValue(String::class.java)?.let { editor.putString("folder_id", it) }
        snapshot.child("folderPath").getValue(String::class.java)?.let { editor.putString("folder_path", it) }
        snapshot.child("accessMode").getValue(String::class.java)?.let { editor.putString("access_mode", it) }
        snapshot.child("shareEncodedId").getValue(String::class.java)?.let { editor.putString("share_encoded_id", it) }
        snapshot.child("shareUrl").getValue(String::class.java)?.let { editor.putString("share_url", it) }
        snapshot.child("clientId").getValue(String::class.java)?.let { editor.putString("msal_client_id", it) }
        editor.putBoolean("is_configured", true)
        editor.apply()

        Log.d("KidsMoviesApp", "Synced OneDrive config from Firebase")
    }

    private fun startOneDriveConfigListener(familyId: String) {
        FirebaseDatabase.getInstance()
            .getReference("families/$familyId/oneDriveConfig")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    syncOneDriveConfigFromSnapshot(snapshot)
                    applicationScope.launch {
                        reinitializeOneDrive()
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.w("KidsMoviesApp", "OneDrive config listener cancelled: ${error.message}")
                }
            })
    }

    private suspend fun initializeOneDrive() {
        try {
            // Sync config from Firebase first (parent app may have updated it)
            syncOneDriveConfigFromFirebase()

            // Start real-time listener for future config changes from parent app
            val pairingState = pairingRepository.getPairingState()
            val familyId = pairingState?.familyId
            if (pairingState?.isPaired == true && familyId != null) {
                startOneDriveConfigListener(familyId)
            }

            createAndStartScanner()
        } catch (e: Exception) {
            Log.w("KidsMoviesApp", "OneDrive initialization skipped: ${e.message}")
        }
    }

    private suspend fun reinitializeOneDrive() {
        try {
            createAndStartScanner()
        } catch (e: Exception) {
            Log.w("KidsMoviesApp", "OneDrive re-initialization failed: ${e.message}")
        }
    }

    private suspend fun createAndStartScanner() {
        val prefs = getSharedPreferences("onedrive_config", MODE_PRIVATE)
        val accessMode = prefs.getString("access_mode", null)

        if (accessMode == "public_link") {
            // Public link mode: no MSAL needed, just create scanner
            val scanner = OneDriveScannerService(
                this,
                graphApiClient,
                videoRepository,
                collectionRepository,
                applicationScope
            )
            oneDriveScannerService = scanner
            _oneDriveScannerReady.value = true

            if (scanner.isConfigured) {
                scanner.scan()
                scanner.startPeriodicScan()
            }
            return
        }

        // Authenticated modes: need MSAL
        val clientId = prefs.getString("msal_client_id", null)
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
        _oneDriveScannerReady.value = true

        // If configured and signed in, start scanning
        if (scanner.isConfigured && msalAuthManager.isSignedIn()) {
            scanner.scan()
            scanner.startPeriodicScan()
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
