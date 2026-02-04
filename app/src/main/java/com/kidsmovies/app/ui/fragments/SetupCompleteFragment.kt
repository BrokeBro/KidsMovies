package com.kidsmovies.app.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.kidsmovies.app.R
import com.kidsmovies.app.databinding.FragmentSetupCompleteBinding
import com.kidsmovies.app.ui.activities.SetupActivity

class SetupCompleteFragment : Fragment() {

    private var _binding: FragmentSetupCompleteBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSetupCompleteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val videosFound = (activity as? SetupActivity)?.getVideosFound() ?: 0

        binding.completeMessage.text = if (videosFound > 0) {
            getString(R.string.setup_movies_found, videosFound)
        } else {
            getString(R.string.setup_no_movies)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
