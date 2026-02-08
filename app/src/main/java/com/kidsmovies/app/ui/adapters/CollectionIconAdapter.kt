package com.kidsmovies.app.ui.adapters

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.kidsmovies.app.R
import com.kidsmovies.app.data.database.entities.VideoCollection
import com.kidsmovies.app.databinding.ItemCollectionIconBinding
import java.io.File
import java.util.Collections

class CollectionIconAdapter(
    private val onCollectionClick: (VideoCollection) -> Unit,
    private val onCollectionLongClick: (VideoCollection) -> Boolean = { false },
    private val onOrderChanged: ((List<VideoCollection>) -> Unit)? = null
) : ListAdapter<VideoCollection, CollectionIconAdapter.CollectionIconViewHolder>(CollectionDiffCallback()) {

    private var selectedCollectionId: Long? = null
    private var isReorderMode = false
    private var itemTouchHelper: ItemTouchHelper? = null
    private val mutableCollections = mutableListOf<VideoCollection>()

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

    fun attachToRecyclerView(recyclerView: RecyclerView) {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                if (fromPos != RecyclerView.NO_POSITION && toPos != RecyclerView.NO_POSITION) {
                    Collections.swap(mutableCollections, fromPos, toPos)
                    notifyItemMoved(fromPos, toPos)
                    return true
                }
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // Not used
            }

            override fun isLongPressDragEnabled(): Boolean = false

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // Notify order changed when drag ends
                onOrderChanged?.invoke(mutableCollections.toList())
            }
        }

        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper?.attachToRecyclerView(recyclerView)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setReorderMode(enabled: Boolean) {
        isReorderMode = enabled
        notifyDataSetChanged()
    }

    fun isInReorderMode() = isReorderMode

    override fun submitList(list: List<VideoCollection>?) {
        mutableCollections.clear()
        list?.let { mutableCollections.addAll(it) }
        super.submitList(list?.toList())
    }

    override fun getItem(position: Int): VideoCollection {
        return mutableCollections[position]
    }

    override fun getItemCount(): Int = mutableCollections.size

    fun startDrag(viewHolder: RecyclerView.ViewHolder) {
        itemTouchHelper?.startDrag(viewHolder)
    }

    inner class CollectionIconViewHolder(
        private val binding: ItemCollectionIconBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        @SuppressLint("ClickableViewAccessibility")
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

            // Show lock overlay if collection is disabled (parental lock)
            binding.lockOverlay.visibility = if (!collection.isEnabled) View.VISIBLE else View.GONE

            // Show/hide reorder handle based on mode
            binding.reorderHandle.visibility = if (isReorderMode) View.VISIBLE else View.GONE

            // Set up drag handle touch listener
            binding.reorderHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN && isReorderMode) {
                    startDrag(this)
                }
                false
            }

            // Highlight selected collection
            val isSelected = collection.id == selectedCollectionId
            binding.iconCard.strokeWidth = if (isSelected) 4 else 2
            binding.iconCard.alpha = if (selectedCollectionId != null && !isSelected) 0.5f else 1.0f

            // Click listeners
            binding.contentLayout.setOnClickListener {
                if (!isReorderMode) {
                    onCollectionClick(collection)
                }
            }

            binding.contentLayout.setOnLongClickListener {
                if (!isReorderMode) {
                    onCollectionLongClick(collection)
                } else {
                    false
                }
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
