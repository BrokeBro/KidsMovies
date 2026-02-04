package com.kidsmovies.app.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.kidsmovies.app.databinding.FragmentSetupNameBinding

class SetupNameFragment : Fragment() {

    private var _binding: FragmentSetupNameBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSetupNameBinding.inflate(inflater, container, false)
        return binding.root
    }

    fun getName(): String {
        return binding.nameInput.text?.toString()?.trim() ?: ""
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
