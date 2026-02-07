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
import com.kidsmovies.app.KidsMoviesApp
import com.kidsmovies.app.R
import com.kidsmovies.app.databinding.ActivitySettingsBinding
import com.kidsmovies.app.services.VideoScannerService
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

    private var currentColorScheme = "blue"
    private var currentGridColumns = 4

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
        loadSettings()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            ThemeManager.applyTheme(this@SettingsActivity)
        }
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

                // Load navigation tab visibility settings
                binding.showAllMoviesCheckbox.isChecked = it.showAllMoviesTab
                binding.showFavouritesCheckbox.isChecked = it.showFavouritesTab
                binding.showCollectionsCheckbox.isChecked = it.showCollectionsTab
                binding.showRecentCheckbox.isChecked = it.showRecentTab
                binding.showOnlineCheckbox.isChecked = it.showOnlineTab
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

        // Navigation tab visibility checkboxes
        binding.showAllMoviesCheckbox.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                app.settingsRepository.setShowAllMoviesTab(isChecked)
            }
        }

        binding.showFavouritesCheckbox.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                app.settingsRepository.setShowFavouritesTab(isChecked)
            }
        }

        binding.showCollectionsCheckbox.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                app.settingsRepository.setShowCollectionsTab(isChecked)
            }
        }

        binding.showRecentCheckbox.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                app.settingsRepository.setShowRecentTab(isChecked)
            }
        }

        binding.showOnlineCheckbox.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                app.settingsRepository.setShowOnlineTab(isChecked)
            }
        }
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
