package com.kidsmovies.app.ui.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kidsmovies.app.KidsMoviesApp
import com.kidsmovies.app.R
import com.kidsmovies.app.data.repository.MetricsRepository
import com.kidsmovies.app.databinding.ActivityUsageMetricsBinding
import com.kidsmovies.app.utils.ThemeManager
import kotlinx.coroutines.launch

class UsageMetricsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUsageMetricsBinding
    private lateinit var app: KidsMoviesApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUsageMetricsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        app = application as KidsMoviesApp

        setupToolbar()
        setupClearButton()
        loadMetrics()
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            ThemeManager.applyTheme(this@UsageMetricsActivity)
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupClearButton() {
        binding.clearMetricsButton.setOnClickListener {
            showClearConfirmationDialog()
        }
    }

    private fun showClearConfirmationDialog() {
        AlertDialog.Builder(this, R.style.Theme_KidsMovies_Dialog)
            .setTitle(R.string.clear_metrics)
            .setMessage(R.string.clear_metrics_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                clearMetrics()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun clearMetrics() {
        lifecycleScope.launch {
            app.metricsRepository.deleteAllSessions()
            Toast.makeText(this@UsageMetricsActivity, R.string.metrics_cleared, Toast.LENGTH_SHORT).show()
            loadMetrics()
        }
    }

    private fun loadMetrics() {
        lifecycleScope.launch {
            val metrics = app.metricsRepository.getUsageMetrics()

            // Check if there's any data
            if (metrics.totalSessions == 0) {
                binding.emptyState.visibility = View.VISIBLE
                binding.contentScrollView.visibility = View.GONE
                return@launch
            }

            binding.emptyState.visibility = View.GONE
            binding.contentScrollView.visibility = View.VISIBLE

            // Total watch time
            binding.totalWatchTime.text = MetricsRepository.formatDuration(metrics.totalWatchTimeMs)

            // Session counts
            binding.totalSessions.text = metrics.totalSessions.toString()
            binding.completedSessions.text = metrics.completedSessions.toString()

            // Average session duration
            val avgSessionMs = (metrics.averageSessionMinutes * 60000).toLong()
            binding.averageSession.text = MetricsRepository.formatDuration(avgSessionMs)

            // Break analysis
            if (metrics.averageBreakDurationMs > 0) {
                binding.averageBreak.text = MetricsRepository.formatDuration(metrics.averageBreakDurationMs)
                binding.longestBreak.text = MetricsRepository.formatDuration(metrics.longestBreakDurationMs)
                binding.shortestBreak.text = MetricsRepository.formatDuration(metrics.shortestBreakDurationMs)
            } else {
                binding.averageBreak.text = "--"
                binding.longestBreak.text = "--"
                binding.shortestBreak.text = "--"
            }

            // Top collections
            populateTopCollections(metrics.topCollections)
        }
    }

    private fun populateTopCollections(collections: List<com.kidsmovies.app.data.database.dao.CollectionWatchTime>) {
        val container = binding.topCollectionsContainer
        container.removeAllViews()

        if (collections.isEmpty()) {
            val noDataText = TextView(this).apply {
                text = "No collection data yet"
                setTextColor(resources.getColor(R.color.text_secondary, theme))
                textSize = 14f
                setPadding(0, 32, 0, 32)
                textAlignment = View.TEXT_ALIGNMENT_CENTER
            }
            container.addView(noDataText)
            return
        }

        collections.forEachIndexed { index, collection ->
            val itemView = LayoutInflater.from(this)
                .inflate(R.layout.item_collection_metric, container, false)

            itemView.findViewById<TextView>(R.id.rankNumber).text = (index + 1).toString()
            itemView.findViewById<TextView>(R.id.collectionName).text = collection.collectionName
            itemView.findViewById<TextView>(R.id.sessionCount).text =
                getString(R.string.sessions_count, collection.sessionCount)
            itemView.findViewById<TextView>(R.id.watchTime).text =
                MetricsRepository.formatDuration(collection.totalWatchTimeMs)

            container.addView(itemView)

            // Add divider between items (but not after the last one)
            if (index < collections.size - 1) {
                val divider = View(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        1
                    ).apply {
                        setMargins(40, 0, 0, 0)
                    }
                    setBackgroundColor(resources.getColor(R.color.divider, theme))
                }
                container.addView(divider)
            }
        }
    }
}
