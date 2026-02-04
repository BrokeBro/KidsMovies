package com.kidsmovies.app.ui.fragments

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.kidsmovies.app.databinding.FragmentSetupScanBinding
import com.kidsmovies.app.services.VideoScannerService

class SetupScanFragment : Fragment() {

    private var _binding: FragmentSetupScanBinding? = null
    private val binding get() = _binding!!

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                VideoScannerService.ACTION_SCAN_PROGRESS -> {
                    val currentFile = intent.getStringExtra(VideoScannerService.EXTRA_CURRENT_FILE)
                    binding.scanStatus.text = currentFile ?: ""
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSetupScanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val filter = IntentFilter(VideoScannerService.ACTION_SCAN_PROGRESS)
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(progressReceiver, filter)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        LocalBroadcastManager.getInstance(requireContext())
            .unregisterReceiver(progressReceiver)
        _binding = null
    }
}
