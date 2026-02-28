package com.kidsmovies.app.ui.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kidsmovies.app.KidsMoviesApp
import com.kidsmovies.app.R
import com.kidsmovies.app.data.database.entities.Video
import com.kidsmovies.app.data.database.entities.VideoCollection
import com.kidsmovies.app.databinding.FragmentCollectionsBinding
import com.kidsmovies.app.data.database.entities.CollectionType
import com.kidsmovies.app.ui.activities.CollectionDetailActivity
import com.kidsmovies.app.ui.activities.VideoPlayerActivity
import com.kidsmovies.app.ui.adapters.CollectionIconAdapter
import com.kidsmovies.app.ui.adapters.CollectionRowAdapter
import com.kidsmovies.app.ui.adapters.CollectionRowItem
import com.kidsmovies.app.ui.adapters.CollectionWithVideos
import com.kidsmovies.app.ui.adapters.SeasonWithCount
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class CollectionsFragment : Fragment() {

    private var _binding: FragmentCollectionsBinding? = null
    private val binding get() = _binding!!

    private lateinit var app: KidsMoviesApp
    private lateinit var collectionIconAdapter: CollectionIconAdapter
    private lateinit var collectionRowAdapter: CollectionRowAdapter

    private var pendingThumbnailCollection: VideoCollection? = null
    private var allCollections: List<VideoCollection> = emptyList()
    private var collectionsWithVideos: List<CollectionWithVideos> = emptyList()

    private val thumbnailPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { handleThumbnailSelected(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCollectionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        app = requireActivity().application as KidsMoviesApp

        setupCollectionIcons()
        setupCollectionRows()
        setupSwipeRefresh()
        setupReorderButton()
        observeCollections()
    }

    override fun onResume() {
        super.onResume()
        // Reload videos to get fresh playback positions after returning from video player
        refreshCollectionVideos()
    }

    private fun refreshCollectionVideos() {
        if (allCollections.isEmpty()) return

        viewLifecycleOwner.lifecycleScope.launch {
            val rowItems = buildRowItems(allCollections)

            val newCollectionsWithVideos = allCollections.mapNotNull { collection ->
                if (collection.isTvShow()) {
                    null
                } else {
                    val videos = app.collectionRepository.getVideosInCollection(collection.id)
                    if (videos.isNotEmpty()) {
                        CollectionWithVideos(collection, videos)
                    } else {
                        null
                    }
                }
            }

            collectionsWithVideos = newCollectionsWithVideos
            collectionRowAdapter.submitList(rowItems.toList())
        }
    }

    private fun setupCollectionIcons() {
        collectionIconAdapter = CollectionIconAdapter(
            onCollectionClick = { collection -> scrollToCollection(collection) },
            onCollectionLongClick = { collection -> showCollectionOptions(collection) },
            onOrderChanged = { newOrder -> saveCollectionOrder(newOrder) }
        )

        binding.collectionIconsRecyclerView.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = collectionIconAdapter

            // Prevent parent views from intercepting horizontal touch events
            addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                            rv.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            rv.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                    return false
                }
                override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}
                override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
            })
        }

        collectionIconAdapter.attachToRecyclerView(binding.collectionIconsRecyclerView)
    }

    private fun setupReorderButton() {
        binding.reorderButton.setOnClickListener {
            if (collectionIconAdapter.isInReorderMode()) {
                // Exit reorder mode
                collectionIconAdapter.setReorderMode(false)
                binding.reorderButton.setColorFilter(
                    resources.getColor(R.color.text_secondary, null)
                )
            } else {
                // Enter reorder mode
                collectionIconAdapter.setReorderMode(true)
                binding.reorderButton.setColorFilter(
                    resources.getColor(R.color.primary, null)
                )
                Toast.makeText(requireContext(), R.string.reorder_mode_enabled, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveCollectionOrder(collections: List<VideoCollection>) {
        viewLifecycleOwner.lifecycleScope.launch {
            collections.forEachIndexed { index, collection ->
                app.collectionRepository.updateSortOrder(collection.id, index)
            }
            Toast.makeText(requireContext(), R.string.reorder_mode_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupCollectionRows() {
        collectionRowAdapter = CollectionRowAdapter(
            onVideoClick = { video, collection -> playVideo(video, collection) },
            onCollectionClick = { collection -> viewCollection(collection) },
            onSeasonClick = { season -> viewCollection(season) },
            onVideoLongClick = { video -> showDownloadOption(video) }
        )

        binding.collectionsRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = collectionRowAdapter
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeResources(R.color.primary)
        binding.swipeRefresh.setOnRefreshListener {
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun observeCollections() {
        viewLifecycleOwner.lifecycleScope.launch {
            app.collectionRepository.getAllCollectionsFlow().collectLatest { collections ->
                // Filter to only top-level collections (not seasons)
                val topLevelCollections = collections.filter { it.parentCollectionId == null }
                allCollections = topLevelCollections

                if (topLevelCollections.isEmpty()) {
                    showEmptyState()
                } else {
                    // Build row items based on collection type
                    val rowItems = buildRowItems(topLevelCollections)
                    // Also build CollectionWithVideos for backward compatibility
                    val newCollectionsWithVideos = topLevelCollections.mapNotNull { collection ->
                        if (collection.isTvShow()) {
                            // For TV shows, we don't use CollectionWithVideos
                            null
                        } else {
                            val videos = app.collectionRepository.getVideosInCollection(collection.id)
                            if (videos.isNotEmpty()) {
                                CollectionWithVideos(collection, videos)
                            } else {
                                null
                            }
                        }
                    }

                    collectionsWithVideos = newCollectionsWithVideos

                    if (rowItems.isEmpty()) {
                        showEmptyState()
                    } else {
                        showCollections(topLevelCollections, rowItems)
                    }
                }
            }
        }
    }

    private suspend fun buildRowItems(collections: List<VideoCollection>): List<CollectionRowItem> {
        return collections.mapNotNull { collection ->
            when (collection.getType()) {
                CollectionType.TV_SHOW -> {
                    // Load seasons for this TV show
                    val seasons = app.collectionRepository.getSeasonsForShow(collection.id)
                    if (seasons.isNotEmpty()) {
                        val seasonsWithCounts = seasons.map { season ->
                            val episodeCount = app.collectionRepository.getVideoCountInCollection(season.id)
                            SeasonWithCount(season, episodeCount)
                        }
                        CollectionRowItem.SeasonsRow(collection, seasonsWithCounts)
                    } else {
                        null // TV show with no seasons yet
                    }
                }
                CollectionType.SEASON -> {
                    // Seasons should be handled by their parent TV show, skip them at top level
                    null
                }
                CollectionType.REGULAR -> {
                    // Regular collection - show videos
                    val videos = app.collectionRepository.getVideosInCollection(collection.id)
                    if (videos.isNotEmpty()) {
                        CollectionRowItem.VideosRow(collection, videos)
                    } else {
                        null
                    }
                }
                CollectionType.FRANCHISE -> {
                    // Franchise collection - show videos (same display as regular)
                    val videos = app.collectionRepository.getVideosInCollection(collection.id)
                    if (videos.isNotEmpty()) {
                        CollectionRowItem.VideosRow(collection, videos)
                    } else {
                        null
                    }
                }
            }
        }
    }

    private fun showEmptyState() {
        binding.emptyState.visibility = View.VISIBLE
        binding.contentLayout.visibility = View.GONE
    }

    private fun showCollections(
        collections: List<VideoCollection>,
        rowItems: List<CollectionRowItem>
    ) {
        binding.emptyState.visibility = View.GONE
        binding.contentLayout.visibility = View.VISIBLE

        // Show all top-level collections in the icon row (even empty ones)
        collectionIconAdapter.submitList(collections.toList())

        // Show row items (both regular collections with videos and TV shows with seasons)
        collectionRowAdapter.submitList(rowItems.toList())
    }

    private fun scrollToCollection(collection: VideoCollection) {
        // Find the position of this collection in the row items
        val rowItems = collectionRowAdapter.currentList
        val position = rowItems.indexOfFirst { it.collection.id == collection.id }
        if (position >= 0) {
            binding.collectionsRecyclerView.smoothScrollToPosition(position)
            // Highlight the selected collection icon
            collectionIconAdapter.setSelectedCollection(collection.id)
        } else {
            // Collection not in list, go to detail view
            viewCollection(collection)
        }
    }

    private fun showCollectionOptions(collection: VideoCollection): Boolean {
        pendingThumbnailCollection = collection

        val options = arrayOf(
            getString(R.string.change_thumbnail),
            if (collection.thumbnailPath != null) getString(R.string.reset_thumbnail) else null,
            getString(R.string.edit_collection)
        ).filterNotNull().toTypedArray()

        AlertDialog.Builder(requireContext(), R.style.Theme_KidsMovies_Dialog)
            .setTitle(collection.name)
            .setItems(options) { _, which ->
                when (options[which]) {
                    getString(R.string.change_thumbnail) -> thumbnailPicker.launch("image/*")
                    getString(R.string.reset_thumbnail) -> resetCollectionThumbnail(collection)
                    getString(R.string.edit_collection) -> viewCollection(collection)
                }
            }
            .show()

        return true
    }

    private fun handleThumbnailSelected(uri: Uri) {
        val collection = pendingThumbnailCollection ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Copy the image to app's internal storage
                val thumbnailDir = File(requireContext().filesDir, "collection_thumbnails")
                if (!thumbnailDir.exists()) {
                    thumbnailDir.mkdirs()
                }

                val thumbnailFile = File(thumbnailDir, "collection_${collection.id}.jpg")

                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(thumbnailFile).use { output ->
                        input.copyTo(output)
                    }
                }

                // Update collection with new thumbnail path
                val updatedCollection = collection.copy(
                    thumbnailPath = thumbnailFile.absolutePath,
                    dateModified = System.currentTimeMillis()
                )
                app.collectionRepository.updateCollection(updatedCollection)

                Toast.makeText(requireContext(), R.string.thumbnail_changed, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), R.string.error, Toast.LENGTH_SHORT).show()
            }
        }

        pendingThumbnailCollection = null
    }

    private fun resetCollectionThumbnail(collection: VideoCollection) {
        viewLifecycleOwner.lifecycleScope.launch {
            // Delete the thumbnail file if it exists
            collection.thumbnailPath?.let { path ->
                File(path).delete()
            }

            // Update collection to remove thumbnail path
            val updatedCollection = collection.copy(
                thumbnailPath = null,
                dateModified = System.currentTimeMillis()
            )
            app.collectionRepository.updateCollection(updatedCollection)

            Toast.makeText(requireContext(), R.string.thumbnail_reset, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDownloadOption(video: Video) {
        if (!video.isRemote()) return

        if (video.isDownloaded()) {
            // Already downloaded - offer to remove
            AlertDialog.Builder(requireContext(), R.style.Theme_KidsMovies_Dialog)
                .setTitle(video.title)
                .setItems(arrayOf(getString(R.string.remove_download))) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        app.videoDownloadManager?.removeDownload(video)
                        Toast.makeText(requireContext(), R.string.download_removed, Toast.LENGTH_SHORT).show()
                    }
                }
                .show()
        } else {
            // Not downloaded - offer to download
            AlertDialog.Builder(requireContext(), R.style.Theme_KidsMovies_Dialog)
                .setTitle(video.title)
                .setItems(arrayOf(getString(R.string.download_for_offline))) { _, _ ->
                    val downloadManager = app.videoDownloadManager
                    if (downloadManager == null) {
                        Toast.makeText(requireContext(), R.string.download_not_available, Toast.LENGTH_SHORT).show()
                        return@setItems
                    }
                    downloadManager.downloadVideo(video)
                    Toast.makeText(requireContext(), getString(R.string.download_started, video.title), Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    private fun playVideo(video: Video, collection: VideoCollection) {
        val intent = Intent(requireContext(), VideoPlayerActivity::class.java).apply {
            putExtra(VideoPlayerActivity.EXTRA_VIDEO, video)
            putExtra(VideoPlayerActivity.EXTRA_COLLECTION_ID, collection.id)
            putExtra(VideoPlayerActivity.EXTRA_COLLECTION_NAME, collection.name)
        }
        startActivity(intent)
    }

    private fun viewCollection(collection: VideoCollection) {
        val intent = Intent(requireContext(), CollectionDetailActivity::class.java).apply {
            putExtra(CollectionDetailActivity.EXTRA_COLLECTION_ID, collection.id)
            putExtra(CollectionDetailActivity.EXTRA_COLLECTION_NAME, collection.name)
        }
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
