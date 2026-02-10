package com.kidsmovies.app.ui.adapters

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.kidsmovies.app.databinding.ItemNavigationTabBinding
import java.util.Collections

data class NavigationTab(
    val id: String,
    val displayName: String,
    var isEnabled: Boolean
)

class NavigationTabAdapter(
    private val onTabChanged: (NavigationTab) -> Unit,
    private val onOrderChanged: (List<NavigationTab>) -> Unit
) : RecyclerView.Adapter<NavigationTabAdapter.TabViewHolder>() {

    private val tabs = mutableListOf<NavigationTab>()
    private var itemTouchHelper: ItemTouchHelper? = null

    fun setItemTouchHelper(helper: ItemTouchHelper) {
        this.itemTouchHelper = helper
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(newTabs: List<NavigationTab>) {
        tabs.clear()
        tabs.addAll(newTabs)
        notifyDataSetChanged()
    }

    fun getTabs(): List<NavigationTab> = tabs.toList()

    fun moveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(tabs, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(tabs, i, i - 1)
            }
        }
        notifyItemMoved(fromPosition, toPosition)
    }

    fun onMoveComplete() {
        onOrderChanged(tabs.toList())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
        val binding = ItemNavigationTabBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return TabViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
        holder.bind(tabs[position])
    }

    override fun getItemCount(): Int = tabs.size

    @SuppressLint("ClickableViewAccessibility")
    inner class TabViewHolder(
        private val binding: ItemNavigationTabBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    itemTouchHelper?.startDrag(this)
                }
                false
            }
        }

        fun bind(tab: NavigationTab) {
            binding.tabCheckbox.text = tab.displayName
            binding.tabCheckbox.setOnCheckedChangeListener(null)
            binding.tabCheckbox.isChecked = tab.isEnabled
            binding.tabCheckbox.setOnCheckedChangeListener { _, isChecked ->
                tab.isEnabled = isChecked
                onTabChanged(tab)
            }
        }
    }
}

class NavigationTabTouchHelper(
    private val adapter: NavigationTabAdapter
) : ItemTouchHelper.Callback() {

    override fun isLongPressDragEnabled(): Boolean = false

    override fun isItemViewSwipeEnabled(): Boolean = false

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int {
        val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN
        return makeMovementFlags(dragFlags, 0)
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        adapter.moveItem(viewHolder.adapterPosition, target.adapterPosition)
        return true
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        // Not used
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        adapter.onMoveComplete()
    }
}
