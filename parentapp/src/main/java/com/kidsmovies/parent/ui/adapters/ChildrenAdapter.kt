package com.kidsmovies.parent.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.kidsmovies.parent.R
import com.kidsmovies.parent.databinding.ItemChildDeviceBinding
import com.kidsmovies.parent.firebase.ChildDevice
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChildrenAdapter(
    private val onChildClick: (ChildDevice) -> Unit,
    private val onRemoveChild: (ChildDevice) -> Unit
) : ListAdapter<ChildDevice, ChildrenAdapter.ChildViewHolder>(ChildDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChildViewHolder {
        val binding = ItemChildDeviceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ChildViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChildViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ChildViewHolder(
        private val binding: ItemChildDeviceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(child: ChildDevice) {
            val context = binding.root.context

            binding.childName.text = child.displayName

            // Online status
            if (child.isOnline) {
                binding.statusText.text = context.getString(R.string.child_online)
                binding.statusText.setTextColor(ContextCompat.getColor(context, R.color.status_online))
                binding.onlineIndicator.setBackgroundResource(R.drawable.circle_indicator)
            } else {
                binding.statusText.text = context.getString(R.string.child_offline)
                binding.statusText.setTextColor(ContextCompat.getColor(context, R.color.status_offline))
                binding.onlineIndicator.setBackgroundResource(R.drawable.circle_indicator_offline)
            }

            // Currently watching
            val currentlyWatching = child.deviceInfo.currentlyWatching
            if (!currentlyWatching.isNullOrBlank() && child.isOnline) {
                binding.watchingContainer.visibility = View.VISIBLE
                binding.watchingText.text = context.getString(R.string.currently_watching, currentlyWatching)
            } else {
                binding.watchingContainer.visibility = View.GONE
            }

            // Watch time
            val watchMinutes = child.deviceInfo.todayWatchTime
            binding.watchTimeText.text = context.getString(
                R.string.today_watch_time,
                formatWatchTime(watchMinutes)
            )

            // Last seen
            binding.lastSeenText.text = formatLastSeen(context, child.deviceInfo.lastSeen)

            // Click handlers
            binding.root.setOnClickListener { onChildClick(child) }

            binding.menuButton.setOnClickListener { view ->
                showPopupMenu(view, child)
            }
        }

        private fun showPopupMenu(view: View, child: ChildDevice) {
            val popup = PopupMenu(view.context, view)
            popup.menuInflater.inflate(R.menu.menu_child_item, popup.menu)
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.action_remove -> {
                        onRemoveChild(child)
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        private fun formatWatchTime(minutes: Long): String {
            return if (minutes >= 60) {
                val hours = minutes / 60
                val mins = minutes % 60
                binding.root.context.getString(R.string.time_format_hours, hours, mins)
            } else {
                binding.root.context.getString(R.string.time_format_minutes, minutes)
            }
        }

        private fun formatLastSeen(context: android.content.Context, timestamp: Long): String {
            if (timestamp == 0L) return ""

            val now = System.currentTimeMillis()
            val diff = now - timestamp

            return when {
                diff < 60_000 -> context.getString(R.string.just_now)
                diff < 3600_000 -> context.getString(R.string.minutes_ago, (diff / 60_000).toInt())
                diff < 86400_000 -> context.getString(R.string.hours_ago, (diff / 3600_000).toInt())
                diff < 172800_000 -> context.getString(R.string.yesterday)
                else -> {
                    val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())
                    dateFormat.format(Date(timestamp))
                }
            }
        }
    }

    class ChildDiffCallback : DiffUtil.ItemCallback<ChildDevice>() {
        override fun areItemsTheSame(oldItem: ChildDevice, newItem: ChildDevice): Boolean {
            return oldItem.childUid == newItem.childUid
        }

        override fun areContentsTheSame(oldItem: ChildDevice, newItem: ChildDevice): Boolean {
            return oldItem == newItem
        }
    }
}
