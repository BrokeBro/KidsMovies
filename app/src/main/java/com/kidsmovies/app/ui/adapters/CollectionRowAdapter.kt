package com.kidsmovies.app.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.kidsmovies.app.R
import com.kidsmovies.app.data.database.entities.Video
import com.kidsmovies.app.data.database.entities.VideoCollection
import com.kidsmovies.app.databinding.ItemCollectionRowBinding

/**
 * Represents a collection row - either a regular collection with videos
 * or a TV show with seasons.
 */
sealed class CollectionRowItem {
    abstract val collection: VideoCollection

    /** Regular collection with video items */
    data class VideosRow(
        override val collection: VideoCollection,
        val videos: List<Video>
    ) : CollectionRowItem()

    /** TV Show with season items */
    data class SeasonsRow(
        override val collection: VideoCollection,
        val seasons: List<SeasonWithCount>
    ) : CollectionRowItem()
}

// Keep for backwards compatibility
data class CollectionWithVideos(
    val collection: VideoCollection,
    val videos: List<Video>
) {
    fun toRowItem(): CollectionRowItem = CollectionRowItem.VideosRow(collection, videos)
}

class CollectionRowAdapter(
    private val onVideoClick: (Video, VideoCollection) -> Unit,
    private val onCollectionClick: (VideoCollection) -> Unit,
    private val onSeasonClick: ((VideoCollection) -> Unit)? = null
) : ListAdapter<CollectionRowItem, CollectionRowAdapter.CollectionViewHolder>(CollectionDiffCallback()) {

    // Cache for RecyclerView pools to improve performance
    private val viewPool = RecyclerView.RecycledViewPool()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CollectionViewHolder {
        val binding = ItemCollectionRowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return CollectionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CollectionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class CollectionViewHolder(
        private val binding: ItemCollectionRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentCollection: VideoCollection? = null

        private val videoAdapter = VideoCarouselAdapter { video ->
            currentCollection?.let { collection ->
                onVideoClick(video, collection)
            }
        }

        private val seasonAdapter = SeasonCardAdapter(
            onSeasonClick = { season ->
                onSeasonClick?.invoke(season) ?: onCollectionClick(season)
            }
        )

        private var currentAdapterType: AdapterType = AdapterType.VIDEO

        private enum class AdapterType { VIDEO, SEASON }

        init {
            binding.videosRecyclerView.apply {
                layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
                setRecycledViewPool(viewPool)
            }
        }

        fun bind(rowItem: CollectionRowItem) {
            val collection = rowItem.collection
            currentCollection = collection

            binding.collectionName.text = collection.name

            when (rowItem) {
                is CollectionRowItem.VideosRow -> bindVideosRow(rowItem)
                is CollectionRowItem.SeasonsRow -> bindSeasonsRow(rowItem)
            }

            // Click on collection header to view full collection/TV show
            binding.collectionHeader.setOnClickListener {
                onCollectionClick(collection)
            }
        }

        private fun bindVideosRow(row: CollectionRowItem.VideosRow) {
            val context = binding.root.context

            binding.videoCount.text = context.getString(
                R.string.videos_in_collection,
                row.videos.size
            )

            // Switch to video adapter if needed
            if (currentAdapterType != AdapterType.VIDEO) {
                binding.videosRecyclerView.adapter = videoAdapter
                currentAdapterType = AdapterType.VIDEO
            }

            videoAdapter.submitList(row.videos.toList())
        }

        private fun bindSeasonsRow(row: CollectionRowItem.SeasonsRow) {
            val context = binding.root.context
            val seasonCount = row.seasons.size

            // Show season count and "View All Seasons" text
            binding.videoCount.text = context.resources.getQuantityString(
                R.plurals.season_count,
                seasonCount,
                seasonCount
            )

            // Switch to season adapter if needed
            if (currentAdapterType != AdapterType.SEASON) {
                binding.videosRecyclerView.adapter = seasonAdapter
                currentAdapterType = AdapterType.SEASON
            }

            seasonAdapter.submitList(row.seasons.toList())
        }
    }

    private class CollectionDiffCallback : DiffUtil.ItemCallback<CollectionRowItem>() {
        override fun areItemsTheSame(oldItem: CollectionRowItem, newItem: CollectionRowItem): Boolean {
            return oldItem.collection.id == newItem.collection.id
        }

        override fun areContentsTheSame(oldItem: CollectionRowItem, newItem: CollectionRowItem): Boolean {
            return oldItem == newItem
        }
    }

    // Helper to submit a list of CollectionWithVideos (backwards compatibility)
    fun submitVideosList(list: List<CollectionWithVideos>) {
        submitList(list.map { it.toRowItem() })
    }
}
