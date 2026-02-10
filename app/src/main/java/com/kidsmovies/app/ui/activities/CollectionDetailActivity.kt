package com.kidsmovies.app.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.kidsmovies.app.KidsMoviesApp
import com.kidsmovies.app.R
import com.kidsmovies.app.data.database.entities.CollectionType
import com.kidsmovies.app.data.database.entities.Video
import com.kidsmovies.app.data.database.entities.VideoCollection
import com.kidsmovies.app.databinding.ActivityCollectionDetailBinding
import com.kidsmovies.app.ui.adapters.SeasonCardAdapter
import com.kidsmovies.app.ui.adapters.SeasonWithCount
import com.kidsmovies.app.ui.adapters.VideoAdapter
import com.kidsmovies.app.utils.ThemeManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CollectionDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCollectionDetailBinding
    private lateinit var app: KidsMoviesApp
    private lateinit var videoAdapter: VideoAdapter
    private lateinit var seasonAdapter: SeasonCardAdapter
    private var gridLayoutManager: GridLayoutManager? = null

    private var collectionId: Long = -1
    private var collectionName: String = ""
    private var collection: VideoCollection? = null
    private var isTvShow: Boolean = false
    private var currentGridColumns: Int = 4

    companion object {
        const val EXTRA_COLLECTION_ID = "extra_collection_id"
        const val EXTRA_COLLECTION_NAME = "extra_collection_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCollectionDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        app = application as KidsMoviesApp

        collectionId = intent.getLongExtra(EXTRA_COLLECTION_ID, -1)
        collectionName = intent.getStringExtra(EXTRA_COLLECTION_NAME) ?: ""

        if (collectionId == -1L) {
            finish()
            return
        }

        setupToolbar()
        setupSwipeRefresh()
        observeSettings()
        loadCollection()
    }

    private fun observeSettings() {
        lifecycleScope.launch {
            app.settingsRepository.getSettingsFlow().collectLatest { settings ->
                settings?.let {
                    if (it.gridColumns != currentGridColumns) {
                        currentGridColumns = it.gridColumns
                        gridLayoutManager?.spanCount = currentGridColumns
                        if (!isTvShow) {
                            videoAdapter.notifyDataSetChanged()
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Reload content to get fresh data
        if (isTvShow) {
            loadSeasons()
        } else {
            loadVideos()
        }
        lifecycleScope.launch {
            ThemeManager.applyTheme(this@CollectionDetailActivity)
        }
    }

    private fun setupToolbar() {
        binding.toolbar.title = collectionName
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupVideoRecyclerView() {
        videoAdapter = VideoAdapter(
            onVideoClick = { video -> playVideo(video) },
            onFavouriteClick = { video -> toggleFavourite(video) }
        )

        gridLayoutManager = GridLayoutManager(this@CollectionDetailActivity, currentGridColumns)

        binding.videosRecyclerView.apply {
            layoutManager = gridLayoutManager
            adapter = videoAdapter
        }
    }

    private fun setupSeasonRecyclerView() {
        seasonAdapter = SeasonCardAdapter(
            onSeasonClick = { season -> viewSeason(season) }
        )

        binding.videosRecyclerView.apply {
            layoutManager = GridLayoutManager(this@CollectionDetailActivity, 3)
            adapter = seasonAdapter
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeResources(R.color.primary)
        binding.swipeRefresh.setOnRefreshListener {
            if (isTvShow) {
                loadSeasons()
            } else {
                loadVideos()
            }
        }
    }

    private fun loadCollection() {
        lifecycleScope.launch {
            collection = app.collectionRepository.getCollectionById(collectionId)
            isTvShow = collection?.isTvShow() == true

            setupToolbar()

            if (isTvShow) {
                setupSeasonRecyclerView()
                loadSeasons()
            } else {
                setupVideoRecyclerView()
                loadVideos()
            }
        }
    }

    private fun loadSeasons() {
        lifecycleScope.launch {
            val seasons = app.collectionRepository.getSeasonsForShow(collectionId)
            binding.swipeRefresh.isRefreshing = false

            if (seasons.isEmpty()) {
                showEmptyState()
            } else {
                val seasonsWithCounts = seasons.map { season ->
                    val episodeCount = app.collectionRepository.getVideoCountInCollection(season.id)
                    SeasonWithCount(season, episodeCount)
                }
                showSeasons(seasonsWithCounts)
            }
        }
    }

    private fun loadVideos() {
        lifecycleScope.launch {
            // For seasons, use sorted query to order by episode number
            val isSeason = collection?.isSeason() == true
            val videos = if (isSeason) {
                app.collectionRepository.getVideosInCollectionSorted(collectionId)
            } else {
                app.collectionRepository.getVideosInCollection(collectionId)
            }
            binding.swipeRefresh.isRefreshing = false

            if (videos.isEmpty()) {
                showEmptyState()
            } else {
                showVideos(videos)
            }
        }
    }

    private fun showEmptyState() {
        binding.emptyState.visibility = View.VISIBLE
        binding.videosRecyclerView.visibility = View.GONE
    }

    private fun showVideos(videos: List<Video>) {
        binding.emptyState.visibility = View.GONE
        binding.videosRecyclerView.visibility = View.VISIBLE
        videoAdapter.submitList(videos)
    }

    private fun showSeasons(seasons: List<SeasonWithCount>) {
        binding.emptyState.visibility = View.GONE
        binding.videosRecyclerView.visibility = View.VISIBLE
        seasonAdapter.submitList(seasons)
    }

    private fun viewSeason(season: VideoCollection) {
        val intent = Intent(this, CollectionDetailActivity::class.java).apply {
            putExtra(EXTRA_COLLECTION_ID, season.id)
            putExtra(EXTRA_COLLECTION_NAME, season.name)
        }
        startActivity(intent)
    }

    private fun playVideo(video: Video) {
        val intent = Intent(this, VideoPlayerActivity::class.java).apply {
            putExtra(VideoPlayerActivity.EXTRA_VIDEO, video)
            putExtra(VideoPlayerActivity.EXTRA_COLLECTION_ID, collectionId)
            putExtra(VideoPlayerActivity.EXTRA_COLLECTION_NAME, collectionName)
        }
        startActivity(intent)
    }

    private fun toggleFavourite(video: Video) {
        lifecycleScope.launch {
            app.videoRepository.updateFavourite(video.id, !video.isFavourite)
            loadVideos()
        }
    }
}
