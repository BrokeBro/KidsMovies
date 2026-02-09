package com.kidsmovies.parent.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.kidsmovies.parent.R
import com.kidsmovies.parent.databinding.ItemHierarchicalContentBinding
import com.kidsmovies.parent.firebase.ChildCollection
import com.kidsmovies.parent.firebase.ChildVideo

/**
 * Represents content items in a hierarchical structure.
 * depth: 0 = top level, 1 = season/child, 2 = episode/grandchild
 */
sealed class HierarchicalItem {
    abstract val depth: Int
    abstract val isLocked: Boolean
    abstract val name: String

    data class Video(
        val video: ChildVideo,
        override val depth: Int = 0,
        val parentLocked: Boolean = false // If parent collection is locked
    ) : HierarchicalItem() {
        override val isLocked: Boolean get() = !video.video.isEnabled || parentLocked
        override val name: String get() = video.video.title
    }

    data class Collection(
        val collection: ChildCollection,
        override val depth: Int = 0,
        val isExpanded: Boolean = false,
        val seasonNumber: Int? = null, // For seasons
        val isTvShow: Boolean = false,
        val isSeason: Boolean = false,
        val parentLocked: Boolean = false
    ) : HierarchicalItem() {
        override val isLocked: Boolean get() = !collection.collection.isEnabled || parentLocked
        override val name: String get() = collection.collection.name
    }
}

class HierarchicalContentAdapter(
    private val onLockToggle: (HierarchicalItem, Boolean) -> Unit,
    private val onItemClick: (HierarchicalItem) -> Unit
) : ListAdapter<HierarchicalItem, HierarchicalContentAdapter.ContentViewHolder>(ContentDiffCallback()) {

    private val INDENT_PER_LEVEL_DP = 24

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContentViewHolder {
        val binding = ItemHierarchicalContentBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ContentViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ContentViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ContentViewHolder(
        private val binding: ItemHierarchicalContentBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: HierarchicalItem) {
            val context = binding.root.context
            val density = context.resources.displayMetrics.density

            // Set indentation based on depth
            val indentPx = (item.depth * INDENT_PER_LEVEL_DP * density).toInt()
            binding.indentSpace.layoutParams = binding.indentSpace.layoutParams.apply {
                width = indentPx
            }

            when (item) {
                is HierarchicalItem.Video -> bindVideo(item)
                is HierarchicalItem.Collection -> bindCollection(item)
            }

            // Lock status UI
            updateLockUI(item.isLocked, item is HierarchicalItem.Video && (item as? HierarchicalItem.Video)?.parentLocked == true)

            // Lock switch
            binding.lockSwitch.setOnCheckedChangeListener(null)
            binding.lockSwitch.isChecked = item.isLocked
            binding.lockSwitch.setOnCheckedChangeListener { _, checked ->
                onLockToggle(item, checked)
            }

            // Click listener for expanding collections
            binding.contentCard.setOnClickListener {
                onItemClick(item)
            }
        }

        private fun bindVideo(item: HierarchicalItem.Video) {
            val video = item.video.video
            binding.titleText.text = video.title
            binding.iconImage.setImageResource(R.drawable.ic_movie)
            binding.expandIndicator.visibility = View.GONE

            // Show episode info if available
            binding.subtitleText.visibility = View.GONE
            binding.typeBadge.visibility = View.VISIBLE
            binding.typeBadge.text = "VIDEO"

            // If this is an episode within a locked parent, show special badge
            if (item.parentLocked && video.isEnabled) {
                binding.typeBadge.text = "UNLOCKED EPISODE"
                binding.typeBadge.setBackgroundResource(R.drawable.badge_unlocked_background)
            } else {
                binding.typeBadge.setBackgroundResource(R.drawable.badge_type_background)
            }
        }

        private fun bindCollection(item: HierarchicalItem.Collection) {
            val collection = item.collection.collection
            binding.titleText.text = collection.name
            binding.expandIndicator.visibility = View.VISIBLE

            // Rotate expand indicator based on expanded state
            binding.expandIndicator.rotation = if (item.isExpanded) 180f else 0f

            when {
                item.isTvShow -> {
                    binding.iconImage.setImageResource(R.drawable.ic_tv)
                    binding.subtitleText.visibility = View.VISIBLE
                    binding.subtitleText.text = "TV Show"
                    binding.typeBadge.visibility = View.VISIBLE
                    binding.typeBadge.text = "TV SHOW"
                    binding.typeBadge.setBackgroundResource(R.drawable.badge_type_background)
                }
                item.isSeason -> {
                    binding.iconImage.setImageResource(R.drawable.ic_folder)
                    binding.subtitleText.visibility = View.VISIBLE
                    binding.subtitleText.text = "${collection.videoCount} episodes"
                    binding.typeBadge.visibility = View.VISIBLE
                    binding.typeBadge.text = item.seasonNumber?.let { "SEASON $it" } ?: "SEASON"
                    binding.typeBadge.setBackgroundResource(R.drawable.badge_type_background)
                }
                else -> {
                    binding.iconImage.setImageResource(R.drawable.ic_folder)
                    binding.subtitleText.visibility = View.VISIBLE
                    binding.subtitleText.text = "${collection.videoCount} videos"
                    binding.typeBadge.visibility = View.VISIBLE
                    binding.typeBadge.text = "COLLECTION"
                    binding.typeBadge.setBackgroundResource(R.drawable.badge_type_background)
                }
            }
        }

        private fun updateLockUI(isLocked: Boolean, isExceptionToParent: Boolean) {
            if (isLocked) {
                binding.statusBadge.visibility = View.VISIBLE
                binding.statusBadge.text = binding.root.context.getString(R.string.locked).uppercase()
                binding.statusBadge.setBackgroundResource(R.drawable.badge_background)
                binding.iconImage.alpha = 0.5f
                binding.titleText.alpha = 0.7f
            } else {
                binding.statusBadge.visibility = View.GONE
                binding.iconImage.alpha = 1f
                binding.titleText.alpha = 1f
            }
        }
    }

    class ContentDiffCallback : DiffUtil.ItemCallback<HierarchicalItem>() {
        override fun areItemsTheSame(oldItem: HierarchicalItem, newItem: HierarchicalItem): Boolean {
            return when {
                oldItem is HierarchicalItem.Video && newItem is HierarchicalItem.Video ->
                    oldItem.video.firebaseKey == newItem.video.firebaseKey && oldItem.depth == newItem.depth
                oldItem is HierarchicalItem.Collection && newItem is HierarchicalItem.Collection ->
                    oldItem.collection.firebaseKey == newItem.collection.firebaseKey && oldItem.depth == newItem.depth
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: HierarchicalItem, newItem: HierarchicalItem): Boolean {
            return oldItem == newItem
        }
    }
}
