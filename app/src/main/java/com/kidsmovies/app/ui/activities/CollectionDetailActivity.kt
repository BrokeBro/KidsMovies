package com.kidsmovies.app.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
            onFavouriteClick = { video -> toggleFavourite(video) },
            onOptionsClick = { video -> showVideoOptions(video) }
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

    private fun showVideoOptions(video: Video) {
        val options = mutableListOf(
            getString(R.string.remove_from_collection),
            getString(R.string.move_to_collection)
        )

        // Add download options for remote videos
        if (video.isRemote()) {
            if (video.isDownloaded()) {
                options.add(getString(R.string.remove_download))
            } else {
                options.add(getString(R.string.download_for_offline))
            }
        }

        AlertDialog.Builder(this, R.style.Theme_KidsMovies_Dialog)
            .setTitle(video.title)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> removeVideoFromCollection(video)
                    1 -> showMoveToCollectionDialog(video)
                    2 -> {
                        if (video.isDownloaded()) {
                            removeDownload(video)
                        } else {
                            downloadVideo(video)
                        }
                    }
                }
            }
            .show()
    }

    private fun downloadVideo(video: Video) {
        val downloadManager = app.videoDownloadManager
        if (downloadManager == null) {
            Toast.makeText(this, R.string.download_not_available, Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val downloadFolder = app.settingsRepository.getDownloadFolder()
            if (downloadFolder == null) {
                Toast.makeText(
                    this@CollectionDetailActivity,
                    R.string.no_download_folder,
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            Toast.makeText(
                this@CollectionDetailActivity,
                getString(R.string.download_started, video.title),
                Toast.LENGTH_SHORT
            ).show()
            downloadManager.downloadVideo(video)
        }
    }

    private fun removeDownload(video: Video) {
        val downloadManager = app.videoDownloadManager ?: return

        AlertDialog.Builder(this, R.style.Theme_KidsMovies_Dialog)
            .setTitle(R.string.remove_download)
            .setMessage(getString(R.string.remove_download_confirm, video.title))
            .setPositiveButton(R.string.remove) { _, _ ->
                lifecycleScope.launch {
                    downloadManager.removeDownload(video)
                    Toast.makeText(
                        this@CollectionDetailActivity,
                        R.string.download_removed,
                        Toast.LENGTH_SHORT
                    ).show()
                    loadVideos()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun removeVideoFromCollection(video: Video) {
        AlertDialog.Builder(this, R.style.Theme_KidsMovies_Dialog)
            .setTitle(R.string.remove_from_collection)
            .setMessage(getString(R.string.remove_from_collection_confirm, video.title, collectionName))
            .setPositiveButton(R.string.remove) { _, _ ->
                lifecycleScope.launch {
                    app.collectionRepository.removeVideoFromCollection(collectionId, video.id)
                    Toast.makeText(
                        this@CollectionDetailActivity,
                        R.string.video_removed_from_collection,
                        Toast.LENGTH_SHORT
                    ).show()
                    loadVideos()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showMoveToCollectionDialog(video: Video) {
        lifecycleScope.launch {
            val allCollections = app.collectionRepository.getAllCollections()
            // Filter out the current collection and any TV_SHOW type (should move to seasons, not shows)
            val availableCollections = allCollections.filter {
                it.id != collectionId && it.collectionType != CollectionType.TV_SHOW.name
            }

            if (availableCollections.isEmpty()) {
                Toast.makeText(
                    this@CollectionDetailActivity,
                    R.string.no_other_collections,
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            val names = availableCollections.map { it.name }.toTypedArray()

            AlertDialog.Builder(this@CollectionDetailActivity, R.style.Theme_KidsMovies_Dialog)
                .setTitle(R.string.move_to_collection)
                .setItems(names) { _, which ->
                    val targetCollection = availableCollections[which]
                    lifecycleScope.launch {
                        // Remove from current collection
                        app.collectionRepository.removeVideoFromCollection(collectionId, video.id)
                        // Add to target collection
                        app.collectionRepository.addVideoToCollection(targetCollection.id, video.id)
                        Toast.makeText(
                            this@CollectionDetailActivity,
                            getString(R.string.video_moved_to_collection, targetCollection.name),
                            Toast.LENGTH_SHORT
                        ).show()
                        loadVideos()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }
}
