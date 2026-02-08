package com.kidsmovies.app.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.kidsmovies.app.R
import com.kidsmovies.app.data.database.entities.VideoCollection
import com.kidsmovies.app.databinding.ItemCollectionIconBinding
import java.io.File

class CollectionIconAdapter(
    private val onCollectionClick: (VideoCollection) -> Unit,
    private val onCollectionLongClick: (VideoCollection) -> Boolean = { false }
) : ListAdapter<VideoCollection, CollectionIconAdapter.CollectionIconViewHolder>(CollectionDiffCallback()) {

    private var selectedCollectionId: Long? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CollectionIconViewHolder {
        val binding = ItemCollectionIconBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return CollectionIconViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CollectionIconViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun setSelectedCollection(collectionId: Long?) {
        val oldSelectedId = selectedCollectionId
        selectedCollectionId = collectionId

        // Refresh the old and new selected items
        currentList.forEachIndexed { index, collection ->
            if (collection.id == oldSelectedId || collection.id == collectionId) {
                notifyItemChanged(index)
            }
        }
    }

    inner class CollectionIconViewHolder(
        private val binding: ItemCollectionIconBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(collection: VideoCollection) {
            binding.collectionName.text = collection.name

            // Load thumbnail if available (custom or TMDB)
            val thumbnailPath = collection.getDisplayThumbnail()
            if (thumbnailPath != null && File(thumbnailPath).exists()) {
                binding.defaultIcon.visibility = View.GONE
                binding.collectionIcon.visibility = View.VISIBLE
                Glide.with(binding.collectionIcon)
                    .load(File(thumbnailPath))
                    .transform(CircleCrop())
                    .placeholder(R.drawable.bg_thumbnail_placeholder)
                    .error(R.drawable.bg_thumbnail_placeholder)
                    .into(binding.collectionIcon)
            } else {
                binding.collectionIcon.visibility = View.GONE
                binding.defaultIcon.visibility = View.VISIBLE
            }

            // Highlight selected collection
            val isSelected = collection.id == selectedCollectionId
            binding.iconCard.strokeWidth = if (isSelected) 4 else 2
            binding.iconCard.alpha = if (selectedCollectionId != null && !isSelected) 0.5f else 1.0f

            // Click listeners
            binding.root.setOnClickListener {
                onCollectionClick(collection)
            }

            binding.root.setOnLongClickListener {
                onCollectionLongClick(collection)
            }
        }
    }

    private class CollectionDiffCallback : DiffUtil.ItemCallback<VideoCollection>() {
        override fun areItemsTheSame(oldItem: VideoCollection, newItem: VideoCollection): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: VideoCollection, newItem: VideoCollection): Boolean {
            return oldItem == newItem
        }
    }
}
