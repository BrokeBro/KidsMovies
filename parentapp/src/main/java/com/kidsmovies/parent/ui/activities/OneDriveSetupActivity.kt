package com.kidsmovies.parent.ui.activities

import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
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
        const val MODE_OWN_DRIVE = "own_drive"
        const val MODE_SHARED_AUTH = "shared_authenticated"
        const val MODE_PUBLIC_LINK = "public_link"
        private const val ONEDRIVE_API_BASE = "https://api.onedrive.com/v1.0"
        private const val GRAPH_API_BASE = "https://graph.microsoft.com/v1.0"
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

    // Access mode tracking
    private var accessMode: String = MODE_OWN_DRIVE
    private var encodedShareId: String? = null
    private var shareUrl: String? = null

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
        binding.signInButton.setOnClickListener { startOwnDriveFlow() }
        binding.sharedLinkButton.setOnClickListener { startSharedLinkFlow() }
        binding.publicLinkButton.setOnClickListener { startPublicLinkFlow() }
        binding.disconnectButton.setOnClickListener { confirmDisconnect() }
        binding.selectFolderButton.setOnClickListener { selectCurrentFolder() }
        binding.selectDriveButton.setOnClickListener { browseDrives() }
    }

    // --- State check ---

    private fun checkCurrentState() {
        val prefs = getSharedPreferences("onedrive_config", MODE_PRIVATE)
        val isConfigured = prefs.getBoolean("is_configured", false)
        val storedMode = prefs.getString("access_mode", null)

        if (isConfigured && storedMode != null) {
            accessMode = storedMode
            encodedShareId = prefs.getString("share_encoded_id", null)
            shareUrl = prefs.getString("share_url", null)

            if (storedMode == MODE_PUBLIC_LINK) {
                showConnectedState("Public Link", prefs.getString("folder_path", "/") ?: "/")
                return
            }

            // For auth modes, try to initialize MSAL
            val clientId = getStoredClientId()
            if (clientId != null) {
                lifecycleScope.launch {
                    try {
                        val configFile = MsalAuthManager.generateConfig(
                            this@OneDriveSetupActivity, clientId
                        )
                        msalAuthManager.initialize(configFile)
                        showConnectedState(
                            msalAuthManager.getAccountDisplayName() ?: "Connected",
                            prefs.getString("folder_path", "/") ?: "/"
                        )
                    } catch (e: Exception) {
                        showConnectedState("Connected", prefs.getString("folder_path", "/") ?: "/")
                    }
                }
                return
            }
        }

        showDisconnectedState()
    }

    private fun getStoredClientId(): String? {
        return getSharedPreferences("onedrive_config", MODE_PRIVATE)
            .getString("msal_client_id", null)
    }

    // --- UI States ---

    private fun showDisconnectedState() {
        binding.statusTitle.text = "Not Connected"
        binding.statusMessage.text = "Choose how to connect to OneDrive"
        binding.connectedAccount.visibility = View.GONE
        binding.connectedFolder.visibility = View.GONE
        binding.signInButton.visibility = View.VISIBLE
        binding.sharedLinkButton.visibility = View.VISIBLE
        binding.publicLinkButton.visibility = View.VISIBLE
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
        binding.sharedLinkButton.visibility = View.GONE
        binding.publicLinkButton.visibility = View.GONE
        binding.folderSection.visibility = View.VISIBLE
        binding.disconnectButton.visibility = View.VISIBLE
    }

    private fun showBrowsingState(title: String) {
        binding.statusTitle.text = title
        binding.statusMessage.text = "Select a folder containing your video files"
        binding.connectedAccount.visibility = View.GONE
        binding.connectedFolder.visibility = View.GONE
        binding.signInButton.visibility = View.GONE
        binding.sharedLinkButton.visibility = View.GONE
        binding.publicLinkButton.visibility = View.GONE
        binding.folderSection.visibility = View.VISIBLE
        binding.disconnectButton.visibility = View.VISIBLE
    }

    private fun showConnectedState(account: String, folderPath: String) {
        binding.statusTitle.text = "Connected"
        binding.statusMessage.text = "Videos from OneDrive will appear in the child app"
        binding.connectedAccount.text = "Source: $account"
        binding.connectedAccount.visibility = View.VISIBLE
        binding.connectedFolder.text = "Folder: $folderPath"
        binding.connectedFolder.visibility = View.VISIBLE
        binding.signInButton.visibility = View.GONE
        binding.sharedLinkButton.visibility = View.GONE
        binding.publicLinkButton.visibility = View.GONE
        binding.folderSection.visibility = View.GONE
        binding.disconnectButton.visibility = View.VISIBLE
    }

    // --- Flow 1: Sign in & Browse Own OneDrive ---

    private fun startOwnDriveFlow() {
        accessMode = MODE_OWN_DRIVE
        val clientId = getStoredClientId()
        if (clientId == null) {
            promptForClientId { signIn() }
        } else {
            lifecycleScope.launch {
                try {
                    val configFile = MsalAuthManager.generateConfig(
                        this@OneDriveSetupActivity, clientId
                    )
                    msalAuthManager.initialize(configFile)
                    signIn()
                } catch (e: Exception) {
                    Snackbar.make(binding.root, "MSAL init failed: ${e.message}", Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun promptForClientId(onComplete: () -> Unit) {
        val hash = MsalAuthManager.getSignatureHash(this)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val smallPad = (8 * resources.displayMetrics.density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, smallPad, pad, 0)
        }

        val infoText = TextView(this).apply {
            text = "To connect OneDrive, register an app at portal.azure.com:\n\n" +
                "1. App registrations > New registration\n" +
                "2. Add Android platform redirect URI:\n\n" +
                "Package: $packageName\n" +
                "Hash: $hash\n\n" +
                "3. API permissions: Files.Read.All, Sites.Read.All\n\n" +
                "Then paste your Application (Client) ID below:"
            setTextIsSelectable(true)
            textSize = 14f
        }
        container.addView(infoText)

        val input = EditText(this).apply {
            hint = "e.g. 12345678-abcd-efgh-ijkl-1234567890ab"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        container.addView(input)

        MaterialAlertDialogBuilder(this)
            .setTitle("Azure App Setup")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val clientId = input.text.toString().trim()
                if (clientId.isNotEmpty()) {
                    getSharedPreferences("onedrive_config", MODE_PRIVATE).edit()
                        .putString("msal_client_id", clientId)
                        .apply()
                    lifecycleScope.launch {
                        try {
                            val configFile = MsalAuthManager.generateConfig(
                                this@OneDriveSetupActivity, clientId
                            )
                            msalAuthManager.initialize(configFile)
                            onComplete()
                        } catch (e: Exception) {
                            Snackbar.make(binding.root, "MSAL init failed: ${e.message}", Snackbar.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    // --- Flow 2: Shared Link (Authenticated) ---

    private fun startSharedLinkFlow() {
        accessMode = MODE_SHARED_AUTH
        val clientId = getStoredClientId()
        if (clientId == null) {
            promptForClientId {
                signInThenPromptForLink()
            }
        } else {
            lifecycleScope.launch {
                try {
                    val configFile = MsalAuthManager.generateConfig(
                        this@OneDriveSetupActivity, clientId
                    )
                    msalAuthManager.initialize(configFile)
                    signInThenPromptForLink()
                } catch (e: Exception) {
                    Snackbar.make(binding.root, "MSAL init failed: ${e.message}", Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun signInThenPromptForLink() {
        if (msalAuthManager.isSignedIn()) {
            promptForLink(useAuth = true)
            return
        }

        binding.loadingIndicator.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val token = msalAuthManager.signIn(this@OneDriveSetupActivity)
                if (token != null) {
                    promptForLink(useAuth = true)
                } else {
                    Snackbar.make(binding.root, "Sign-in cancelled", Snackbar.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Snackbar.make(binding.root, "Sign-in failed: ${e.message}", Snackbar.LENGTH_LONG).show()
            } finally {
                binding.loadingIndicator.visibility = View.GONE
            }
        }
    }

    // --- Flow 3: Public Link (No Auth) ---

    private fun startPublicLinkFlow() {
        accessMode = MODE_PUBLIC_LINK
        promptForLink(useAuth = false)
    }

    // --- Shared: Link Prompt & Resolution ---

    private fun promptForLink(useAuth: Boolean) {
        val pad = (24 * resources.displayMetrics.density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 3, pad, 0)
        }

        val infoText = TextView(this).apply {
            text = if (useAuth) {
                "Paste the OneDrive sharing link you received:"
            } else {
                "Paste a public OneDrive link (must be set to \"Anyone with the link\"):"
            }
            textSize = 14f
        }
        container.addView(infoText)

        val input = EditText(this).apply {
            hint = "https://1drv.ms/f/s!..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        container.addView(input)

        MaterialAlertDialogBuilder(this)
            .setTitle(if (useAuth) "Shared Link" else "Public Link")
            .setView(container)
            .setPositiveButton("Connect") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    resolveSharedLink(url, useAuth)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun encodeSharingUrl(url: String): String {
        val base64 = Base64.encodeToString(url.toByteArray(), Base64.NO_WRAP)
        return "u!" + base64.trimEnd('=').replace('/', '_').replace('+', '-')
    }

    private fun resolveSharedLink(url: String, useAuth: Boolean) {
        binding.loadingIndicator.visibility = View.VISIBLE
        shareUrl = url
        val encoded = encodeSharingUrl(url)
        encodedShareId = encoded

        lifecycleScope.launch {
            try {
                val item = fetchSharedDriveItem(encoded, useAuth)
                if (item == null) {
                    Snackbar.make(binding.root, "Could not resolve link. Check the URL and try again.", Snackbar.LENGTH_LONG).show()
                    return@launch
                }

                // Extract drive info from the response
                val driveId = item.parentReference?.driveId
                val folderId = item.id

                if (driveId != null && folderId.isNotEmpty()) {
                    currentDriveId = driveId
                    val title = if (useAuth) "Shared Folder" else "Public Folder"
                    showBrowsingState(title)
                    browseFolderViaShares(encoded, folderId, "/${item.name}", useAuth)
                } else {
                    Snackbar.make(binding.root, "Could not read folder info from link", Snackbar.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Snackbar.make(binding.root, "Error: ${e.message}", Snackbar.LENGTH_LONG).show()
            } finally {
                binding.loadingIndicator.visibility = View.GONE
            }
        }
    }

    // --- Browsing ---

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
                    val drive = drives[0]
                    currentDriveId = drive.id
                    browseFolder(drive.id, "root", "/")
                } else {
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

    private fun browseFolderViaShares(encoded: String, folderId: String, path: String, useAuth: Boolean) {
        binding.loadingIndicator.visibility = View.VISIBLE
        currentFolderId = folderId
        currentPath = path
        binding.currentPath.text = "Path: $path"

        lifecycleScope.launch {
            try {
                val items = fetchSharedFolderContents(encoded, folderId, useAuth)
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
        currentFolderId?.let { fid ->
            val driveId = currentDriveId ?: ""
            folderStack.add(BrowseState(driveId, fid, currentPath))
        }
        val newPath = if (currentPath == "/") "/${item.name}" else "$currentPath/${item.name}"

        if (accessMode == MODE_OWN_DRIVE) {
            val driveId = currentDriveId ?: return
            browseFolder(driveId, item.id, newPath)
        } else {
            val encoded = encodedShareId ?: return
            browseFolderViaShares(encoded, item.id, newPath, accessMode == MODE_SHARED_AUTH)
        }
    }

    private fun selectCurrentFolder() {
        val driveId = currentDriveId ?: ""
        val folderId = currentFolderId ?: return

        // Save configuration locally
        val prefs = getSharedPreferences("onedrive_config", MODE_PRIVATE)
        prefs.edit()
            .putString("drive_id", driveId)
            .putString("folder_id", folderId)
            .putString("folder_path", currentPath)
            .putString("access_mode", accessMode)
            .putString("share_encoded_id", encodedShareId)
            .putString("share_url", shareUrl)
            .putBoolean("is_configured", true)
            .apply()

        // Also save to Firebase so child app can read it
        saveConfigToFirebase(driveId, folderId, currentPath)

        val accountLabel = when (accessMode) {
            MODE_PUBLIC_LINK -> "Public Link"
            MODE_SHARED_AUTH -> msalAuthManager.getAccountDisplayName() ?: "Shared Link"
            else -> msalAuthManager.getAccountDisplayName() ?: "Connected"
        }

        showConnectedState(accountLabel, currentPath)
        Snackbar.make(binding.root, "OneDrive folder connected!", Snackbar.LENGTH_SHORT).show()
    }

    private fun saveConfigToFirebase(driveId: String, folderId: String, folderPath: String) {
        lifecycleScope.launch {
            try {
                val fid = familyId ?: return@launch
                val database = FirebaseDatabase.getInstance()

                val config = mutableMapOf<String, Any?>(
                    "driveId" to driveId,
                    "folderId" to folderId,
                    "folderPath" to folderPath,
                    "accessMode" to accessMode,
                    "configuredAt" to System.currentTimeMillis()
                )

                // Include share info for shared/public modes
                encodedShareId?.let { config["shareEncodedId"] = it }
                shareUrl?.let { config["shareUrl"] = it }

                // Include client ID for authenticated modes
                if (accessMode != MODE_PUBLIC_LINK) {
                    getStoredClientId()?.let { config["clientId"] = it }
                }

                database.getReference("${FirebasePaths.FAMILIES}/$fid/oneDriveConfig")
                    .setValue(config)
                    .await()
            } catch (e: Exception) {
                // Firebase save is best-effort
            }
        }
    }

    // --- Disconnect ---

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

            // Clear local config (preserve client_id for reconnect)
            getSharedPreferences("onedrive_config", MODE_PRIVATE).edit()
                .remove("drive_id")
                .remove("folder_id")
                .remove("folder_path")
                .remove("is_configured")
                .remove("access_mode")
                .remove("share_encoded_id")
                .remove("share_url")
                .apply()

            // Reset state
            accessMode = MODE_OWN_DRIVE
            encodedShareId = null
            shareUrl = null

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
            if (accessMode == MODE_OWN_DRIVE) {
                browseFolder(prev.driveId, prev.folderId, prev.path)
            } else {
                val encoded = encodedShareId ?: return
                browseFolderViaShares(encoded, prev.folderId, prev.path, accessMode == MODE_SHARED_AUTH)
            }
        } else {
            super.onBackPressed()
        }
    }

    // --- Network helpers ---

    private suspend fun fetchDrives(token: String): List<DriveInfo> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$GRAPH_API_BASE/me/drives")
            .addHeader("Authorization", "Bearer $token")
            .build()
        val response = httpClient.newCall(request).execute()
        val body = response.body?.string() ?: return@withContext emptyList()
        val result = gson.fromJson(body, DriveListResult::class.java)
        result.value
    }

    private suspend fun fetchFolderContents(token: String, driveId: String, folderId: String): List<FolderItem> = withContext(Dispatchers.IO) {
        val url = if (folderId == "root") {
            "$GRAPH_API_BASE/drives/$driveId/root/children?\$select=id,name,folder&\$top=200"
        } else {
            "$GRAPH_API_BASE/drives/$driveId/items/$folderId/children?\$select=id,name,folder&\$top=200"
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

    private suspend fun fetchSharedDriveItem(encoded: String, useAuth: Boolean): SharedDriveItem? = withContext(Dispatchers.IO) {
        val baseUrl = if (useAuth) GRAPH_API_BASE else ONEDRIVE_API_BASE
        val url = "$baseUrl/shares/$encoded/driveItem?\$select=id,name,folder,parentReference"

        val requestBuilder = Request.Builder().url(url)
        if (useAuth) {
            val token = msalAuthManager.acquireTokenSilently() ?: return@withContext null
            requestBuilder.addHeader("Authorization", "Bearer $token")
        }

        val response = httpClient.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful) return@withContext null
        val body = response.body?.string() ?: return@withContext null
        gson.fromJson(body, SharedDriveItem::class.java)
    }

    private suspend fun fetchSharedFolderContents(encoded: String, folderId: String, useAuth: Boolean): List<FolderItem> = withContext(Dispatchers.IO) {
        val baseUrl = if (useAuth) GRAPH_API_BASE else ONEDRIVE_API_BASE
        val url = "$baseUrl/shares/$encoded/items/$folderId/children?\$select=id,name,folder&\$top=200"

        val requestBuilder = Request.Builder().url(url)
        if (useAuth) {
            val token = msalAuthManager.acquireTokenSilently() ?: return@withContext emptyList()
            requestBuilder.addHeader("Authorization", "Bearer $token")
        }

        val response = httpClient.newCall(requestBuilder.build()).execute()
        val body = response.body?.string() ?: return@withContext emptyList()
        val result = gson.fromJson(body, FolderListResult::class.java)
        result.value
    }

    // --- Data classes ---

    data class DriveListResult(val value: List<DriveInfo> = emptyList())
    data class DriveInfo(val id: String = "", val name: String = "", val driveType: String = "")
    data class FolderListResult(val value: List<FolderItem> = emptyList())
    data class FolderItem(
        val id: String = "",
        val name: String = "",
        val folder: FolderFacet? = null
    )
    data class FolderFacet(val childCount: Int = 0)

    data class SharedDriveItem(
        val id: String = "",
        val name: String = "",
        val folder: FolderFacet? = null,
        val parentReference: SharedParentReference? = null
    )
    data class SharedParentReference(
        val driveId: String? = null,
        val id: String? = null
    )

    // --- Adapter ---

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
