package com.kidsmovies.app.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.kidsmovies.app.R
import com.kidsmovies.app.data.database.entities.Video
import com.kidsmovies.app.databinding.ItemVideoCardBinding
import java.io.File

class VideoAdapter(
    private val onVideoClick: (Video) -> Unit,
    private val onFavouriteClick: (Video) -> Unit,
    private val onVideoLongClick: ((Video) -> Boolean)? = null,
    private val onSelectionChanged: ((Set<Long>) -> Unit)? = null,
    private val onOptionsClick: ((Video) -> Unit)? = null
) : ListAdapter<Video, VideoAdapter.VideoViewHolder>(VideoDiffCallback()) {

    private var isSelectionMode = false
    private val selectedVideoIds = mutableSetOf<Long>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val binding = ItemVideoCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VideoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun enterSelectionMode() {
        isSelectionMode = true
        notifyDataSetChanged()
    }

    fun exitSelectionMode() {
        isSelectionMode = false
        selectedVideoIds.clear()
        onSelectionChanged?.invoke(selectedVideoIds)
        notifyDataSetChanged()
    }

    fun isInSelectionMode() = isSelectionMode

    fun getSelectedVideoIds(): Set<Long> = selectedVideoIds.toSet()

    fun selectAll() {
        selectedVideoIds.clear()
        currentList.forEach { selectedVideoIds.add(it.id) }
        onSelectionChanged?.invoke(selectedVideoIds)
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectedVideoIds.clear()
        onSelectionChanged?.invoke(selectedVideoIds)
        notifyDataSetChanged()
    }

    private fun toggleSelection(videoId: Long) {
        if (selectedVideoIds.contains(videoId)) {
            selectedVideoIds.remove(videoId)
        } else {
            selectedVideoIds.add(videoId)
        }
        onSelectionChanged?.invoke(selectedVideoIds)
        
        // Exit selection mode if nothing is selected
        if (selectedVideoIds.isEmpty()) {
            exitSelectionMode()
        }
    }

    inner class VideoViewHolder(
        private val binding: ItemVideoCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(video: Video) {
            binding.videoTitle.text = video.title
            binding.durationBadge.text = video.getFormattedDuration()

            // Load thumbnail
            val thumbnailPath = video.getDisplayThumbnail()
            if (thumbnailPath != null && File(thumbnailPath).exists()) {
                Glide.with(binding.videoThumbnail)
                    .load(File(thumbnailPath))
                    .transform(CenterCrop(), RoundedCorners(16))
                    .placeholder(R.drawable.bg_thumbnail_placeholder)
                    .error(R.drawable.bg_thumbnail_placeholder)
                    .into(binding.videoThumbnail)
            } else {
                binding.videoThumbnail.setImageResource(R.drawable.bg_thumbnail_placeholder)
            }

            // Set favourite icon with proper color
            if (video.isFavourite) {
                binding.favouriteButton.setImageResource(R.drawable.ic_favourite_filled)
                binding.favouriteButton.setColorFilter(
                    ContextCompat.getColor(binding.root.context, R.color.favourite_active)
                )
            } else {
                binding.favouriteButton.setImageResource(R.drawable.ic_favourite)
                binding.favouriteButton.setColorFilter(
                    ContextCompat.getColor(binding.root.context, R.color.favourite_inactive)
                )
            }

            // Show watch progress bar
            if (video.playbackPosition > 0 && video.duration > 0) {
                val progressPercent = (video.playbackPosition.toFloat() / video.duration.toFloat()).coerceIn(0f, 1f)
                binding.progressBar.visibility = View.VISIBLE
                // Set width as percentage of parent
                binding.progressBar.post {
                    val parentWidth = (binding.progressBar.parent as? View)?.width ?: 0
                    val progressWidth = (parentWidth * progressPercent).toInt()
                    binding.progressBar.layoutParams = binding.progressBar.layoutParams.apply {
                        width = progressWidth
                    }
                }
            } else {
                binding.progressBar.visibility = View.GONE
            }

            // Show lock overlay if video is disabled (parental lock)
            binding.lockOverlay.visibility = if (!video.isEnabled) View.VISIBLE else View.GONE

            // Handle selection mode UI
            val isSelected = selectedVideoIds.contains(video.id)
            binding.selectionCheckbox.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
            binding.selectionCheckbox.isChecked = isSelected
            binding.videoCard.alpha = if (isSelectionMode && !isSelected) 0.6f else 1.0f

            // Click listeners
            binding.videoCard.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(video.id)
                    notifyItemChanged(bindingAdapterPosition)
                } else {
                    onVideoClick(video)
                }
            }

            binding.videoCard.setOnLongClickListener {
                if (!isSelectionMode) {
                    // Enter selection mode and select this item
                    isSelectionMode = true
                    selectedVideoIds.add(video.id)
                    onSelectionChanged?.invoke(selectedVideoIds)
                    notifyDataSetChanged()
                    onVideoLongClick?.invoke(video) ?: true
                } else {
                    true
                }
            }

            binding.favouriteButton.setOnClickListener {
                if (!isSelectionMode) {
                    onFavouriteClick(video)
                }
            }

            binding.optionsButton.setOnClickListener {
                if (!isSelectionMode) {
                    onOptionsClick?.invoke(video)
                }
            }

            // Hide favourite and options buttons in selection mode
            binding.favouriteButton.visibility = if (isSelectionMode) View.GONE else View.VISIBLE
            binding.optionsButton.visibility = if (isSelectionMode) View.GONE else View.VISIBLE
        }
    }

    private class VideoDiffCallback : DiffUtil.ItemCallback<Video>() {
        override fun areItemsTheSame(oldItem: Video, newItem: Video): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Video, newItem: Video): Boolean {
            return oldItem == newItem
        }
    }
}
