package com.kidsmovies.app.ui.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kidsmovies.app.KidsMoviesApp
import com.kidsmovies.app.R
import com.kidsmovies.app.data.database.entities.ScanFolder
import com.kidsmovies.app.databinding.ActivityFolderPickerBinding
import com.kidsmovies.app.databinding.ItemFolderBinding
import com.kidsmovies.app.utils.FileUtils
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class FolderPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFolderPickerBinding
    private lateinit var app: KidsMoviesApp
    private lateinit var folderAdapter: FolderAdapter

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { handleFolderSelected(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFolderPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        app = application as KidsMoviesApp

        setupToolbar()
        setupRecyclerView()
        setupFab()
        observeFolders()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupRecyclerView() {
        folderAdapter = FolderAdapter(
            onEnableChanged = { folder, enabled ->
                lifecycleScope.launch {
                    app.settingsRepository.setFolderEnabled(folder.id, enabled)
                }
            },
            onSubfoldersChanged = { folder, include ->
                lifecycleScope.launch {
                    app.settingsRepository.updateFolder(folder.copy(includeSubfolders = include))
                }
            },
            onDeleteClick = { folder ->
                lifecycleScope.launch {
                    // Delete videos from this folder first
                    if (folder.includeSubfolders) {
                        app.videoRepository.deleteVideosByFolderPathPrefix(folder.path)
                    } else {
                        app.videoRepository.deleteVideosByFolderPath(folder.path)
                    }
                    // Then delete the folder entry
                    app.settingsRepository.deleteFolder(folder)
                }
            }
        )

        binding.foldersRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@FolderPickerActivity)
            adapter = folderAdapter
        }
    }

    private fun setupFab() {
        binding.addFolderFab.setOnClickListener {
            // Open folder picker
            val initialUri = Uri.parse("content://com.android.externalstorage.documents/document/primary:")
            folderPickerLauncher.launch(initialUri)
        }
    }

    private fun observeFolders() {
        lifecycleScope.launch {
            app.settingsRepository.getAllFoldersFlow().collectLatest { folders ->
                folderAdapter.submitList(folders)

                if (folders.isEmpty()) {
                    binding.emptyState.visibility = View.VISIBLE
                    binding.foldersRecyclerView.visibility = View.GONE
                } else {
                    binding.emptyState.visibility = View.GONE
                    binding.foldersRecyclerView.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun handleFolderSelected(uri: Uri) {
        // Get the actual path from the URI
        val path = getPathFromUri(uri)
        if (path != null) {
            lifecycleScope.launch {
                if (!app.settingsRepository.folderExists(path)) {
                    val folder = ScanFolder(
                        path = path,
                        name = FileUtils.getFolderName(path),
                        includeSubfolders = true,
                        isEnabled = true
                    )
                    app.settingsRepository.addFolder(folder)
                }
            }
        }
    }

    private fun getPathFromUri(uri: Uri): String? {
        // Try to get the path from the URI
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val parts = docId.split(":")
        return if (parts.size >= 2 && parts[0] == "primary") {
            Environment.getExternalStorageDirectory().absolutePath + "/" + parts[1]
        } else if (parts.isNotEmpty()) {
            "/storage/${parts[0]}/${parts.getOrElse(1) { "" }}"
        } else {
            null
        }
    }

    private inner class FolderAdapter(
        private val onEnableChanged: (ScanFolder, Boolean) -> Unit,
        private val onSubfoldersChanged: (ScanFolder, Boolean) -> Unit,
        private val onDeleteClick: (ScanFolder) -> Unit
    ) : RecyclerView.Adapter<FolderAdapter.ViewHolder>() {

        private var folders = listOf<ScanFolder>()

        fun submitList(list: List<ScanFolder>) {
            folders = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemFolderBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(folders[position])
        }

        override fun getItemCount(): Int = folders.size

        inner class ViewHolder(private val binding: ItemFolderBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(folder: ScanFolder) {
                binding.folderName.text = folder.name
                binding.folderPath.text = folder.path
                binding.enabledSwitch.isChecked = folder.isEnabled
                binding.includeSubfoldersChip.isChecked = folder.includeSubfolders

                binding.enabledSwitch.setOnCheckedChangeListener { _, isChecked ->
                    onEnableChanged(folder, isChecked)
                }

                binding.includeSubfoldersChip.setOnCheckedChangeListener { _, isChecked ->
                    onSubfoldersChanged(folder, isChecked)
                }

                binding.deleteButton.setOnClickListener {
                    onDeleteClick(folder)
                }
            }
        }
    }
}
