package com.kidsmovies.app.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
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
    private val onFavouriteClick: (Video) -> Unit
) : ListAdapter<Video, VideoAdapter.VideoViewHolder>(VideoDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val binding = ItemVideoCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VideoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(getItem(position))
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

            // Set favourite icon
            binding.favouriteButton.setImageResource(
                if (video.isFavourite) R.drawable.ic_favourite_filled
                else R.drawable.ic_favourite
            )

            // Click listeners
            binding.videoCard.setOnClickListener {
                onVideoClick(video)
            }

            binding.favouriteButton.setOnClickListener {
                onFavouriteClick(video)
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
