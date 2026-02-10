package com.kidsmovies.app.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.kidsmovies.app.R
import com.kidsmovies.app.databinding.FragmentVideoGridBinding

/**
 * Placeholder fragment for OneDrive online videos.
 * This will be implemented when OneDrive integration is added.
 */
class OnlineVideosFragment : Fragment() {

    private var _binding: FragmentVideoGridBinding? = null
    private val binding get() = _binding!!

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

        // Show placeholder state
        binding.emptyState.visibility = View.VISIBLE
        binding.videoRecyclerView.visibility = View.GONE
        binding.emptyIcon.setImageResource(R.drawable.ic_cloud)
        binding.emptyTitle.text = getString(R.string.online_videos)
        binding.emptyMessage.text = "Coming soon! Connect to OneDrive in Settings"

        binding.swipeRefresh.isEnabled = false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
