package com.kidsmovies.app.ui.dialogs

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.kidsmovies.app.KidsMoviesApp
import com.kidsmovies.app.R
import com.kidsmovies.app.data.database.entities.Video
import com.kidsmovies.app.data.database.entities.VideoCollection
import com.kidsmovies.app.databinding.BottomSheetVideoOptionsBinding
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class VideoOptionsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetVideoOptionsBinding? = null
    private val binding get() = _binding!!

    private lateinit var app: KidsMoviesApp
    private var video: Video? = null

    private var onVideoUpdated: ((Video) -> Unit)? = null
    private var onVideoRemoved: ((Video) -> Unit)? = null
    private var onAddToCollection: ((Video) -> Unit)? = null
    private var onRemoveFromCollection: ((Video, VideoCollection) -> Unit)? = null

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                saveCustomThumbnail(uri)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetVideoOptionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        app = requireActivity().application as KidsMoviesApp

        setupUI()
        setupClickListeners()
    }

    private fun setupUI() {
        video?.let { v ->
            // Set video title and duration
            binding.videoTitle.text = v.title
            binding.videoDuration.text = v.getFormattedDuration()

            // Load thumbnail
            val thumbnailPath = v.getDisplayThumbnail()
            if (thumbnailPath != null && File(thumbnailPath).exists()) {
                Glide.with(binding.videoThumbnail)
                    .load(File(thumbnailPath))
                    .transform(CenterCrop(), RoundedCorners(8))
                    .placeholder(R.drawable.bg_thumbnail_placeholder)
                    .into(binding.videoThumbnail)
            }

            // Show/hide reset thumbnail option based on whether custom thumbnail exists
            binding.resetThumbnailOption.visibility =
                if (v.customThumbnailPath != null) View.VISIBLE else View.GONE

            // Update favourite icon and text
            updateFavouriteUI(v.isFavourite)

            // Check if video is in any collections and show/hide remove from collection option
            checkVideoCollections(v)
        }
    }

    private fun checkVideoCollections(v: Video) {
        viewLifecycleOwner.lifecycleScope.launch {
            val allCollections = app.collectionRepository.getAllCollections()
            val containingCollections = allCollections.filter { collection ->
                app.collectionRepository.isVideoInCollection(v.id, collection.id)
            }

            binding.removeFromCollectionOption.visibility =
                if (containingCollections.isNotEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun updateFavouriteUI(isFavourite: Boolean) {
        if (isFavourite) {
            binding.favouriteIcon.setImageResource(R.drawable.ic_favourite_filled)
            binding.favouriteIcon.setColorFilter(
                ContextCompat.getColor(requireContext(), R.color.favourite_active)
            )
            binding.favouriteText.text = getString(R.string.remove_from_favourites)
        } else {
            binding.favouriteIcon.setImageResource(R.drawable.ic_favourite)
            binding.favouriteIcon.setColorFilter(
                ContextCompat.getColor(requireContext(), R.color.text_secondary)
            )
            binding.favouriteText.text = getString(R.string.add_to_favourites)
        }
    }

    private fun setupClickListeners() {
        // Change thumbnail
        binding.changeThumbnailOption.setOnClickListener {
            openImagePicker()
        }

        // Reset thumbnail
        binding.resetThumbnailOption.setOnClickListener {
            resetThumbnail()
        }

        // Add to collection
        binding.addToCollectionOption.setOnClickListener {
            video?.let { v ->
                onAddToCollection?.invoke(v)
                dismiss()
            }
        }

        // Remove from collection
        binding.removeFromCollectionOption.setOnClickListener {
            video?.let { v ->
                showRemoveFromCollectionDialog(v)
            }
        }

        // Toggle favourite
        binding.toggleFavouriteOption.setOnClickListener {
            toggleFavourite()
        }

        // Remove from library
        binding.removeOption.setOnClickListener {
            video?.let { v ->
                onVideoRemoved?.invoke(v)
                dismiss()
            }
        }
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_PICK).apply {
            type = "image/*"
        }
        imagePickerLauncher.launch(Intent.createChooser(intent, getString(R.string.select_image)))
    }

    private fun saveCustomThumbnail(uri: Uri) {
        video?.let { v ->
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    // Create custom thumbnails directory
                    val thumbnailDir = File(requireContext().filesDir, "custom_thumbnails")
                    if (!thumbnailDir.exists()) {
                        thumbnailDir.mkdirs()
                    }

                    // Save the image with a unique filename based on video ID
                    val thumbnailFile = File(thumbnailDir, "thumb_${v.id}.jpg")

                    requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(thumbnailFile).use { output ->
                            input.copyTo(output)
                        }
                    }

                    // Update the video in database
                    app.videoRepository.updateCustomThumbnail(v.id, thumbnailFile.absolutePath)

                    // Update local state
                    video = v.copy(customThumbnailPath = thumbnailFile.absolutePath)
                    setupUI()

                    Toast.makeText(
                        requireContext(),
                        R.string.thumbnail_changed,
                        Toast.LENGTH_SHORT
                    ).show()

                    onVideoUpdated?.invoke(video!!)

                } catch (e: Exception) {
                    Toast.makeText(
                        requireContext(),
                        R.string.error,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun resetThumbnail() {
        video?.let { v ->
            viewLifecycleOwner.lifecycleScope.launch {
                // Delete the custom thumbnail file if it exists
                v.customThumbnailPath?.let { path ->
                    val file = File(path)
                    if (file.exists()) {
                        file.delete()
                    }
                }

                // Clear custom thumbnail in database
                app.videoRepository.updateCustomThumbnail(v.id, null)

                // Update local state
                video = v.copy(customThumbnailPath = null)
                setupUI()

                Toast.makeText(
                    requireContext(),
                    R.string.thumbnail_reset,
                    Toast.LENGTH_SHORT
                ).show()

                onVideoUpdated?.invoke(video!!)
            }
        }
    }

    private fun toggleFavourite() {
        video?.let { v ->
            viewLifecycleOwner.lifecycleScope.launch {
                val newFavouriteState = !v.isFavourite
                app.videoRepository.updateFavourite(v.id, newFavouriteState)

                // Update local state
                video = v.copy(isFavourite = newFavouriteState)
                updateFavouriteUI(newFavouriteState)

                onVideoUpdated?.invoke(video!!)
            }
        }
    }

    private fun showRemoveFromCollectionDialog(v: Video) {
        viewLifecycleOwner.lifecycleScope.launch {
            val allCollections = app.collectionRepository.getAllCollections()
            val containingCollections = allCollections.filter { collection ->
                app.collectionRepository.isVideoInCollection(v.id, collection.id)
            }

            if (containingCollections.isEmpty()) {
                Toast.makeText(requireContext(), "Video is not in any collection", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val collectionNames = containingCollections.map { it.name }.toTypedArray()

            AlertDialog.Builder(requireContext(), R.style.Theme_KidsMovies_Dialog)
                .setTitle(R.string.remove_from_collection)
                .setItems(collectionNames) { _, which ->
                    val selectedCollection = containingCollections[which]
                    removeFromCollection(v, selectedCollection)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun removeFromCollection(v: Video, collection: VideoCollection) {
        viewLifecycleOwner.lifecycleScope.launch {
            app.collectionRepository.removeVideoFromCollection(collection.id, v.id)

            Toast.makeText(
                requireContext(),
                "Removed from \"${collection.name}\"",
                Toast.LENGTH_SHORT
            ).show()

            onRemoveFromCollection?.invoke(v, collection)
            onVideoUpdated?.invoke(v)

            // Update the UI to reflect the change
            checkVideoCollections(v)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "VideoOptionsBottomSheet"

        fun newInstance(
            video: Video,
            onVideoUpdated: ((Video) -> Unit)? = null,
            onVideoRemoved: ((Video) -> Unit)? = null,
            onAddToCollection: ((Video) -> Unit)? = null,
            onRemoveFromCollection: ((Video, VideoCollection) -> Unit)? = null
        ): VideoOptionsBottomSheet {
            return VideoOptionsBottomSheet().apply {
                this.video = video
                this.onVideoUpdated = onVideoUpdated
                this.onVideoRemoved = onVideoRemoved
                this.onAddToCollection = onAddToCollection
                this.onRemoveFromCollection = onRemoveFromCollection
            }
        }
    }
}
