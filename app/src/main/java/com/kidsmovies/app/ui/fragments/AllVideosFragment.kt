package com.kidsmovies.app.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.kidsmovies.app.KidsMoviesApp
import com.kidsmovies.app.R
import com.kidsmovies.app.data.database.entities.Video
import com.kidsmovies.app.data.database.entities.VideoCollection
import com.kidsmovies.app.databinding.FragmentVideoGridBinding
import com.kidsmovies.app.ui.activities.VideoPlayerActivity
import com.kidsmovies.app.ui.adapters.VideoAdapter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AllVideosFragment : Fragment() {

    private var _binding: FragmentVideoGridBinding? = null
    private val binding get() = _binding!!

    private lateinit var app: KidsMoviesApp
    private lateinit var videoAdapter: VideoAdapter

    private var currentQuery: String = ""
    private var allVideos: List<Video> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVideoGridBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        app = requireActivity().application as KidsMoviesApp

        setupRecyclerView()
        setupSwipeRefresh()
        setupSelectionToolbar()
        observeVideos()
    }

    private fun setupRecyclerView() {
        videoAdapter = VideoAdapter(
            onVideoClick = { video -> playVideo(video) },
            onFavouriteClick = { video -> toggleFavourite(video) },
            onVideoLongClick = { _ ->
                showSelectionToolbar()
                true
            },
            onSelectionChanged = { selectedIds ->
                updateSelectionCount(selectedIds.size)
                if (selectedIds.isEmpty()) {
                    hideSelectionToolbar()
                }
            }
        )

        binding.videoRecyclerView.apply {
            layoutManager = GridLayoutManager(context, 4)
            adapter = videoAdapter
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeResources(R.color.primary)
        binding.swipeRefresh.setOnRefreshListener {
            // Refresh will be handled by MainActivity
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun setupSelectionToolbar() {
        binding.selectionToolbar.visibility = View.GONE

        binding.cancelSelectionButton.setOnClickListener {
            videoAdapter.exitSelectionMode()
            hideSelectionToolbar()
        }

        binding.selectAllButton.setOnClickListener {
            videoAdapter.selectAll()
        }

        binding.addToCollectionButton.setOnClickListener {
            showAddToCollectionDialog()
        }

        binding.deleteSelectedButton.setOnClickListener {
            showDeleteConfirmationDialog()
        }
    }

    private fun showAddToCollectionDialog() {
        val selectedIds = videoAdapter.getSelectedVideoIds()
        if (selectedIds.isEmpty()) {
            Toast.makeText(requireContext(), "No videos selected", Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val collections = app.collectionRepository.getAllCollections()

            if (collections.isEmpty()) {
                // No collections exist, prompt to create one
                showCreateCollectionDialog(selectedIds)
            } else {
                // Show collection picker with option to create new
                showCollectionPickerDialog(collections, selectedIds)
            }
        }
    }

    private fun showCollectionPickerDialog(collections: List<VideoCollection>, selectedVideoIds: Set<Long>) {
        val options = collections.map { it.name }.toMutableList()
        options.add(getString(R.string.new_collection))

        AlertDialog.Builder(requireContext(), R.style.Theme_KidsMovies_Dialog)
            .setTitle(R.string.add_to_collection)
            .setItems(options.toTypedArray()) { _, which ->
                if (which == options.size - 1) {
                    // Create new collection
                    showCreateCollectionDialog(selectedVideoIds)
                } else {
                    // Add to existing collection
                    addVideosToCollection(collections[which], selectedVideoIds)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showCreateCollectionDialog(selectedVideoIds: Set<Long>) {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.collection_name)
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(requireContext(), R.style.Theme_KidsMovies_Dialog)
            .setTitle(R.string.new_collection)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    createCollectionAndAddVideos(name, selectedVideoIds)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun createCollectionAndAddVideos(name: String, videoIds: Set<Long>) {
        viewLifecycleOwner.lifecycleScope.launch {
            val collection = VideoCollection(name = name)
            val collectionId = app.collectionRepository.insertCollection(collection)

            // Add videos to collection
            videoIds.forEach { videoId ->
                app.collectionRepository.addVideoToCollection(collectionId, videoId)
            }

            Toast.makeText(
                requireContext(),
                "Added ${videoIds.size} video(s) to \"$name\"",
                Toast.LENGTH_SHORT
            ).show()

            videoAdapter.exitSelectionMode()
            hideSelectionToolbar()
        }
    }

    private fun addVideosToCollection(collection: VideoCollection, videoIds: Set<Long>) {
        viewLifecycleOwner.lifecycleScope.launch {
            videoIds.forEach { videoId ->
                app.collectionRepository.addVideoToCollection(collection.id, videoId)
            }

            Toast.makeText(
                requireContext(),
                "Added ${videoIds.size} video(s) to \"${collection.name}\"",
                Toast.LENGTH_SHORT
            ).show()

            videoAdapter.exitSelectionMode()
            hideSelectionToolbar()
        }
    }

    private fun showSelectionToolbar() {
        binding.selectionToolbar.visibility = View.VISIBLE
    }

    private fun hideSelectionToolbar() {
        binding.selectionToolbar.visibility = View.GONE
    }

    private fun updateSelectionCount(count: Int) {
        binding.selectionCount.text = getString(R.string.items_selected, count)
    }

    private fun showDeleteConfirmationDialog() {
        val selectedCount = videoAdapter.getSelectedVideoIds().size
        
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.remove_from_library)
            .setMessage(getString(R.string.remove_videos_message, selectedCount))
            .setPositiveButton(R.string.remove) { _, _ ->
                removeSelectedVideos()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun removeSelectedVideos() {
        val selectedIds = videoAdapter.getSelectedVideoIds().toList()
        
        viewLifecycleOwner.lifecycleScope.launch {
            app.videoRepository.deleteVideosByIds(selectedIds)
            videoAdapter.exitSelectionMode()
            hideSelectionToolbar()
            Toast.makeText(
                requireContext(),
                getString(R.string.videos_removed_from_library, selectedIds.size),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun observeVideos() {
        viewLifecycleOwner.lifecycleScope.launch {
            app.videoRepository.getAllVideosFlow().collectLatest { videos ->
                allVideos = videos
                updateDisplay()
            }
        }
    }

    private fun updateDisplay() {
        val filteredVideos = if (currentQuery.isBlank()) {
            allVideos
        } else {
            allVideos.filter { it.title.contains(currentQuery, ignoreCase = true) }
        }

        videoAdapter.submitList(filteredVideos)

        if (filteredVideos.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            binding.videoRecyclerView.visibility = View.GONE
            binding.emptyTitle.text = getString(R.string.no_movies)
        } else {
            binding.emptyState.visibility = View.GONE
            binding.videoRecyclerView.visibility = View.VISIBLE
        }
    }

    private fun playVideo(video: Video) {
        val intent = Intent(requireContext(), VideoPlayerActivity::class.java).apply {
            putExtra(VideoPlayerActivity.EXTRA_VIDEO, video)
        }
        startActivity(intent)
    }

    private fun toggleFavourite(video: Video) {
        viewLifecycleOwner.lifecycleScope.launch {
            app.videoRepository.updateFavourite(video.id, !video.isFavourite)
        }
    }

    fun filterByQuery(query: String) {
        currentQuery = query
        updateDisplay()
    }

    fun clearFilter() {
        currentQuery = ""
        updateDisplay()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
