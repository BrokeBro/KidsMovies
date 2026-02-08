package com.kidsmovies.parent.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kidsmovies.parent.ParentApp
import com.kidsmovies.parent.R
import com.kidsmovies.parent.databinding.FragmentContentListBinding
import com.kidsmovies.parent.firebase.ChildCollection
import com.kidsmovies.parent.firebase.ChildVideo
import com.kidsmovies.parent.ui.adapters.ContentAdapter
import com.kidsmovies.parent.ui.adapters.ContentItem
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ContentListFragment : Fragment() {

    companion object {
        private const val ARG_TYPE = "type"
        private const val ARG_FAMILY_ID = "family_id"
        private const val ARG_CHILD_UID = "child_uid"

        const val TYPE_VIDEOS = "videos"
        const val TYPE_COLLECTIONS = "collections"

        fun newInstance(type: String, familyId: String, childUid: String): ContentListFragment {
            return ContentListFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TYPE, type)
                    putString(ARG_FAMILY_ID, familyId)
                    putString(ARG_CHILD_UID, childUid)
                }
            }
        }
    }

    private var _binding: FragmentContentListBinding? = null
    private val binding get() = _binding!!

    private lateinit var app: ParentApp
    private lateinit var adapter: ContentAdapter

    private var contentType: String = TYPE_VIDEOS
    private var familyId: String = ""
    private var childUid: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            contentType = it.getString(ARG_TYPE, TYPE_VIDEOS)
            familyId = it.getString(ARG_FAMILY_ID, "")
            childUid = it.getString(ARG_CHILD_UID, "")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentContentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        app = requireActivity().application as ParentApp

        setupUI()
        loadContent()
    }

    private fun setupUI() {
        adapter = ContentAdapter { item, isLocked ->
            showLockDialog(item, isLocked)
        }

        binding.contentRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ContentListFragment.adapter
        }

        // Set empty state based on type
        if (contentType == TYPE_VIDEOS) {
            binding.emptyIcon.setImageResource(R.drawable.ic_movie)
            binding.emptyText.text = getString(R.string.no_videos)
        } else {
            binding.emptyIcon.setImageResource(R.drawable.ic_folder)
            binding.emptyText.text = getString(R.string.no_collections)
        }
    }

    private fun loadContent() {
        binding.loadingIndicator.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            if (contentType == TYPE_VIDEOS) {
                app.familyManager.getChildVideosFlow(familyId, childUid).collectLatest { videos ->
                    updateVideos(videos)
                }
            } else {
                app.familyManager.getChildCollectionsFlow(familyId, childUid).collectLatest { collections ->
                    updateCollections(collections)
                }
            }
        }
    }

    private fun updateVideos(videos: List<ChildVideo>) {
        binding.loadingIndicator.visibility = View.GONE

        if (videos.isEmpty()) {
            binding.contentRecyclerView.visibility = View.GONE
            binding.emptyState.visibility = View.VISIBLE
        } else {
            binding.contentRecyclerView.visibility = View.VISIBLE
            binding.emptyState.visibility = View.GONE
            adapter.submitList(videos.map { ContentItem.Video(it) })
        }
    }

    private fun updateCollections(collections: List<ChildCollection>) {
        binding.loadingIndicator.visibility = View.GONE

        if (collections.isEmpty()) {
            binding.contentRecyclerView.visibility = View.GONE
            binding.emptyState.visibility = View.VISIBLE
        } else {
            binding.contentRecyclerView.visibility = View.VISIBLE
            binding.emptyState.visibility = View.GONE
            adapter.submitList(collections.map { ContentItem.Collection(it) })
        }
    }

    private fun showLockDialog(item: ContentItem, isLocked: Boolean) {
        val title: String
        val name: String

        when (item) {
            is ContentItem.Video -> {
                title = item.video.video.title
                name = title
            }
            is ContentItem.Collection -> {
                title = item.collection.collection.name
                name = title
            }
        }

        if (isLocked) {
            // Show lock options dialog
            showLockOptionsDialog(item, name)
        } else {
            // Unlock immediately
            performUnlock(item, name)
        }
    }

    private fun showLockOptionsDialog(item: ContentItem, name: String) {
        val warningOptions = arrayOf(
            getString(R.string.immediate),
            getString(R.string.minutes, 1),
            getString(R.string.minutes, 5),
            getString(R.string.minutes, 10),
            getString(R.string.minutes, 15)
        )
        val warningMinutes = intArrayOf(0, 1, 5, 10, 15)
        var selectedWarning = 2 // Default: 5 minutes
        var allowFinishVideo = true

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lock_dialog_title)
            .setSingleChoiceItems(warningOptions, selectedWarning) { _, which ->
                selectedWarning = which
            }
            .setMultiChoiceItems(
                arrayOf(getString(R.string.allow_finish_video)),
                booleanArrayOf(allowFinishVideo)
            ) { _, _, isChecked ->
                allowFinishVideo = isChecked
            }
            .setPositiveButton(R.string.lock_content) { _, _ ->
                performLock(item, name, warningMinutes[selectedWarning], allowFinishVideo)
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                // Revert the switch
                adapter.notifyDataSetChanged()
            }
            .setOnCancelListener {
                adapter.notifyDataSetChanged()
            }
            .show()
    }

    private fun performLock(item: ContentItem, name: String, warningMinutes: Int, allowFinishVideo: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                when (item) {
                    is ContentItem.Video -> {
                        app.familyManager.setVideoLock(
                            familyId = familyId,
                            childUid = childUid,
                            videoTitle = name,
                            isLocked = true,
                            warningMinutes = warningMinutes,
                            allowFinishCurrentVideo = allowFinishVideo
                        )
                    }
                    is ContentItem.Collection -> {
                        app.familyManager.setCollectionLock(
                            familyId = familyId,
                            childUid = childUid,
                            collectionName = name,
                            isLocked = true,
                            warningMinutes = warningMinutes,
                            allowFinishCurrentVideo = allowFinishVideo
                        )
                    }
                }
                showMessage(getString(R.string.content_locked_success))
            } catch (e: Exception) {
                showError(getString(R.string.error_generic))
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun performUnlock(item: ContentItem, name: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                when (item) {
                    is ContentItem.Video -> {
                        app.familyManager.setVideoLock(
                            familyId = familyId,
                            childUid = childUid,
                            videoTitle = name,
                            isLocked = false
                        )
                    }
                    is ContentItem.Collection -> {
                        app.familyManager.setCollectionLock(
                            familyId = familyId,
                            childUid = childUid,
                            collectionName = name,
                            isLocked = false
                        )
                    }
                }
                showMessage(getString(R.string.content_unlocked_success))
            } catch (e: Exception) {
                showError(getString(R.string.error_generic))
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun showMessage(message: String) {
        com.google.android.material.snackbar.Snackbar.make(
            binding.root,
            message,
            com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun showError(message: String) {
        com.google.android.material.snackbar.Snackbar.make(
            binding.root,
            message,
            com.google.android.material.snackbar.Snackbar.LENGTH_LONG
        ).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
