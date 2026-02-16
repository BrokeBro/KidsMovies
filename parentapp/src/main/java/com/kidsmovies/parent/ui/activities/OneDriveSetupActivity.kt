package com.kidsmovies.parent.ui.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.database.FirebaseDatabase
import com.kidsmovies.parent.ParentApp
import com.kidsmovies.parent.R
import com.kidsmovies.parent.databinding.ActivityOneDriveSetupBinding
import com.kidsmovies.shared.auth.MsalAuthManager
import com.kidsmovies.shared.models.FirebasePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.util.concurrent.TimeUnit

class OneDriveSetupActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FAMILY_ID = "extra_family_id"
    }

    private lateinit var binding: ActivityOneDriveSetupBinding
    private lateinit var app: ParentApp
    private lateinit var msalAuthManager: MsalAuthManager
    private var familyId: String? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    // Browsing state
    private var currentDriveId: String? = null
    private var currentFolderId: String? = null
    private var currentPath: String = "/"
    private var folderStack = mutableListOf<BrowseState>()
    private val folderAdapter = FolderAdapter { item -> onFolderClicked(item) }

    data class BrowseState(val driveId: String, val folderId: String, val path: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOneDriveSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        app = application as ParentApp
        msalAuthManager = app.msalAuthManager
        familyId = intent.getStringExtra(EXTRA_FAMILY_ID)

        setupUI()
        setupListeners()
        checkCurrentState()
    }

    private fun setupUI() {
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.folderRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.folderRecyclerView.adapter = folderAdapter
    }

    private fun setupListeners() {
        binding.signInButton.setOnClickListener { signIn() }
        binding.disconnectButton.setOnClickListener { confirmDisconnect() }
        binding.selectFolderButton.setOnClickListener { selectCurrentFolder() }
        binding.selectDriveButton.setOnClickListener { browseDrives() }
    }

    private fun checkCurrentState() {
        lifecycleScope.launch {
            try {
                msalAuthManager.initialize(R.raw.msal_config)

                if (msalAuthManager.isSignedIn()) {
                    // Check if already configured
                    val prefs = getSharedPreferences("onedrive_config", MODE_PRIVATE)
                    val isConfigured = prefs.getBoolean("is_configured", false)

                    if (isConfigured) {
                        showConnectedState(
                            msalAuthManager.getAccountDisplayName() ?: "Connected",
                            prefs.getString("folder_path", "/") ?: "/"
                        )
                    } else {
                        showSignedInState()
                    }
                } else {
                    showDisconnectedState()
                }
            } catch (e: Exception) {
                showDisconnectedState()
            }
        }
    }

    private fun showDisconnectedState() {
        binding.statusTitle.text = "Not Connected"
        binding.statusMessage.text = "Sign in to connect your OneDrive or SharePoint folder"
        binding.connectedAccount.visibility = View.GONE
        binding.connectedFolder.visibility = View.GONE
        binding.signInButton.visibility = View.VISIBLE
        binding.folderSection.visibility = View.GONE
        binding.disconnectButton.visibility = View.GONE
    }

    private fun showSignedInState() {
        val accountName = msalAuthManager.getAccountDisplayName() ?: "Signed In"
        binding.statusTitle.text = "Signed In"
        binding.statusMessage.text = "Select a folder containing your video files"
        binding.connectedAccount.text = "Account: $accountName"
        binding.connectedAccount.visibility = View.VISIBLE
        binding.connectedFolder.visibility = View.GONE
        binding.signInButton.visibility = View.GONE
        binding.folderSection.visibility = View.VISIBLE
        binding.disconnectButton.visibility = View.VISIBLE
    }

    private fun showConnectedState(account: String, folderPath: String) {
        binding.statusTitle.text = "Connected"
        binding.statusMessage.text = "Videos from OneDrive will appear in the child app"
        binding.connectedAccount.text = "Account: $account"
        binding.connectedAccount.visibility = View.VISIBLE
        binding.connectedFolder.text = "Folder: $folderPath"
        binding.connectedFolder.visibility = View.VISIBLE
        binding.signInButton.visibility = View.GONE
        binding.folderSection.visibility = View.GONE
        binding.disconnectButton.visibility = View.VISIBLE
    }

    private fun signIn() {
        binding.loadingIndicator.visibility = View.VISIBLE
        binding.signInButton.isEnabled = false

        lifecycleScope.launch {
            try {
                val token = msalAuthManager.signIn(this@OneDriveSetupActivity)
                if (token != null) {
                    showSignedInState()
                    browseDrives()
                } else {
                    Snackbar.make(binding.root, "Sign-in cancelled", Snackbar.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Snackbar.make(binding.root, "Sign-in failed: ${e.message}", Snackbar.LENGTH_LONG).show()
            } finally {
                binding.loadingIndicator.visibility = View.GONE
                binding.signInButton.isEnabled = true
            }
        }
    }

    private fun browseDrives() {
        binding.loadingIndicator.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val token = msalAuthManager.acquireTokenSilently()
                    ?: throw Exception("No access token")

                val drives = fetchDrives(token)
                if (drives.isEmpty()) {
                    Snackbar.make(binding.root, "No drives found", Snackbar.LENGTH_SHORT).show()
                    return@launch
                }

                if (drives.size == 1) {
                    // Auto-select single drive
                    val drive = drives[0]
                    currentDriveId = drive.id
                    browseFolder(drive.id, "root", "/")
                } else {
                    // Show drive picker dialog
                    val driveNames = drives.map { "${it.name} (${it.driveType})" }.toTypedArray()
                    MaterialAlertDialogBuilder(this@OneDriveSetupActivity)
                        .setTitle("Select Drive")
                        .setItems(driveNames) { _, which ->
                            val drive = drives[which]
                            currentDriveId = drive.id
                            browseFolder(drive.id, "root", "/")
                        }
                        .show()
                }
            } catch (e: Exception) {
                Snackbar.make(binding.root, "Error: ${e.message}", Snackbar.LENGTH_LONG).show()
            } finally {
                binding.loadingIndicator.visibility = View.GONE
            }
        }
    }

    private fun browseFolder(driveId: String, folderId: String, path: String) {
        binding.loadingIndicator.visibility = View.VISIBLE
        currentFolderId = folderId
        currentPath = path
        binding.currentPath.text = "Path: $path"

        lifecycleScope.launch {
            try {
                val token = msalAuthManager.acquireTokenSilently()
                    ?: throw Exception("No access token")

                val items = fetchFolderContents(token, driveId, folderId)
                val folders = items.filter { it.folder != null }

                folderAdapter.submitList(folders)
            } catch (e: Exception) {
                Snackbar.make(binding.root, "Error browsing: ${e.message}", Snackbar.LENGTH_LONG).show()
            } finally {
                binding.loadingIndicator.visibility = View.GONE
            }
        }
    }

    private fun onFolderClicked(item: FolderItem) {
        val driveId = currentDriveId ?: return
        // Save current state for back navigation
        currentFolderId?.let { fid ->
            folderStack.add(BrowseState(driveId, fid, currentPath))
        }
        val newPath = if (currentPath == "/") "/${item.name}" else "$currentPath/${item.name}"
        browseFolder(driveId, item.id, newPath)
    }

    private fun selectCurrentFolder() {
        val driveId = currentDriveId ?: return
        val folderId = currentFolderId ?: return

        // Save configuration locally
        val prefs = getSharedPreferences("onedrive_config", MODE_PRIVATE)
        prefs.edit()
            .putString("drive_id", driveId)
            .putString("folder_id", folderId)
            .putString("folder_path", currentPath)
            .putBoolean("is_configured", true)
            .apply()

        // Also save to Firebase so child app can read it
        saveConfigToFirebase(driveId, folderId, currentPath)

        showConnectedState(
            msalAuthManager.getAccountDisplayName() ?: "Connected",
            currentPath
        )

        Snackbar.make(binding.root, "OneDrive folder connected!", Snackbar.LENGTH_SHORT).show()
    }

    private fun saveConfigToFirebase(driveId: String, folderId: String, folderPath: String) {
        lifecycleScope.launch {
            try {
                val fid = familyId ?: return@launch
                val database = FirebaseDatabase.getInstance()

                val config = mapOf(
                    "driveId" to driveId,
                    "folderId" to folderId,
                    "folderPath" to folderPath,
                    "configuredAt" to System.currentTimeMillis()
                )

                database.getReference("${FirebasePaths.FAMILIES}/$fid/oneDriveConfig")
                    .setValue(config)
                    .await()
            } catch (e: Exception) {
                // Firebase save is best-effort
            }
        }
    }

    private fun confirmDisconnect() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Disconnect OneDrive")
            .setMessage("This will remove the OneDrive connection. Videos from OneDrive will no longer appear in the child app.")
            .setPositiveButton("Disconnect") { _, _ -> disconnect() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun disconnect() {
        lifecycleScope.launch {
            try {
                msalAuthManager.signOut()
            } catch (e: Exception) {
                // Ignore sign-out errors
            }

            // Clear local config
            getSharedPreferences("onedrive_config", MODE_PRIVATE).edit().clear().apply()

            // Clear Firebase config
            try {
                val fid = familyId ?: return@launch
                FirebaseDatabase.getInstance()
                    .getReference("${FirebasePaths.FAMILIES}/$fid/oneDriveConfig")
                    .removeValue()
                    .await()
            } catch (e: Exception) {
                // Best effort
            }

            showDisconnectedState()
            Snackbar.make(binding.root, "OneDrive disconnected", Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onBackPressed() {
        if (folderStack.isNotEmpty()) {
            val prev = folderStack.removeAt(folderStack.size - 1)
            browseFolder(prev.driveId, prev.folderId, prev.path)
        } else {
            super.onBackPressed()
        }
    }

    // Network helpers
    private suspend fun fetchDrives(token: String): List<DriveInfo> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://graph.microsoft.com/v1.0/me/drives")
            .addHeader("Authorization", "Bearer $token")
            .build()
        val response = httpClient.newCall(request).execute()
        val body = response.body?.string() ?: return@withContext emptyList()
        val result = gson.fromJson(body, DriveListResult::class.java)
        result.value
    }

    private suspend fun fetchFolderContents(token: String, driveId: String, folderId: String): List<FolderItem> = withContext(Dispatchers.IO) {
        val url = if (folderId == "root") {
            "https://graph.microsoft.com/v1.0/drives/$driveId/root/children?\$select=id,name,folder&\$top=200"
        } else {
            "https://graph.microsoft.com/v1.0/drives/$driveId/items/$folderId/children?\$select=id,name,folder&\$top=200"
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .build()
        val response = httpClient.newCall(request).execute()
        val body = response.body?.string() ?: return@withContext emptyList()
        val result = gson.fromJson(body, FolderListResult::class.java)
        result.value
    }

    // Data classes for API responses
    data class DriveListResult(val value: List<DriveInfo> = emptyList())
    data class DriveInfo(val id: String = "", val name: String = "", val driveType: String = "")
    data class FolderListResult(val value: List<FolderItem> = emptyList())
    data class FolderItem(
        val id: String = "",
        val name: String = "",
        val folder: FolderFacet? = null
    )
    data class FolderFacet(val childCount: Int = 0)

    // RecyclerView adapter for folder browsing
    class FolderAdapter(private val onClick: (FolderItem) -> Unit) :
        RecyclerView.Adapter<FolderAdapter.ViewHolder>() {

        private var items: List<FolderItem> = emptyList()

        fun submitList(newItems: List<FolderItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_1, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.bind(item)
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            fun bind(item: FolderItem) {
                (itemView as TextView).text = item.name
                itemView.setOnClickListener { onClick(item) }
            }
        }
    }
}
