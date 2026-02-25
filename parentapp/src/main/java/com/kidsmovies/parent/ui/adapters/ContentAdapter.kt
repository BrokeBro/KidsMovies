package com.kidsmovies.parent.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.kidsmovies.parent.R
import com.kidsmovies.parent.databinding.ItemContentBinding
import com.kidsmovies.parent.firebase.ChildCollection
import com.kidsmovies.parent.firebase.ChildVideo

sealed class ContentItem {
    data class Video(val video: ChildVideo) : ContentItem()
    data class Collection(val collection: ChildCollection) : ContentItem()
}

class ContentAdapter(
    private val onLockToggle: (ContentItem, Boolean) -> Unit
) : ListAdapter<ContentItem, ContentAdapter.ContentViewHolder>(ContentDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContentViewHolder {
        val binding = ItemContentBinding.inflate(
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
        private val binding: ItemContentBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ContentItem) {
            val context = binding.root.context

            when (item) {
                is ContentItem.Video -> {
                    val video = item.video.video
                    binding.titleText.text = video.title

                    // Show cloud icon for OneDrive videos, movie icon for local
                    if (video.sourceType == "onedrive") {
                        binding.iconImage.setImageResource(R.drawable.ic_cloud)
                    } else {
                        binding.iconImage.setImageResource(R.drawable.ic_movie)
                    }

                    // Show source and collections info
                    val parts = mutableListOf<String>()
                    if (video.sourceType == "onedrive") {
                        parts.add("OneDrive")
                    }
                    if (video.collectionNames.isNotEmpty()) {
                        parts.add(video.collectionNames.joinToString(", "))
                    }
                    if (parts.isNotEmpty()) {
                        binding.subtitleText.visibility = View.VISIBLE
                        binding.subtitleText.text = parts.joinToString(" • ")
                    } else {
                        binding.subtitleText.visibility = View.GONE
                    }

                    // Lock status
                    val isLocked = !video.isEnabled
                    updateLockUI(isLocked)

                    binding.lockSwitch.setOnCheckedChangeListener(null)
                    binding.lockSwitch.isChecked = isLocked
                    binding.lockSwitch.setOnCheckedChangeListener { _, checked ->
                        onLockToggle(item, checked)
                    }
                }

                is ContentItem.Collection -> {
                    val collection = item.collection.collection
                    binding.titleText.text = collection.name
                    binding.iconImage.setImageResource(R.drawable.ic_folder)

                    // Show video count
                    binding.subtitleText.visibility = View.VISIBLE
                    binding.subtitleText.text = "${collection.videoCount} videos • ${collection.type}"

                    // Lock status
                    val isLocked = !collection.isEnabled
                    updateLockUI(isLocked)

                    binding.lockSwitch.setOnCheckedChangeListener(null)
                    binding.lockSwitch.isChecked = isLocked
                    binding.lockSwitch.setOnCheckedChangeListener { _, checked ->
                        onLockToggle(item, checked)
                    }
                }
            }
        }

        private fun updateLockUI(isLocked: Boolean) {
            val context = binding.root.context

            if (isLocked) {
                binding.statusBadge.visibility = View.VISIBLE
                binding.statusBadge.text = context.getString(R.string.locked).uppercase()
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

    class ContentDiffCallback : DiffUtil.ItemCallback<ContentItem>() {
        override fun areItemsTheSame(oldItem: ContentItem, newItem: ContentItem): Boolean {
            return when {
                oldItem is ContentItem.Video && newItem is ContentItem.Video ->
                    oldItem.video.firebaseKey == newItem.video.firebaseKey
                oldItem is ContentItem.Collection && newItem is ContentItem.Collection ->
                    oldItem.collection.firebaseKey == newItem.collection.firebaseKey
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: ContentItem, newItem: ContentItem): Boolean {
            return oldItem == newItem
        }
    }
}
