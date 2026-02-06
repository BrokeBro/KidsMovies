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

data class CollectionWithVideos(
    val collection: VideoCollection,
    val videos: List<Video>
)

class CollectionRowAdapter(
    private val onVideoClick: (Video) -> Unit,
    private val onCollectionClick: (VideoCollection) -> Unit
) : ListAdapter<CollectionWithVideos, CollectionRowAdapter.CollectionViewHolder>(CollectionDiffCallback()) {

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

        private val videoAdapter = VideoCarouselAdapter { video ->
            onVideoClick(video)
        }

        init {
            binding.videosRecyclerView.apply {
                adapter = videoAdapter
                layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
                setRecycledViewPool(viewPool)
                setHasFixedSize(true)
            }
        }

        fun bind(collectionWithVideos: CollectionWithVideos) {
            val collection = collectionWithVideos.collection
            val videos = collectionWithVideos.videos

            binding.collectionName.text = collection.name
            binding.videoCount.text = binding.root.context.getString(
                R.string.videos_in_collection,
                videos.size
            )

            videoAdapter.submitList(videos)

            // Click on collection header to view full collection
            binding.collectionHeader.setOnClickListener {
                onCollectionClick(collection)
            }
        }
    }

    private class CollectionDiffCallback : DiffUtil.ItemCallback<CollectionWithVideos>() {
        override fun areItemsTheSame(oldItem: CollectionWithVideos, newItem: CollectionWithVideos): Boolean {
            return oldItem.collection.id == newItem.collection.id
        }

        override fun areContentsTheSame(oldItem: CollectionWithVideos, newItem: CollectionWithVideos): Boolean {
            return oldItem == newItem
        }
    }
}
