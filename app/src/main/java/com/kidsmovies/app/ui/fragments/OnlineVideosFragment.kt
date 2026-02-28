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
import com.kidsmovies.app.cloud.OneDriveScannerService
import com.kidsmovies.app.data.database.entities.Video
import com.kidsmovies.app.databinding.FragmentVideoGridBinding
import com.kidsmovies.app.ui.activities.VideoPlayerActivity
import com.kidsmovies.app.ui.adapters.VideoAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class OnlineVideosFragment : Fragment() {

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
        observeOnlineVideos()
        observeScanState()
        observeDownloadStates()
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
        binding.swipeRefresh.setOnRefreshListener {
            lifecycleScope.launch {
                val scanner = app.oneDriveScannerService
                if (scanner != null && scanner.isConfigured) {
                    scanner.scan()
                }
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private var videosJob: kotlinx.coroutines.Job? = null

    private fun observeOnlineVideos() {
        viewLifecycleOwner.lifecycleScope.launch {
            app.oneDriveScannerReady.collectLatest { ready ->
                // Cancel any previous video observer when state changes
                videosJob?.cancel()

                val scanner = app.oneDriveScannerService
                if (!ready || scanner == null || !scanner.isConfigured) {
                    showNotConfiguredState()
                    return@collectLatest
                }

                // Scanner is ready — start observing videos
                binding.swipeRefresh.isEnabled = true
                videosJob = viewLifecycleOwner.lifecycleScope.launch {
                    app.videoRepository.getVideosBySourceFlow("onedrive").collectLatest { videos ->
                        if (videos.isEmpty()) {
                            showEmptyState()
                        } else {
                            showVideoList(videos)
                        }
                    }
                }
            }
        }
    }

    private fun observeScanState() {
        viewLifecycleOwner.lifecycleScope.launch {
            app.oneDriveScannerReady.collectLatest { ready ->
                val scanner = app.oneDriveScannerService
                if (!ready || scanner == null) return@collectLatest

                scanner.scanState.collectLatest { state ->
                    when (state) {
                        is OneDriveScannerService.ScanState.Scanning -> {
                            binding.swipeRefresh.isRefreshing = true
                        }
                        is OneDriveScannerService.ScanState.Complete -> {
                            binding.swipeRefresh.isRefreshing = false
                        }
                        is OneDriveScannerService.ScanState.Error -> {
                            binding.swipeRefresh.isRefreshing = false
                        }
                        is OneDriveScannerService.ScanState.Idle -> {
                            // No-op
                        }
                    }
                }
            }
        }
    }

    private fun showNotConfiguredState() {
        binding.emptyState.visibility = View.VISIBLE
        binding.videoRecyclerView.visibility = View.GONE
        binding.emptyIcon.setImageResource(R.drawable.ic_cloud)
        binding.emptyTitle.text = getString(R.string.online_videos)
        binding.emptyMessage.text = "Ask a parent to connect OneDrive in the parent app"
        binding.swipeRefresh.isEnabled = false
    }

    private fun showEmptyState() {
        binding.emptyState.visibility = View.VISIBLE
        binding.videoRecyclerView.visibility = View.GONE
        binding.emptyIcon.setImageResource(R.drawable.ic_cloud)
        binding.emptyTitle.text = getString(R.string.online_videos)
        binding.emptyMessage.text = "No videos found in the connected OneDrive folder. Pull down to refresh."
        binding.swipeRefresh.isEnabled = true
    }

    private fun showVideoList(videos: List<Video>) {
        binding.emptyState.visibility = View.GONE
        binding.videoRecyclerView.visibility = View.VISIBLE
        binding.swipeRefresh.isEnabled = true
        videoAdapter.submitList(videos)
    }

    private fun observeDownloadStates() {
        viewLifecycleOwner.lifecycleScope.launch {
            app.videoDownloadManager?.downloadStates?.collectLatest {
                videoAdapter.notifyDataSetChanged()
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
        if (!video.isEnabled) return

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
