package com.kidsmovies.app.ui.activities

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.kidsmovies.app.KidsMoviesApp
import com.kidsmovies.app.R
import com.kidsmovies.app.databinding.ActivityMainBinding
import com.kidsmovies.app.enforcement.LockScreenActivity
import com.kidsmovies.app.enforcement.ViewingTimerManager
import com.kidsmovies.app.services.VideoScannerService
import com.kidsmovies.app.ui.fragments.AllVideosFragment
import com.kidsmovies.app.ui.fragments.CollectionsFragment
import com.kidsmovies.app.ui.fragments.FavouritesFragment
import com.kidsmovies.app.ui.fragments.OnlineVideosFragment
import com.kidsmovies.app.ui.fragments.RecentVideosFragment
import com.kidsmovies.app.utils.ThemeManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var app: KidsMoviesApp
    private var isSearchVisible = false
    private var visibleTabs = mutableListOf<TabItem>()
    private var tabLayoutMediator: TabLayoutMediator? = null

    private data class TabItem(
        val type: TabType,
        val titleResId: Int,
        val iconResId: Int,
        val fragmentFactory: () -> Fragment
    )

    private enum class TabType {
        ALL_MOVIES, FAVOURITES, COLLECTIONS, RECENT, ONLINE
    }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                VideoScannerService.ACTION_SCAN_COMPLETE -> {
                    binding.loadingOverlay.visibility = View.GONE
                    val added = intent.getIntExtra(VideoScannerService.EXTRA_VIDEOS_ADDED, 0)
                    val removed = intent.getIntExtra(VideoScannerService.EXTRA_VIDEOS_REMOVED, 0)

                    if (added > 0 || removed > 0) {
                        val message = buildString {
                            if (added > 0) append(getString(R.string.videos_added, added))
                            if (added > 0 && removed > 0) append("\n")
                            if (removed > 0) append(getString(R.string.videos_removed, removed))
                        }
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                    }
                }
                VideoScannerService.ACTION_SCAN_PROGRESS -> {
                    binding.loadingOverlay.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        app = application as KidsMoviesApp

        setupUI()
        setupListeners()
        loadGreeting()
        loadTabsFromSettings()
        observeViewingState()

        // Register scan receiver
        val filter = IntentFilter().apply {
            addAction(VideoScannerService.ACTION_SCAN_COMPLETE)
            addAction(VideoScannerService.ACTION_SCAN_PROGRESS)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(scanReceiver, filter)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            ThemeManager.applyTheme(this@MainActivity)
            // Re-evaluate viewing state
            app.viewingTimerManager.evaluate()
        }
        // Reload tabs in case settings changed
        loadTabsFromSettings()
        // Check if we should show lock screen
        checkViewingState()
    }

    private fun observeViewingState() {
        lifecycleScope.launch {
            app.viewingTimerManager.timerState.collectLatest { state ->
                when (state.state) {
                    ViewingTimerManager.ViewingState.LOCKED -> {
                        // Check if paired - only show lock screen if paired and locked
                        val isPaired = app.pairingRepository.isPaired()
                        if (isPaired) {
                            showLockScreen()
                        }
                    }
                    else -> {
                        // App is usable
                    }
                }
            }
        }
    }

    private fun checkViewingState() {
        lifecycleScope.launch {
            val isPaired = app.pairingRepository.isPaired()
            if (isPaired) {
                val state = app.viewingTimerManager.timerState.value
                if (state.state == ViewingTimerManager.ViewingState.LOCKED) {
                    showLockScreen()
                }
            }
        }
    }

    private fun showLockScreen() {
        val intent = Intent(this, LockScreenActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(scanReceiver)
    }

    private fun setupUI() {
        binding.searchInputLayout.visibility = View.GONE
    }

    private fun loadTabsFromSettings() {
        lifecycleScope.launch {
            val settings = app.settingsRepository.getSettings()
            val tabOrder = app.settingsRepository.getTabOrder().split(",")

            // Map tab IDs to TabType
            val tabIdToType = mapOf(
                "all_movies" to TabType.ALL_MOVIES,
                "favourites" to TabType.FAVOURITES,
                "collections" to TabType.COLLECTIONS,
                "recent" to TabType.RECENT,
                "online" to TabType.ONLINE
            )

            // Map TabType to TabItem
            val allTabsMap = mapOf(
                TabType.ALL_MOVIES to TabItem(TabType.ALL_MOVIES, R.string.all_movies, R.drawable.ic_movie) { AllVideosFragment() },
                TabType.FAVOURITES to TabItem(TabType.FAVOURITES, R.string.my_favourites, R.drawable.ic_favourite) { FavouritesFragment() },
                TabType.COLLECTIONS to TabItem(TabType.COLLECTIONS, R.string.collections, R.drawable.ic_collections) { CollectionsFragment() },
                TabType.RECENT to TabItem(TabType.RECENT, R.string.recently_watched, R.drawable.ic_history) { RecentVideosFragment() },
                TabType.ONLINE to TabItem(TabType.ONLINE, R.string.online_videos, R.drawable.ic_cloud) { OnlineVideosFragment() }
            )

            // Map tab visibility settings
            val tabVisibility = mapOf(
                TabType.ALL_MOVIES to (settings?.showAllMoviesTab ?: true),
                TabType.FAVOURITES to (settings?.showFavouritesTab ?: true),
                TabType.COLLECTIONS to (settings?.showCollectionsTab ?: true),
                TabType.RECENT to (settings?.showRecentTab ?: true),
                TabType.ONLINE to (settings?.showOnlineTab ?: true)
            )

            // Build tabs in the saved order, filtering by visibility
            val newVisibleTabs = tabOrder.mapNotNull { tabId ->
                val tabType = tabIdToType[tabId] ?: return@mapNotNull null
                if (tabVisibility[tabType] == true) {
                    allTabsMap[tabType]
                } else {
                    null
                }
            }

            // Only update if tabs changed
            if (visibleTabs.map { it.type } != newVisibleTabs.map { it.type }) {
                visibleTabs.clear()
                visibleTabs.addAll(newVisibleTabs)
                setupViewPager()
            }
        }
    }

    private fun setupViewPager() {
        // Detach existing mediator
        tabLayoutMediator?.detach()

        binding.viewPager.adapter = MainPagerAdapter(this)

        tabLayoutMediator = TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            if (position < visibleTabs.size) {
                tab.text = getString(visibleTabs[position].titleResId)
                tab.setIcon(visibleTabs[position].iconResId)
            }
        }
        tabLayoutMediator?.attach()
    }

    private fun setupListeners() {
        binding.searchButton.setOnClickListener {
            toggleSearch()
        }

        binding.refreshButton.setOnClickListener {
            refreshVideos()
        }

        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch(binding.searchInput.text?.toString() ?: "")
                true
            } else {
                false
            }
        }
    }

    private fun loadGreeting() {
        lifecycleScope.launch {
            val settings = app.settingsRepository.getSettings()
            val name = settings?.childName ?: ""
            binding.greetingText.text = if (name.isNotBlank()) {
                "Hi, $name!"
            } else {
                getString(R.string.app_name)
            }
        }
    }

    private fun toggleSearch() {
        isSearchVisible = !isSearchVisible
        binding.searchInputLayout.visibility = if (isSearchVisible) View.VISIBLE else View.GONE

        if (!isSearchVisible) {
            binding.searchInput.text?.clear()
            // Clear search in fragments
            notifySearchCleared()
        } else {
            binding.searchInput.requestFocus()
        }
    }

    private fun refreshVideos() {
        binding.loadingOverlay.visibility = View.VISIBLE
        VideoScannerService.startScan(this)
    }

    private fun performSearch(query: String) {
        val currentFragment = supportFragmentManager.findFragmentByTag("f${binding.viewPager.currentItem}")
        if (currentFragment is AllVideosFragment) {
            currentFragment.filterByQuery(query)
        }
    }

    private fun notifySearchCleared() {
        val currentFragment = supportFragmentManager.findFragmentByTag("f${binding.viewPager.currentItem}")
        if (currentFragment is AllVideosFragment) {
            currentFragment.clearFilter()
        }
    }

    private inner class MainPagerAdapter(activity: AppCompatActivity) :
        FragmentStateAdapter(activity) {

        override fun getItemCount(): Int = visibleTabs.size

        override fun createFragment(position: Int): Fragment {
            return if (position < visibleTabs.size) {
                visibleTabs[position].fragmentFactory()
            } else {
                AllVideosFragment()
            }
        }
    }
}
