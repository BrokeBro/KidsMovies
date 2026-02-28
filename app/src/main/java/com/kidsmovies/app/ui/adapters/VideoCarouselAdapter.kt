package com.kidsmovies.app.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.kidsmovies.app.R
import com.kidsmovies.app.data.database.entities.Video
import com.kidsmovies.app.databinding.ItemVideoCarouselBinding
import java.io.File

class VideoCarouselAdapter(
    private val onVideoClick: (Video) -> Unit,
    private val onVideoLongClick: ((Video) -> Unit)? = null
) : ListAdapter<Video, VideoCarouselAdapter.VideoViewHolder>(VideoDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val binding = ItemVideoCarouselBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VideoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VideoViewHolder(
        private val binding: ItemVideoCarouselBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(video: Video) {
            binding.videoTitle.text = video.title
            binding.durationBadge.text = video.getFormattedDuration()

            // Load thumbnail
            val thumbnailPath = video.getDisplayThumbnail()
            if (thumbnailPath != null && File(thumbnailPath).exists()) {
                Glide.with(binding.videoThumbnail)
                    .load(File(thumbnailPath))
                    .transform(CenterCrop(), RoundedCorners(8))
                    .placeholder(R.drawable.bg_thumbnail_placeholder)
                    .error(R.drawable.bg_thumbnail_placeholder)
                    .into(binding.videoThumbnail)
            } else {
                binding.videoThumbnail.setImageResource(R.drawable.bg_thumbnail_placeholder)
            }

            // Show cloud badge for online/OneDrive videos
            binding.cloudBadge.visibility = if (video.isRemote()) View.VISIBLE else View.GONE

            // Show favourite indicator
            binding.favouriteIndicator.visibility = if (video.isFavourite) View.VISIBLE else View.GONE

            // Show lock overlay if video is disabled (parental lock)
            val isLocked = !video.isEnabled
            binding.lockOverlay.visibility = if (isLocked) View.VISIBLE else View.GONE

            // Dim locked videos
            binding.videoThumbnail.alpha = if (isLocked) 0.4f else 1.0f
            binding.videoTitle.alpha = if (isLocked) 0.6f else 1.0f

            // Show watch progress bar
            if (video.playbackPosition > 0 && video.duration > 0) {
                val progressPercent = (video.playbackPosition.toFloat() / video.duration.toFloat()).coerceIn(0f, 1f)
                binding.progressBar.visibility = View.VISIBLE
                binding.progressBar.post {
                    val parentWidth = binding.videoThumbnail.width
                    val progressWidth = (parentWidth * progressPercent).toInt()
                    binding.progressBar.layoutParams = binding.progressBar.layoutParams.apply {
                        width = progressWidth
                    }
                }
            } else {
                binding.progressBar.visibility = View.GONE
            }

            // Click listener
            binding.videoCard.setOnClickListener {
                if (!isLocked) {
                    // Only allow playing unlocked videos
                    onVideoClick(video)
                }
                // If locked, clicking does nothing (video stays visible but unplayable)
            }

            // Long-click listener for download option on remote videos
            binding.videoCard.setOnLongClickListener {
                if (video.isRemote()) {
                    onVideoLongClick?.invoke(video)
                    true
                } else {
                    false
                }
            }
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
