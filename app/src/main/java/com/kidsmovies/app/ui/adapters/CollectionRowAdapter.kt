package com.kidsmovies.app.ui.adapters

import android.os.Parcelable
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
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
    private val onSeasonClick: ((VideoCollection) -> Unit)? = null,
    private val onVideoLongClick: ((Video) -> Unit)? = null
) : ListAdapter<CollectionRowItem, CollectionRowAdapter.CollectionViewHolder>(CollectionDiffCallback()) {

    // Shared pool for the nested carousels. VideoCarouselAdapter and SeasonCardAdapter
    // return distinct view types, so holders are never handed to the wrong adapter.
    private val viewPool = RecyclerView.RecycledViewPool()

    // Horizontal scroll position per row, so rows keep their place when scrolled off-screen
    private val scrollStates = mutableMapOf<Long, Parcelable?>()

    // Enum to track which adapter type is currently set on the RecyclerView
    private enum class AdapterType { VIDEO, SEASON }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CollectionViewHolder {
        val binding = ItemCollectionRowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return CollectionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CollectionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: CollectionViewHolder) {
        super.onViewRecycled(holder)
        holder.saveScrollState()
    }

    inner class CollectionViewHolder(
        private val binding: ItemCollectionRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentCollection: VideoCollection? = null

        private val videoAdapter = VideoCarouselAdapter(
            onVideoClick = { video ->
                currentCollection?.let { collection ->
                    onVideoClick(video, collection)
                }
            },
            onVideoLongClick = onVideoLongClick
        )

        private val seasonAdapter = SeasonCardAdapter(
            onSeasonClick = { season ->
                onSeasonClick?.invoke(season) ?: onCollectionClick(season)
            }
        )

        private var currentAdapterType: AdapterType? = null

        init {
            binding.videosRecyclerView.apply {
                layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false).apply {
                    initialPrefetchItemCount = 4
                    recycleChildrenOnDetach = true
                }
                setRecycledViewPool(viewPool)
                isNestedScrollingEnabled = false
                itemAnimator = null
                // Set default adapter - will be swapped if needed in bind
                adapter = videoAdapter

                // Only intercept parent scrolling when the user is swiping horizontally.
                // Vertical swipes should pass through to the parent for scrolling up/down.
                addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
                    private var startX = 0f
                    private var startY = 0f

                    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                        when (e.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                startX = e.x
                                startY = e.y
                                // Tentatively claim the touch
                                rv.parent?.requestDisallowInterceptTouchEvent(true)
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val dx = Math.abs(e.x - startX)
                                val dy = Math.abs(e.y - startY)
                                // Only keep horizontal lock if movement is primarily horizontal
                                rv.parent?.requestDisallowInterceptTouchEvent(dx > dy)
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                rv.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                        }
                        return false
                    }
                    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}
                    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
                })
            }
            currentAdapterType = AdapterType.VIDEO
        }

        fun saveScrollState() {
            currentCollection?.let {
                scrollStates[it.id] = binding.videosRecyclerView.layoutManager?.onSaveInstanceState()
            }
        }

        fun bind(rowItem: CollectionRowItem) {
            val collection = rowItem.collection
            val isNewRow = currentCollection?.id != collection.id
            if (isNewRow) saveScrollState()
            currentCollection = collection

            binding.collectionName.text = collection.name

            when (rowItem) {
                is CollectionRowItem.VideosRow -> bindVideosRow(rowItem)
                is CollectionRowItem.SeasonsRow -> bindSeasonsRow(rowItem)
            }

            if (isNewRow) {
                val state = scrollStates[collection.id]
                val lm = binding.videosRecyclerView.layoutManager
                if (state != null) lm?.onRestoreInstanceState(state) else lm?.scrollToPosition(0)
            }

            // Virtual rows (e.g. Continue Watching) have no detail page
            val isVirtual = collection.id < 0
            binding.headerChevron.visibility = if (isVirtual) View.GONE else View.VISIBLE
            binding.collectionHeader.isClickable = !isVirtual
            binding.collectionHeader.setOnClickListener(
                if (isVirtual) null else View.OnClickListener { onCollectionClick(collection) }
            )
        }

        private fun bindVideosRow(row: CollectionRowItem.VideosRow) {
            val context = binding.root.context

            binding.videoCount.text = if (row.collection.id < 0) "" else context.getString(
                R.string.videos_in_collection,
                row.videos.size
            )

            // Switch to video adapter if needed
            if (currentAdapterType != AdapterType.VIDEO) {
                binding.videosRecyclerView.adapter = videoAdapter
                currentAdapterType = AdapterType.VIDEO
            }

            videoAdapter.submitList(row.videos)
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

            seasonAdapter.submitList(row.seasons)
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
