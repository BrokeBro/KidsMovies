package com.kidsmovies.app.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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

class RecentVideosFragment : Fragment() {

    private var _binding: FragmentVideoGridBinding? = null
    private val binding get() = _binding!!

    private lateinit var app: KidsMoviesApp
    private lateinit var videoAdapter: VideoAdapter

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
        observeRecentVideos()
    }

    private fun setupRecyclerView() {
        videoAdapter = VideoAdapter(
            onVideoClick = { video -> playVideo(video) },
            onFavouriteClick = { video -> toggleFavourite(video) },
            onDownloadClick = { video -> showDownloadOption(video) }
        )

        binding.videoRecyclerView.apply {
            layoutManager = GridLayoutManager(context, 4)
            adapter = videoAdapter
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeResources(R.color.primary)
        binding.swipeRefresh.isEnabled = false
    }

    private fun observeRecentVideos() {
        viewLifecycleOwner.lifecycleScope.launch {
            app.videoRepository.getRecentlyPlayedFlow(20).collectLatest { videos ->
                videoAdapter.submitList(videos)

                if (videos.isEmpty()) {
                    binding.emptyState.visibility = View.VISIBLE
                    binding.videoRecyclerView.visibility = View.GONE
                    binding.emptyIcon.setImageResource(R.drawable.ic_history)
                    binding.emptyTitle.text = getString(R.string.recently_watched)
                    binding.emptyMessage.text = "Videos you watch will appear here"
                } else {
                    binding.emptyState.visibility = View.GONE
                    binding.videoRecyclerView.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun showDownloadOption(video: Video) {
        if (!video.isRemote()) return

        if (video.isDownloaded()) {
            AlertDialog.Builder(requireContext())
                .setTitle(video.title)
                .setItems(arrayOf(getString(R.string.remove_download))) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        app.videoDownloadManager?.removeDownload(video)
                        Toast.makeText(requireContext(), R.string.download_removed, Toast.LENGTH_SHORT).show()
                    }
                }
                .show()
        } else {
            AlertDialog.Builder(requireContext())
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
