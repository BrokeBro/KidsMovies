package com.kidsmovies.app.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.kidsmovies.app.KidsMoviesApp
import com.kidsmovies.app.R
import com.kidsmovies.app.data.database.entities.Video
import com.kidsmovies.app.databinding.ActivityCollectionDetailBinding
import com.kidsmovies.app.ui.adapters.VideoAdapter
import com.kidsmovies.app.utils.ThemeManager
import kotlinx.coroutines.launch

class CollectionDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCollectionDetailBinding
    private lateinit var app: KidsMoviesApp
    private lateinit var videoAdapter: VideoAdapter

    private var collectionId: Long = -1
    private var collectionName: String = ""

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
        setupRecyclerView()
        setupSwipeRefresh()
        loadVideos()
    }

    override fun onResume() {
        super.onResume()
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

    private fun setupRecyclerView() {
        videoAdapter = VideoAdapter(
            onVideoClick = { video -> playVideo(video) },
            onFavouriteClick = { video -> toggleFavourite(video) }
        )

        binding.videosRecyclerView.apply {
            layoutManager = GridLayoutManager(this@CollectionDetailActivity, 4)
            adapter = videoAdapter
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeResources(R.color.primary)
        binding.swipeRefresh.setOnRefreshListener {
            loadVideos()
        }
    }

    private fun loadVideos() {
        lifecycleScope.launch {
            val videos = app.collectionRepository.getVideosInCollection(collectionId)
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

    private fun playVideo(video: Video) {
        val intent = Intent(this, VideoPlayerActivity::class.java).apply {
            putExtra(VideoPlayerActivity.EXTRA_VIDEO, video)
        }
        startActivity(intent)
    }

    private fun toggleFavourite(video: Video) {
        lifecycleScope.launch {
            app.videoRepository.setFavourite(video.id, !video.isFavourite)
            loadVideos()
        }
    }
}
