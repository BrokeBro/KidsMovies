package com.kidsmovies.app.ui.activities

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.kidsmovies.app.KidsMoviesApp
import com.kidsmovies.app.R
import com.kidsmovies.app.databinding.ActivitySetupBinding
import com.kidsmovies.app.services.VideoScannerService
import com.kidsmovies.app.ui.fragments.*
import com.kidsmovies.app.utils.ThemeManager
import kotlinx.coroutines.launch

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var app: KidsMoviesApp

    private var selectedColorScheme = "blue"
    private var childName = ""
    private var videosFound = 0

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                VideoScannerService.ACTION_SCAN_COMPLETE -> {
                    videosFound = intent.getIntExtra(VideoScannerService.EXTRA_VIDEOS_FOUND, 0)
                    // Move to complete page
                    if (binding.setupViewPager.currentItem == 3) {
                        binding.setupViewPager.currentItem = 4
                        updateNextButton()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        app = application as KidsMoviesApp

        setupViewPager()
        setupPageIndicator()
        setupNavigation()

        // Register broadcast receiver
        val filter = IntentFilter().apply {
            addAction(VideoScannerService.ACTION_SCAN_COMPLETE)
            addAction(VideoScannerService.ACTION_SCAN_PROGRESS)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(scanReceiver, filter)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            ThemeManager.applyTheme(this@SetupActivity)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(scanReceiver)
    }

    private fun setupViewPager() {
        binding.setupViewPager.adapter = SetupPagerAdapter(this)
        binding.setupViewPager.isUserInputEnabled = false

        binding.setupViewPager.registerOnPageChangeCallback(object :
            androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updatePageIndicator(position)
                updateNextButton()
            }
        })
    }

    private fun setupPageIndicator() {
        val pageCount = 5
        for (i in 0 until pageCount) {
            val dot = ImageView(this).apply {
                setImageResource(R.drawable.indicator_dot)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = 8
                    marginEnd = 8
                }
            }
            binding.pageIndicator.addView(dot)
        }
        updatePageIndicator(0)
    }

    private fun updatePageIndicator(position: Int) {
        for (i in 0 until binding.pageIndicator.childCount) {
            val dot = binding.pageIndicator.getChildAt(i) as ImageView
            dot.alpha = if (i == position) 1f else 0.3f
        }
    }

    private fun updateNextButton() {
        val currentPage = binding.setupViewPager.currentItem
        when (currentPage) {
            0, 1, 2 -> {
                binding.nextButton.text = getString(R.string.setup_next)
                binding.nextButton.isEnabled = true
                binding.skipButton.visibility = View.GONE
            }
            3 -> {
                // Scanning page - hide button
                binding.nextButton.visibility = View.GONE
                binding.skipButton.visibility = View.GONE
            }
            4 -> {
                binding.nextButton.visibility = View.VISIBLE
                binding.nextButton.text = getString(R.string.setup_start)
                binding.nextButton.isEnabled = true
                binding.skipButton.visibility = View.GONE
            }
        }
    }

    private fun setupNavigation() {
        binding.nextButton.setOnClickListener {
            val currentPage = binding.setupViewPager.currentItem

            when (currentPage) {
                1 -> {
                    // Save name from name fragment
                    val fragment = supportFragmentManager.findFragmentByTag("f1")
                    if (fragment is SetupNameFragment) {
                        childName = fragment.getName()
                    }
                    binding.setupViewPager.currentItem = currentPage + 1
                }
                2 -> {
                    // Save color scheme and start scanning
                    saveSettings()
                    binding.setupViewPager.currentItem = 3
                    VideoScannerService.startScan(this)
                }
                4 -> {
                    // Complete setup
                    completeSetup()
                }
                else -> {
                    binding.setupViewPager.currentItem = currentPage + 1
                }
            }
        }
    }

    fun setSelectedColorScheme(scheme: String) {
        selectedColorScheme = scheme
        // Apply the color scheme preview immediately
        lifecycleScope.launch {
            app.settingsRepository.setColorScheme(scheme)
            ThemeManager.clearCache()
            ThemeManager.applyTheme(this@SetupActivity)
        }
    }

    fun getSelectedColorScheme(): String = selectedColorScheme

    fun getVideosFound(): Int = videosFound

    private fun saveSettings() {
        lifecycleScope.launch {
            app.settingsRepository.setColorScheme(selectedColorScheme)
            if (childName.isNotBlank()) {
                app.settingsRepository.setChildName(childName)
            }
        }
    }

    private fun completeSetup() {
        lifecycleScope.launch {
            app.settingsRepository.setSetupComplete(true)
            startActivity(Intent(this@SetupActivity, MainActivity::class.java))
            finish()
        }
    }

    private inner class SetupPagerAdapter(activity: AppCompatActivity) :
        FragmentStateAdapter(activity) {

        override fun getItemCount(): Int = 5

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> SetupWelcomeFragment()
                1 -> SetupNameFragment()
                2 -> SetupColorFragment()
                3 -> SetupScanFragment()
                4 -> SetupCompleteFragment()
                else -> SetupWelcomeFragment()
            }
        }
    }
}
