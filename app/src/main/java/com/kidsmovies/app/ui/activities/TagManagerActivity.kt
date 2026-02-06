package com.kidsmovies.app.ui.activities

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kidsmovies.app.KidsMoviesApp
import com.kidsmovies.app.R
import com.kidsmovies.app.data.database.entities.Tag
import com.kidsmovies.app.databinding.ActivityTagManagerBinding
import com.kidsmovies.app.databinding.ItemTagBinding
import com.kidsmovies.app.utils.ThemeManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TagManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTagManagerBinding
    private lateinit var app: KidsMoviesApp
    private lateinit var tagAdapter: TagAdapter

    private val tagColors = listOf(
        "#F44336", "#E91E63", "#9C27B0", "#673AB7",
        "#3F51B5", "#2196F3", "#03A9F4", "#00BCD4",
        "#009688", "#4CAF50", "#8BC34A", "#CDDC39",
        "#FFEB3B", "#FFC107", "#FF9800", "#FF5722"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTagManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        app = application as KidsMoviesApp

        setupToolbar()
        setupRecyclerView()
        setupFab()
        observeTags()
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            ThemeManager.applyTheme(this@TagManagerActivity)
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupRecyclerView() {
        tagAdapter = TagAdapter(
            onEditClick = { tag -> showEditTagDialog(tag) },
            onDeleteClick = { tag -> confirmDeleteTag(tag) }
        )

        binding.tagsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@TagManagerActivity)
            adapter = tagAdapter
        }
    }

    private fun setupFab() {
        binding.addTagFab.setOnClickListener {
            showAddTagDialog()
        }
    }

    private fun observeTags() {
        lifecycleScope.launch {
            app.tagRepository.getUserTagsFlow().collectLatest { tags ->
                // Get video counts for each tag
                val tagsWithCounts = tags.map { tag ->
                    val count = app.tagRepository.getVideoCountForTag(tag.id)
                    TagWithCount(tag, count)
                }

                tagAdapter.submitList(tagsWithCounts)

                if (tags.isEmpty()) {
                    binding.emptyState.visibility = View.VISIBLE
                    binding.tagsRecyclerView.visibility = View.GONE
                } else {
                    binding.emptyState.visibility = View.GONE
                    binding.tagsRecyclerView.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun showAddTagDialog() {
        showTagDialog(null)
    }

    private fun showEditTagDialog(tag: Tag) {
        showTagDialog(tag)
    }

    private fun showTagDialog(existingTag: Tag?) {
        val isEdit = existingTag != null
        var selectedColor = existingTag?.color ?: tagColors.first()

        val dialogView = layoutInflater.inflate(R.layout.dialog_tag_edit, null)
        val nameInput = dialogView.findViewById<EditText>(R.id.tagNameInput)
        val colorContainer = dialogView.findViewById<LinearLayout>(R.id.colorContainer)

        nameInput.setText(existingTag?.name ?: "")

        // Create color options
        colorContainer.removeAllViews()
        tagColors.forEach { color ->
            val colorView = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(48.dpToPx(), 48.dpToPx()).apply {
                    marginEnd = 8.dpToPx()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(color))
                    if (color == selectedColor) {
                        setStroke(4.dpToPx(), Color.WHITE)
                    }
                }
                setOnClickListener {
                    selectedColor = color
                    // Update selection UI
                    for (i in 0 until colorContainer.childCount) {
                        val child = colorContainer.getChildAt(i)
                        (child.background as? GradientDrawable)?.setStroke(
                            if (i == colorContainer.indexOfChild(this)) 4.dpToPx() else 0,
                            Color.WHITE
                        )
                    }
                }
            }
            colorContainer.addView(colorView)
        }

        AlertDialog.Builder(this, R.style.Theme_KidsMovies_Dialog)
            .setTitle(if (isEdit) R.string.edit_tag else R.string.add_tag)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch {
                        if (isEdit) {
                            app.tagRepository.updateTag(
                                existingTag!!.copy(name = name, color = selectedColor)
                            )
                        } else {
                            app.tagRepository.insertTag(
                                Tag(name = name, color = selectedColor)
                            )
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteTag(tag: Tag) {
        AlertDialog.Builder(this, R.style.Theme_KidsMovies_Dialog)
            .setTitle(R.string.delete_tag)
            .setMessage("Are you sure you want to delete \"${tag.name}\"?")
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    app.tagRepository.deleteTag(tag)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    data class TagWithCount(
        val tag: Tag,
        val videoCount: Int
    )

    private inner class TagAdapter(
        private val onEditClick: (Tag) -> Unit,
        private val onDeleteClick: (Tag) -> Unit
    ) : RecyclerView.Adapter<TagAdapter.ViewHolder>() {

        private var tags = listOf<TagWithCount>()

        fun submitList(list: List<TagWithCount>) {
            tags = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemTagBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(tags[position])
        }

        override fun getItemCount(): Int = tags.size

        inner class ViewHolder(private val binding: ItemTagBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(tagWithCount: TagWithCount) {
                val tag = tagWithCount.tag

                binding.tagName.text = tag.name
                binding.videoCount.text = "${tagWithCount.videoCount} videos"

                if (tag.description.isNotEmpty()) {
                    binding.tagDescription.text = tag.description
                    binding.tagDescription.visibility = View.VISIBLE
                } else {
                    binding.tagDescription.visibility = View.GONE
                }

                // Set tag color
                val drawable = binding.tagColor.background as? GradientDrawable
                    ?: GradientDrawable()
                drawable.setColor(Color.parseColor(tag.color))
                drawable.shape = GradientDrawable.OVAL
                binding.tagColor.background = drawable

                // Hide edit/delete for system tags
                if (tag.isSystemTag) {
                    binding.editButton.visibility = View.GONE
                    binding.deleteButton.visibility = View.GONE
                } else {
                    binding.editButton.visibility = View.VISIBLE
                    binding.deleteButton.visibility = View.VISIBLE

                    binding.editButton.setOnClickListener {
                        onEditClick(tag)
                    }

                    binding.deleteButton.setOnClickListener {
                        onDeleteClick(tag)
                    }
                }
            }
        }
    }
}
