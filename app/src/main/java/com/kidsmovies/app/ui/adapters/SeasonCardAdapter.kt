package com.kidsmovies.app.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.kidsmovies.app.R
import com.kidsmovies.app.data.database.entities.VideoCollection
import com.kidsmovies.app.databinding.ItemSeasonCardBinding
import java.io.File

/**
 * Data class for a season with its episode count
 */
data class SeasonWithCount(
    val season: VideoCollection,
    val episodeCount: Int
)

/**
 * Adapter for displaying TV show seasons as cards with episode count badges.
 */
class SeasonCardAdapter(
    private val onSeasonClick: (VideoCollection) -> Unit,
    private val onSeasonLongClick: (VideoCollection) -> Boolean = { false }
) : ListAdapter<SeasonWithCount, SeasonCardAdapter.SeasonViewHolder>(SeasonDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SeasonViewHolder {
        val binding = ItemSeasonCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return SeasonViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SeasonViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SeasonViewHolder(
        private val binding: ItemSeasonCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(seasonWithCount: SeasonWithCount) {
            val season = seasonWithCount.season
            val context = binding.root.context

            // Set season name
            binding.seasonName.text = season.name

            // Set episode count
            val episodeText = context.resources.getQuantityString(
                R.plurals.episode_count,
                seasonWithCount.episodeCount,
                seasonWithCount.episodeCount
            )
            binding.episodeCount.text = episodeText

            // Load thumbnail if available
            val thumbnailPath = season.getDisplayThumbnail()
            if (thumbnailPath != null && File(thumbnailPath).exists()) {
                binding.defaultIcon.visibility = View.GONE
                binding.seasonPoster.visibility = View.VISIBLE
                Glide.with(binding.seasonPoster)
                    .load(File(thumbnailPath))
                    .placeholder(R.drawable.bg_thumbnail_placeholder)
                    .error(R.drawable.bg_thumbnail_placeholder)
                    .centerCrop()
                    .into(binding.seasonPoster)
            } else {
                binding.seasonPoster.visibility = View.GONE
                binding.defaultIcon.visibility = View.VISIBLE
            }

            // Click listeners
            binding.root.setOnClickListener {
                onSeasonClick(season)
            }

            binding.root.setOnLongClickListener {
                onSeasonLongClick(season)
            }
        }
    }

    private class SeasonDiffCallback : DiffUtil.ItemCallback<SeasonWithCount>() {
        override fun areItemsTheSame(oldItem: SeasonWithCount, newItem: SeasonWithCount): Boolean {
            return oldItem.season.id == newItem.season.id
        }

        override fun areContentsTheSame(oldItem: SeasonWithCount, newItem: SeasonWithCount): Boolean {
            return oldItem == newItem
        }
    }
}
