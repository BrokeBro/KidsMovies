package com.kidsmovies.parent.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.kidsmovies.parent.ParentApp
import com.kidsmovies.parent.R
import com.kidsmovies.parent.databinding.ActivityMainBinding
import com.kidsmovies.parent.firebase.ChildDevice
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

        setupUI()
        setupListeners()

        if (app.firebaseInitialized) {
            signInAnonymouslyAndLoadFamily()
        } else {
            showFirebaseError()
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
