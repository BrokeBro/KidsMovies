package com.kidsmovies.app.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.kidsmovies.app.KidsMoviesApp
import com.kidsmovies.app.R
import com.kidsmovies.app.data.database.entities.Video
import com.kidsmovies.app.databinding.FragmentVideoGridBinding
import com.kidsmovies.app.ui.activities.VideoPlayerActivity
import com.kidsmovies.app.ui.adapters.VideoAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class FavouritesFragment : Fragment() {

    private var _binding: FragmentVideoGridBinding? = null
    private val binding get() = _binding!!

    private lateinit var app: KidsMoviesApp
    private lateinit var videoAdapter: VideoAdapter
    private lateinit var gridLayoutManager: GridLayoutManager
    private var currentGridColumns: Int = 4

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
        observeSettings()
        observeFavourites()
    }

    private fun setupRecyclerView() {
        videoAdapter = VideoAdapter(
            onVideoClick = { video -> playVideo(video) },
            onFavouriteClick = { video -> toggleFavourite(video) }
        )

        gridLayoutManager = GridLayoutManager(context, currentGridColumns)

        binding.videoRecyclerView.apply {
            layoutManager = gridLayoutManager
            adapter = videoAdapter
        }
    }

    private fun observeSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            app.settingsRepository.getSettingsFlow().collectLatest { settings ->
                settings?.let {
                    if (it.gridColumns != currentGridColumns) {
                        currentGridColumns = it.gridColumns
                        gridLayoutManager.spanCount = currentGridColumns
                        videoAdapter.notifyDataSetChanged()
                    }
                }
            }
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeResources(R.color.primary)
        binding.swipeRefresh.isEnabled = false
    }

    private fun observeFavourites() {
        viewLifecycleOwner.lifecycleScope.launch {
            app.videoRepository.getFavouritesFlow().collectLatest { videos ->
                videoAdapter.submitList(videos)

                if (videos.isEmpty()) {
                    binding.emptyState.visibility = View.VISIBLE
                    binding.videoRecyclerView.visibility = View.GONE
                    binding.emptyIcon.setImageResource(R.drawable.ic_favourite)
                    binding.emptyTitle.text = getString(R.string.no_favourites)
                    binding.emptyMessage.text = getString(R.string.tap_heart)
                } else {
                    binding.emptyState.visibility = View.GONE
                    binding.videoRecyclerView.visibility = View.VISIBLE
                }
            }
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
