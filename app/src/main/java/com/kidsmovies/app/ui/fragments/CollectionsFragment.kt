package com.kidsmovies.app.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.kidsmovies.app.KidsMoviesApp
import com.kidsmovies.app.R
import com.kidsmovies.app.data.database.entities.Video
import com.kidsmovies.app.data.database.entities.VideoCollection
import com.kidsmovies.app.databinding.FragmentCollectionsBinding
import com.kidsmovies.app.ui.activities.VideoPlayerActivity
import com.kidsmovies.app.ui.adapters.CollectionRowAdapter
import com.kidsmovies.app.ui.adapters.CollectionWithVideos
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class CollectionsFragment : Fragment() {

    private var _binding: FragmentCollectionsBinding? = null
    private val binding get() = _binding!!

    private lateinit var app: KidsMoviesApp
    private lateinit var collectionAdapter: CollectionRowAdapter

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

        setupRecyclerView()
        setupSwipeRefresh()
        observeCollections()
    }

    private fun setupRecyclerView() {
        collectionAdapter = CollectionRowAdapter(
            onVideoClick = { video -> playVideo(video) },
            onCollectionClick = { collection -> viewCollection(collection) }
        )

        binding.collectionsRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = collectionAdapter
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
            // Combine collections flow with their videos
            app.collectionRepository.getAllCollectionsFlow().collectLatest { collections ->
                if (collections.isEmpty()) {
                    showEmptyState()
                } else {
                    // Load videos for each collection
                    val collectionsWithVideos = collections.mapNotNull { collection ->
                        val videos = app.collectionRepository.getVideosInCollection(collection.id)
                        if (videos.isNotEmpty()) {
                            CollectionWithVideos(collection, videos)
                        } else {
                            null // Don't show empty collections
                        }
                    }

                    if (collectionsWithVideos.isEmpty()) {
                        showEmptyState()
                    } else {
                        showCollections(collectionsWithVideos)
                    }
                }
            }
        }
    }

    private fun showEmptyState() {
        binding.emptyState.visibility = View.VISIBLE
        binding.collectionsRecyclerView.visibility = View.GONE
    }

    private fun showCollections(collectionsWithVideos: List<CollectionWithVideos>) {
        binding.emptyState.visibility = View.GONE
        binding.collectionsRecyclerView.visibility = View.VISIBLE
        collectionAdapter.submitList(collectionsWithVideos)
    }

    private fun playVideo(video: Video) {
        val intent = Intent(requireContext(), VideoPlayerActivity::class.java).apply {
            putExtra(VideoPlayerActivity.EXTRA_VIDEO, video)
        }
        startActivity(intent)
    }

    private fun viewCollection(collection: VideoCollection) {
        // TODO: Open a detailed collection view if needed
        // For now, the carousel shows all videos in the collection
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
