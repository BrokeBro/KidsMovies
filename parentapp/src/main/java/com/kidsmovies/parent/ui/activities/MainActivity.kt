package com.kidsmovies.parent.ui.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.kidsmovies.parent.ParentApp
import com.kidsmovies.parent.R
import com.kidsmovies.parent.databinding.ActivityMainBinding
import com.kidsmovies.parent.firebase.ChildDevice
import com.kidsmovies.parent.firebase.JoinFamilyResult
import com.kidsmovies.parent.ui.adapters.ChildrenAdapter
import com.kidsmovies.shared.models.Family
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var app: ParentApp
    private lateinit var adapter: ChildrenAdapter

    private var family: Family? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        app = application as ParentApp

        setSupportActionBar(binding.toolbar)

        setupUI()
        setupListeners()

        if (app.firebaseInitialized) {
            signInAnonymouslyAndLoadFamily()
        } else {
            showFirebaseError()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_onedrive -> {
                family?.let { f ->
                    val intent = Intent(this, OneDriveSetupActivity::class.java)
                    intent.putExtra(OneDriveSetupActivity.EXTRA_FAMILY_ID, f.familyId)
                    startActivity(intent)
                }
                true
            }
            R.id.action_invite_parent -> {
                family?.let { showInviteParentDialog(it) }
                true
            }
            R.id.action_join_family -> {
                showJoinFamilyDialog()
                true
            }
            R.id.action_donate -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.kofi_url)))
                startActivity(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showFirebaseError() {
        binding.childrenRecyclerView.visibility = View.GONE
        binding.emptyState.visibility = View.VISIBLE
        binding.emptyStateHint.text = "Firebase not configured.\n\nTo use this app, add your google-services.json file to the parentapp folder."
        binding.addChildFab.isEnabled = false
    }

    private fun setupUI() {
        adapter = ChildrenAdapter(
            onChildClick = { child -> openChildDetail(child) },
            onRemoveChild = { child -> confirmRemoveChild(child) }
        )

        binding.childrenRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        binding.swipeRefresh.setColorSchemeResources(R.color.primary)
    }

    private fun setupListeners() {
        binding.addChildFab.setOnClickListener {
            family?.let { f ->
                val intent = Intent(this, PairingActivity::class.java)
                intent.putExtra(PairingActivity.EXTRA_FAMILY_ID, f.familyId)
                startActivity(intent)
            }
        }

        binding.swipeRefresh.setOnRefreshListener {
            family?.let { f ->
                // Re-observe children to get fresh data
                observeChildren(f.familyId)
            }
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun signInAnonymouslyAndLoadFamily() {
        val auth = FirebaseAuth.getInstance()

        if (auth.currentUser != null) {
            loadFamily()
        } else {
            // Sign in anonymously for now (can be enhanced with proper auth later)
            auth.signInAnonymously()
                .addOnSuccessListener {
                    loadFamily()
                }
                .addOnFailureListener { e ->
                    showError(getString(R.string.error_not_signed_in))
                }
        }
    }

    private fun loadFamily() {
        lifecycleScope.launch {
            try {
                family = app.familyManager.getOrCreateFamily()
                family?.let { f ->
                    observeChildren(f.familyId)
                }
            } catch (e: Exception) {
                showError(getString(R.string.error_generic))
            }
        }
    }

    private fun observeChildren(familyId: String) {
        lifecycleScope.launch {
            app.familyManager.getChildrenFlow(familyId).collectLatest { children ->
                updateUI(children)
            }
        }
    }

    private fun updateUI(children: List<ChildDevice>) {
        if (children.isEmpty()) {
            binding.childrenRecyclerView.visibility = View.GONE
            binding.emptyState.visibility = View.VISIBLE
        } else {
            binding.childrenRecyclerView.visibility = View.VISIBLE
            binding.emptyState.visibility = View.GONE
            adapter.submitList(children)
        }
    }

    private fun openChildDetail(child: ChildDevice) {
        val intent = Intent(this, ChildDetailActivity::class.java)
        intent.putExtra(ChildDetailActivity.EXTRA_FAMILY_ID, child.familyId)
        intent.putExtra(ChildDetailActivity.EXTRA_CHILD_UID, child.childUid)
        intent.putExtra(ChildDetailActivity.EXTRA_CHILD_NAME, child.displayName)
        startActivity(intent)
    }

    private fun confirmRemoveChild(child: ChildDevice) {
        AlertDialog.Builder(this, R.style.Theme_KidsMoviesParent_Dialog)
            .setTitle(R.string.remove_child)
            .setMessage(getString(R.string.remove_child_message, child.displayName))
            .setPositiveButton(R.string.confirm) { _, _ ->
                removeChild(child)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun removeChild(child: ChildDevice) {
        lifecycleScope.launch {
            try {
                app.familyManager.removeChild(child.familyId, child.childUid)
                showMessage(getString(R.string.child_removed))
            } catch (e: Exception) {
                showError(getString(R.string.error_generic))
            }
        }
    }

    private fun showInviteParentDialog(family: Family) {
        lifecycleScope.launch {
            try {
                val joinCode = app.familyManager.generateFamilyJoinCode(family.familyId)
                if (joinCode == null) {
                    showError(getString(R.string.error_generic))
                    return@launch
                }

                AlertDialog.Builder(this@MainActivity, R.style.Theme_KidsMoviesParent_Dialog)
                    .setTitle(R.string.invite_parent_title)
                    .setMessage("${getString(R.string.invite_parent_instructions)}\n\nCode: ${joinCode.code}")
                    .setPositiveButton(R.string.share_code) { _, _ ->
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, getString(R.string.share_join_code_message, joinCode.code))
                        }
                        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_code)))
                    }
                    .setNegativeButton(R.string.ok, null)
                    .show()
            } catch (e: Exception) {
                showError(getString(R.string.error_generic))
            }
        }
    }

    private fun showJoinFamilyDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.join_code_label)
            setPadding(48, 32, 48, 32)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            maxLines = 1
        }

        AlertDialog.Builder(this, R.style.Theme_KidsMoviesParent_Dialog)
            .setTitle(R.string.join_family_title)
            .setMessage(R.string.join_family_instructions)
            .setView(input)
            .setPositiveButton(R.string.join_button) { _, _ ->
                val code = input.text.toString().trim()
                if (code.length == 6) {
                    joinFamily(code)
                } else {
                    showError(getString(R.string.join_code_invalid))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun joinFamily(code: String) {
        lifecycleScope.launch {
            try {
                when (val result = app.familyManager.joinFamilyWithCode(code)) {
                    is JoinFamilyResult.Success -> {
                        Toast.makeText(this@MainActivity, R.string.join_success, Toast.LENGTH_SHORT).show()
                        // Reload family to show the joined family's children
                        loadFamily()
                    }
                    is JoinFamilyResult.CodeInvalid -> {
                        showError(getString(R.string.join_code_invalid))
                    }
                    is JoinFamilyResult.CodeExpired -> {
                        showError(getString(R.string.join_code_expired))
                    }
                    is JoinFamilyResult.Error -> {
                        showError(result.message)
                    }
                }
            } catch (e: Exception) {
                showError(getString(R.string.error_generic))
            }
        }
    }

    private fun showError(message: String) {
        com.google.android.material.snackbar.Snackbar.make(
            binding.root,
            message,
            com.google.android.material.snackbar.Snackbar.LENGTH_LONG
        ).show()
    }

    private fun showMessage(message: String) {
        com.google.android.material.snackbar.Snackbar.make(
            binding.root,
            message,
            com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
        ).show()
    }
}
