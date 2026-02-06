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
import com.kidsmovies.app.services.VideoScannerService
import com.kidsmovies.app.ui.fragments.AllVideosFragment
import com.kidsmovies.app.ui.fragments.CollectionsFragment
import com.kidsmovies.app.ui.fragments.FavouritesFragment
import com.kidsmovies.app.ui.fragments.OnlineVideosFragment
import com.kidsmovies.app.ui.fragments.RecentVideosFragment
import com.kidsmovies.app.utils.ThemeManager
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
        }
        // Reload tabs in case settings changed
        loadTabsFromSettings()
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

            val allTabs = listOf(
                TabItem(TabType.ALL_MOVIES, R.string.all_movies, R.drawable.ic_movie) { AllVideosFragment() },
                TabItem(TabType.FAVOURITES, R.string.my_favourites, R.drawable.ic_favourite) { FavouritesFragment() },
                TabItem(TabType.COLLECTIONS, R.string.collections, R.drawable.ic_collections) { CollectionsFragment() },
                TabItem(TabType.RECENT, R.string.recently_watched, R.drawable.ic_history) { RecentVideosFragment() },
                TabItem(TabType.ONLINE, R.string.online_videos, R.drawable.ic_cloud) { OnlineVideosFragment() }
            )

            val newVisibleTabs = allTabs.filter { tab ->
                when (tab.type) {
                    TabType.ALL_MOVIES -> settings?.showAllMoviesTab ?: true
                    TabType.FAVOURITES -> settings?.showFavouritesTab ?: true
                    TabType.COLLECTIONS -> settings?.showCollectionsTab ?: true
                    TabType.RECENT -> settings?.showRecentTab ?: true
                    TabType.ONLINE -> settings?.showOnlineTab ?: true
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
