package com.kidsmovies.app.ui.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.kidsmovies.app.KidsMoviesApp
import com.kidsmovies.app.R
import com.kidsmovies.app.data.database.entities.Video
import com.kidsmovies.app.data.database.entities.VideoCollection
import com.kidsmovies.app.databinding.FragmentCollectionsBinding
import com.kidsmovies.app.ui.activities.CollectionDetailActivity
import com.kidsmovies.app.ui.activities.VideoPlayerActivity
import com.kidsmovies.app.ui.adapters.CollectionIconAdapter
import com.kidsmovies.app.ui.adapters.CollectionRowAdapter
import com.kidsmovies.app.ui.adapters.CollectionWithVideos
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
            val newCollectionsWithVideos = allCollections.mapNotNull { collection ->
                val videos = app.collectionRepository.getVideosInCollection(collection.id)
                if (videos.isNotEmpty()) {
                    CollectionWithVideos(collection, videos)
                } else {
                    null
                }
            }

            collectionsWithVideos = newCollectionsWithVideos
            collectionRowAdapter.submitList(newCollectionsWithVideos.toList())
        }
    }

    private fun setupCollectionIcons() {
        collectionIconAdapter = CollectionIconAdapter(
            onCollectionClick = { collection -> scrollToCollection(collection) },
            onCollectionLongClick = { collection -> showCollectionOptions(collection) }
        )

        binding.collectionIconsRecyclerView.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = collectionIconAdapter
        }
    }

    private fun setupCollectionRows() {
        collectionRowAdapter = CollectionRowAdapter(
            onVideoClick = { video, collection -> playVideo(video, collection) },
            onCollectionClick = { collection -> viewCollection(collection) }
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
                allCollections = collections

                if (collections.isEmpty()) {
                    showEmptyState()
                } else {
                    // Load videos for each collection
                    val newCollectionsWithVideos = collections.mapNotNull { collection ->
                        val videos = app.collectionRepository.getVideosInCollection(collection.id)
                        if (videos.isNotEmpty()) {
                            CollectionWithVideos(collection, videos)
                        } else {
                            null // Don't show empty collections in row view
                        }
                    }

                    collectionsWithVideos = newCollectionsWithVideos

                    if (newCollectionsWithVideos.isEmpty()) {
                        showEmptyState()
                    } else {
                        showCollections(collections, newCollectionsWithVideos)
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
        collectionsWithVideos: List<CollectionWithVideos>
    ) {
        binding.emptyState.visibility = View.GONE
        binding.contentLayout.visibility = View.VISIBLE

        // Show all collections in the icon row (even empty ones)
        collectionIconAdapter.submitList(collections.toList())

        // Show only collections with videos in the row view
        collectionRowAdapter.submitList(collectionsWithVideos.toList())
    }

    private fun scrollToCollection(collection: VideoCollection) {
        // Find the position of this collection in the video rows
        val position = collectionsWithVideos.indexOfFirst { it.collection.id == collection.id }
        if (position >= 0) {
            binding.collectionsRecyclerView.smoothScrollToPosition(position)
            // Highlight the selected collection icon
            collectionIconAdapter.setSelectedCollection(collection.id)
        } else {
            // Collection has no videos, go to detail view
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
