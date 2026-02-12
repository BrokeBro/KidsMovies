package com.kidsmovies.app.ui.activities

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.kidsmovies.app.KidsMoviesApp
import com.kidsmovies.app.R
import com.kidsmovies.app.databinding.ActivitySettingsBinding
import com.kidsmovies.app.services.VideoScannerService
import com.kidsmovies.app.ui.adapters.NavigationTab
import com.kidsmovies.app.ui.adapters.NavigationTabAdapter
import com.kidsmovies.app.ui.adapters.NavigationTabTouchHelper
import com.kidsmovies.app.pairing.PairingActivity
import com.kidsmovies.app.utils.ColorSchemes
import com.kidsmovies.app.utils.Constants
import com.kidsmovies.app.utils.DatabaseExportImport
import com.kidsmovies.app.utils.ThemeManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var app: KidsMoviesApp
    private lateinit var navigationTabAdapter: NavigationTabAdapter

    private var currentColorScheme = "blue"
    private var currentGridColumns = 4

    // Tab display name mappings
    private val tabDisplayNames = mapOf(
        "all_movies" to R.string.show_all_movies_tab,
        "favourites" to R.string.show_favourites_tab,
        "collections" to R.string.show_collections_tab,
        "recent" to R.string.show_recent_tab,
        "online" to R.string.show_online_tab
    )

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { exportData(it) }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { showImportConfirmation(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        app = application as KidsMoviesApp

        setupToolbar()
        setupNavigationTabs()
        loadSettings()
        setupListeners()
        loadPairingStatus()
    }

    private fun loadPairingStatus() {
        lifecycleScope.launch {
            val isPaired = app.pairingRepository.isPaired()
            val pairingState = app.pairingRepository.getPairingState()

            if (isPaired) {
                binding.connectParentTitle.text = getString(R.string.already_paired)
                binding.connectParentStatus.text = pairingState?.deviceName ?: ""
            } else {
                binding.connectParentTitle.text = getString(R.string.connect_to_parent)
                binding.connectParentStatus.text = getString(R.string.pairing_instructions)
            }
        }
    }

    private fun setupNavigationTabs() {
        navigationTabAdapter = NavigationTabAdapter(
            onTabChanged = { tab -> handleTabChanged(tab) },
            onOrderChanged = { tabs -> handleOrderChanged(tabs) }
        )

        binding.navigationTabsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
            adapter = navigationTabAdapter
        }

        val touchHelper = ItemTouchHelper(NavigationTabTouchHelper(navigationTabAdapter))
        touchHelper.attachToRecyclerView(binding.navigationTabsRecyclerView)
        navigationTabAdapter.setItemTouchHelper(touchHelper)
    }

    private fun handleTabChanged(tab: NavigationTab) {
        lifecycleScope.launch {
            when (tab.id) {
                "all_movies" -> app.settingsRepository.setShowAllMoviesTab(tab.isEnabled)
                "favourites" -> app.settingsRepository.setShowFavouritesTab(tab.isEnabled)
                "collections" -> app.settingsRepository.setShowCollectionsTab(tab.isEnabled)
                "recent" -> app.settingsRepository.setShowRecentTab(tab.isEnabled)
                "online" -> app.settingsRepository.setShowOnlineTab(tab.isEnabled)
            }
        }
    }

    private fun handleOrderChanged(tabs: List<NavigationTab>) {
        val tabOrder = tabs.joinToString(",") { it.id }
        lifecycleScope.launch {
            app.settingsRepository.setTabOrder(tabOrder)
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            ThemeManager.applyTheme(this@SettingsActivity)
        }
        loadPairingStatus()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun loadSettings() {
        lifecycleScope.launch {
            val settings = app.settingsRepository.getSettings()
            settings?.let {
                currentColorScheme = it.colorScheme
                currentGridColumns = it.gridColumns

                updateColorSchemeDisplay()
                updateGridColumnsDisplay()

                // Load navigation tabs in the saved order
                val tabOrder = it.tabOrder.split(",")
                val tabEnabledMap = mapOf(
                    "all_movies" to it.showAllMoviesTab,
                    "favourites" to it.showFavouritesTab,
                    "collections" to it.showCollectionsTab,
                    "recent" to it.showRecentTab,
                    "online" to it.showOnlineTab
                )

                val tabs = tabOrder.mapNotNull { tabId ->
                    val displayNameRes = tabDisplayNames[tabId] ?: return@mapNotNull null
                    NavigationTab(
                        id = tabId,
                        displayName = getString(displayNameRes),
                        isEnabled = tabEnabledMap[tabId] ?: true
                    )
                }

                navigationTabAdapter.submitList(tabs)
            }
        }
    }

    private fun updateColorSchemeDisplay() {
        val scheme = ColorSchemes.getScheme(currentColorScheme)
        binding.currentColorScheme.text = scheme.displayName

        val drawable = binding.colorPreview.background as? GradientDrawable
            ?: GradientDrawable()
        drawable.setColor(Color.parseColor(scheme.primaryColor))
        drawable.shape = GradientDrawable.OVAL
        binding.colorPreview.background = drawable
    }

    private fun updateGridColumnsDisplay() {
        binding.gridColumnsValue.text = currentGridColumns.toString()
        binding.gridColumnsSeekBar.progress = currentGridColumns - Constants.MIN_GRID_COLUMNS
    }

    private fun setupListeners() {
        binding.colorSchemeOption.setOnClickListener {
            showColorSchemeDialog()
        }

        binding.gridColumnsSeekBar.max = Constants.MAX_GRID_COLUMNS - Constants.MIN_GRID_COLUMNS
        binding.gridColumnsSeekBar.progress = currentGridColumns - Constants.MIN_GRID_COLUMNS
        binding.gridColumnsSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentGridColumns = progress + Constants.MIN_GRID_COLUMNS
                binding.gridColumnsValue.text = currentGridColumns.toString()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                lifecycleScope.launch {
                    app.settingsRepository.setGridColumns(currentGridColumns)
                }
            }
        })

        binding.connectParentOption.setOnClickListener {
            startActivity(Intent(this, PairingActivity::class.java))
        }

        binding.usageMetricsOption.setOnClickListener {
            startActivity(Intent(this, UsageMetricsActivity::class.java))
        }

        binding.scanFoldersOption.setOnClickListener {
            startActivity(Intent(this, FolderPickerActivity::class.java))
        }

        binding.scanNowOption.setOnClickListener {
            VideoScannerService.startScan(this)
            Toast.makeText(this, R.string.scanning_videos, Toast.LENGTH_SHORT).show()
        }

        binding.manageTagsOption.setOnClickListener {
            startActivity(Intent(this, TagManagerActivity::class.java))
        }

        binding.exportDataOption.setOnClickListener {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            exportLauncher.launch("kidsmovies_backup_$timestamp.json")
        }

        binding.importDataOption.setOnClickListener {
            importLauncher.launch(arrayOf("application/json"))
        }

        binding.oneDriveOption.setOnClickListener {
            Toast.makeText(this, "OneDrive integration coming soon!", Toast.LENGTH_SHORT).show()
        }

        binding.donateOption.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.kofi_url))))
        }

        binding.fetchArtworkOption.setOnClickListener {
            fetchArtwork()
        }

        binding.clearArtworkCacheOption.setOnClickListener {
            clearArtworkCache()
        }

        // Update artwork cache size display
        updateArtworkCacheSize()
    }

    private fun fetchArtwork() {
        binding.fetchArtworkStatus.text = getString(R.string.fetching_artwork)
        Toast.makeText(this, R.string.fetching_artwork, Toast.LENGTH_SHORT).show()

        // Fetch all missing artwork in background
        app.artworkFetcher.fetchAllMissing()

        // Update status after a delay (artwork fetches asynchronously)
        lifecycleScope.launch {
            kotlinx.coroutines.delay(2000)
            binding.fetchArtworkStatus.text = getString(R.string.fetch_artwork_desc)
            updateArtworkCacheSize()
        }
    }

    private fun clearArtworkCache() {
        app.tmdbArtworkManager.clearCache()
        Toast.makeText(this, R.string.artwork_cache_cleared, Toast.LENGTH_SHORT).show()
        updateArtworkCacheSize()
    }

    private fun updateArtworkCacheSize() {
        val cacheSize = app.tmdbArtworkManager.getCacheSize()
        val formattedSize = when {
            cacheSize >= 1024 * 1024 -> String.format("%.1f MB", cacheSize / (1024.0 * 1024.0))
            cacheSize >= 1024 -> String.format("%.1f KB", cacheSize / 1024.0)
            else -> "$cacheSize bytes"
        }
        binding.artworkCacheSize.text = getString(R.string.artwork_cache_size, formattedSize)
    }

    private fun showColorSchemeDialog() {
        val schemes = ColorSchemes.getAllSchemes()
        val schemeNames = schemes.map { it.displayName }.toTypedArray()
        val currentIndex = schemes.indexOfFirst { it.name == currentColorScheme }

        AlertDialog.Builder(this, R.style.Theme_KidsMovies_Dialog)
            .setTitle(R.string.color_scheme)
            .setSingleChoiceItems(schemeNames, currentIndex) { dialog, which ->
                currentColorScheme = schemes[which].name
                lifecycleScope.launch {
                    app.settingsRepository.setColorScheme(currentColorScheme)
                    updateColorSchemeDisplay()
                    // Clear theme cache and reapply
                    ThemeManager.clearCache()
                    ThemeManager.applyTheme(this@SettingsActivity)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun exportData(uri: Uri) {
        lifecycleScope.launch {
            val result = DatabaseExportImport.exportToFile(this@SettingsActivity, uri)
            result.fold(
                onSuccess = {
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.export_success,
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onFailure = {
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.export_error,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    }

    private fun showImportConfirmation(uri: Uri) {
        AlertDialog.Builder(this, R.style.Theme_KidsMovies_Dialog)
            .setTitle(R.string.import_data)
            .setMessage(R.string.clear_before_import)
            .setPositiveButton(R.string.yes) { _, _ ->
                importData(uri, clearExisting = true)
            }
            .setNegativeButton(R.string.no) { _, _ ->
                importData(uri, clearExisting = false)
            }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    private fun importData(uri: Uri, clearExisting: Boolean) {
        lifecycleScope.launch {
            val result = DatabaseExportImport.importFromFile(
                this@SettingsActivity,
                uri,
                clearExisting
            )
            result.fold(
                onSuccess = { importResult ->
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(
                            R.string.import_success,
                            importResult.videosImported,
                            importResult.tagsImported,
                            importResult.foldersImported
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                    loadSettings()
                },
                onFailure = {
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.import_error,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    }
}
