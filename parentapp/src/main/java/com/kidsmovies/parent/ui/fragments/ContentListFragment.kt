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
import com.kidsmovies.parent.ui.adapters.HierarchicalContentAdapter
import com.kidsmovies.parent.ui.adapters.HierarchicalItem
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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
    private lateinit var hierarchicalAdapter: HierarchicalContentAdapter

    private var contentType: String = TYPE_VIDEOS
    private var familyId: String = ""
    private var childUid: String = ""

    // Track expanded collections for hierarchical view
    private val expandedCollections = mutableSetOf<String>()

    // Cached data for building hierarchy
    private var allCollections: List<ChildCollection> = emptyList()
    private var allVideos: List<ChildVideo> = emptyList()

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

        hierarchicalAdapter = HierarchicalContentAdapter(
            onLockToggle = { item, isLocked ->
                showHierarchicalLockDialog(item, isLocked)
            },
            onItemClick = { item ->
                if (item is HierarchicalItem.Collection) {
                    toggleCollectionExpanded(item.collection.firebaseKey)
                }
            }
        )

        binding.contentRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = if (contentType == TYPE_COLLECTIONS) {
                this@ContentListFragment.hierarchicalAdapter
            } else {
                this@ContentListFragment.adapter
            }
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
                // Load both collections and videos for hierarchical display
                combine(
                    app.familyManager.getChildCollectionsFlow(familyId, childUid),
                    app.familyManager.getChildVideosFlow(familyId, childUid)
                ) { collections, videos ->
                    Pair(collections, videos)
                }.collectLatest { (collections, videos) ->
                    allCollections = collections
                    allVideos = videos
                    updateHierarchicalContent()
                }
            }
        }
    }

    private fun toggleCollectionExpanded(key: String) {
        if (expandedCollections.contains(key)) {
            expandedCollections.remove(key)
        } else {
            expandedCollections.add(key)
        }
        updateHierarchicalContent()
    }

    private fun updateHierarchicalContent() {
        binding.loadingIndicator.visibility = View.GONE

        if (allCollections.isEmpty()) {
            binding.contentRecyclerView.visibility = View.GONE
            binding.emptyState.visibility = View.VISIBLE
            return
        }

        binding.contentRecyclerView.visibility = View.VISIBLE
        binding.emptyState.visibility = View.GONE

        val items = mutableListOf<HierarchicalItem>()

        // Separate TV shows, seasons, and regular collections
        val tvShows = allCollections.filter { it.collection.type == "TV_SHOW" }
        val seasons = allCollections.filter { it.collection.type == "SEASON" }
        val regularCollections = allCollections.filter { it.collection.type == "REGULAR" }

        // Add TV shows with their seasons
        for (tvShow in tvShows) {
            val isExpanded = expandedCollections.contains(tvShow.firebaseKey)
            items.add(
                HierarchicalItem.Collection(
                    collection = tvShow,
                    depth = 0,
                    isExpanded = isExpanded,
                    isTvShow = true
                )
            )

            if (isExpanded) {
                // Find seasons for this TV show
                val showSeasons = seasons.filter {
                    it.collection.parentName == tvShow.collection.name
                }.sortedBy { extractSeasonNumber(it.collection.name) }

                for (season in showSeasons) {
                    val seasonExpanded = expandedCollections.contains(season.firebaseKey)
                    val seasonNum = extractSeasonNumber(season.collection.name)
                    val parentLocked = !tvShow.collection.isEnabled

                    items.add(
                        HierarchicalItem.Collection(
                            collection = season,
                            depth = 1,
                            isExpanded = seasonExpanded,
                            seasonNumber = seasonNum,
                            isSeason = true,
                            parentLocked = parentLocked
                        )
                    )

                    if (seasonExpanded) {
                        // Find episodes in this season
                        val episodes = allVideos.filter { video ->
                            video.video.collectionNames.contains(season.collection.name)
                        }

                        for (episode in episodes) {
                            items.add(
                                HierarchicalItem.Video(
                                    video = episode,
                                    depth = 2,
                                    parentLocked = parentLocked || !season.collection.isEnabled
                                )
                            )
                        }
                    }
                }
            }
        }

        // Add regular collections
        for (collection in regularCollections) {
            val isExpanded = expandedCollections.contains(collection.firebaseKey)
            items.add(
                HierarchicalItem.Collection(
                    collection = collection,
                    depth = 0,
                    isExpanded = isExpanded
                )
            )

            if (isExpanded) {
                // Find videos in this collection
                val videos = allVideos.filter { video ->
                    video.video.collectionNames.contains(collection.collection.name)
                }

                for (video in videos) {
                    items.add(
                        HierarchicalItem.Video(
                            video = video,
                            depth = 1,
                            parentLocked = !collection.collection.isEnabled
                        )
                    )
                }
            }
        }

        hierarchicalAdapter.submitList(items)
    }

    private fun extractSeasonNumber(seasonName: String): Int? {
        val regex = Regex("season\\s*(\\d+)", RegexOption.IGNORE_CASE)
        return regex.find(seasonName)?.groupValues?.get(1)?.toIntOrNull()
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

    private fun showHierarchicalLockDialog(item: HierarchicalItem, isLocked: Boolean) {
        val name = item.name

        if (isLocked) {
            // Show lock options dialog
            showHierarchicalLockOptionsDialog(item, name)
        } else {
            // Unlock - but check if this is unlocking within a locked parent
            val isUnlockingWithinLockedParent = when (item) {
                is HierarchicalItem.Video -> item.parentLocked
                is HierarchicalItem.Collection -> item.parentLocked
            }

            if (isUnlockingWithinLockedParent) {
                // Show confirmation for exception unlock
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.unlock_exception_title)
                    .setMessage(getString(R.string.unlock_exception_message, name))
                    .setPositiveButton(R.string.unlock) { _, _ ->
                        performHierarchicalUnlock(item, name)
                    }
                    .setNegativeButton(R.string.cancel) { _, _ ->
                        hierarchicalAdapter.notifyDataSetChanged()
                    }
                    .show()
            } else {
                performHierarchicalUnlock(item, name)
            }
        }
    }

    private fun showHierarchicalLockOptionsDialog(item: HierarchicalItem, name: String) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_lock_options, null)

        val warningGroup = dialogView.findViewById<android.widget.RadioGroup>(R.id.warningTimeGroup)
        val allowFinishCheckbox = dialogView.findViewById<android.widget.CheckBox>(R.id.allowFinishVideoCheckbox)

        val title = when (item) {
            is HierarchicalItem.Collection -> {
                when {
                    item.isTvShow -> getString(R.string.lock_tv_show_title)
                    item.isSeason -> getString(R.string.lock_season_title)
                    else -> getString(R.string.lock_collection_title)
                }
            }
            is HierarchicalItem.Video -> getString(R.string.lock_dialog_title)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton(R.string.lock_content) { _, _ ->
                val warningMinutes = when (warningGroup.checkedRadioButtonId) {
                    R.id.radioImmediate -> 0
                    R.id.radio1Min -> 1
                    R.id.radio5Min -> 5
                    R.id.radio10Min -> 10
                    R.id.radio15Min -> 15
                    else -> 5
                }
                performHierarchicalLock(item, name, warningMinutes, allowFinishCheckbox.isChecked)
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                hierarchicalAdapter.notifyDataSetChanged()
            }
            .setOnCancelListener {
                hierarchicalAdapter.notifyDataSetChanged()
            }
            .show()
    }

    private fun performHierarchicalLock(item: HierarchicalItem, name: String, warningMinutes: Int, allowFinishVideo: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                when (item) {
                    is HierarchicalItem.Video -> {
                        app.familyManager.setVideoLock(
                            familyId = familyId,
                            childUid = childUid,
                            videoTitle = name,
                            isLocked = true,
                            warningMinutes = warningMinutes,
                            allowFinishCurrentVideo = allowFinishVideo
                        )
                    }
                    is HierarchicalItem.Collection -> {
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
                hierarchicalAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun performHierarchicalUnlock(item: HierarchicalItem, name: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                when (item) {
                    is HierarchicalItem.Video -> {
                        app.familyManager.setVideoLock(
                            familyId = familyId,
                            childUid = childUid,
                            videoTitle = name,
                            isLocked = false
                        )
                    }
                    is HierarchicalItem.Collection -> {
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
                hierarchicalAdapter.notifyDataSetChanged()
            }
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
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_lock_options, null)

        val warningGroup = dialogView.findViewById<android.widget.RadioGroup>(R.id.warningTimeGroup)
        val allowFinishCheckbox = dialogView.findViewById<android.widget.CheckBox>(R.id.allowFinishVideoCheckbox)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lock_dialog_title)
            .setView(dialogView)
            .setPositiveButton(R.string.lock_content) { _, _ ->
                val warningMinutes = when (warningGroup.checkedRadioButtonId) {
                    R.id.radioImmediate -> 0
                    R.id.radio1Min -> 1
                    R.id.radio5Min -> 5
                    R.id.radio10Min -> 10
                    R.id.radio15Min -> 15
                    else -> 5
                }
                performLock(item, name, warningMinutes, allowFinishCheckbox.isChecked)
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
