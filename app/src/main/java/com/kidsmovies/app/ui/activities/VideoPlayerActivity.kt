package com.kidsmovies.app.ui.activities

import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.kidsmovies.app.KidsMoviesApp
import com.kidsmovies.app.R
import com.kidsmovies.app.data.database.entities.Video
import com.kidsmovies.app.databinding.ActivityVideoPlayerBinding
import kotlinx.coroutines.launch

class VideoPlayerActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        const val EXTRA_VIDEO = "extra_video"
        private const val SEEK_AMOUNT_MS = 10000 // 10 seconds
        private const val CONTROLS_HIDE_DELAY = 3000L
        private const val PROGRESS_UPDATE_INTERVAL = 1000L
    }

    private lateinit var binding: ActivityVideoPlayerBinding
    private lateinit var app: KidsMoviesApp

    private var video: Video? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false
    private var controlsVisible = true

    private val handler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideControls() }
    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, PROGRESS_UPDATE_INTERVAL)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        app = application as KidsMoviesApp

        // Get video from intent
        video = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_VIDEO, Video::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_VIDEO)
        }
        if (video == null) {
            finish()
            return
        }

        setupImmersiveMode()
        setupUI()
        setupListeners()
        setupSurface()
    }

    private fun setupImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun setupUI() {
        binding.videoTitle.text = video?.title ?: ""
        binding.loadingIndicator.visibility = View.VISIBLE
    }

    private fun setupListeners() {
        binding.backButton.setOnClickListener {
            finish()
        }

        binding.playPauseButton.setOnClickListener {
            togglePlayPause()
            resetHideControlsTimer()
        }

        binding.rewindButton.setOnClickListener {
            seekRelative(-SEEK_AMOUNT_MS)
            resetHideControlsTimer()
        }

        binding.forwardButton.setOnClickListener {
            seekRelative(SEEK_AMOUNT_MS)
            resetHideControlsTimer()
        }

        binding.controlsOverlay.setOnClickListener {
            toggleControls()
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.currentTime.text = formatTime(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                handler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                mediaPlayer?.seekTo(seekBar?.progress ?: 0)
                resetHideControlsTimer()
            }
        })
    }

    private fun setupSurface() {
        binding.videoSurface.holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        initializeMediaPlayer(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        releaseMediaPlayer()
    }

    private fun initializeMediaPlayer(holder: SurfaceHolder) {
        try {
            mediaPlayer = MediaPlayer().apply {
                setDisplay(holder)
                setDataSource(video?.filePath)
                setOnPreparedListener { onMediaPlayerPrepared(it) }
                setOnCompletionListener { onMediaPlayerComplete() }
                setOnErrorListener { _, _, _ ->
                    binding.loadingIndicator.visibility = View.GONE
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            binding.loadingIndicator.visibility = View.GONE
        }
    }

    private fun onMediaPlayerPrepared(mp: MediaPlayer) {
        binding.loadingIndicator.visibility = View.GONE

        // Set up duration
        val duration = mp.duration
        binding.seekBar.max = duration
        binding.totalTime.text = formatTime(duration)

        // Adjust video size
        adjustVideoSize(mp.videoWidth, mp.videoHeight)

        // Start playback
        mp.start()
        isPlaying = true
        updatePlayPauseButton()

        // Start progress updates
        handler.post(updateProgressRunnable)

        // Schedule hiding controls
        resetHideControlsTimer()

        // Update play stats
        video?.let {
            lifecycleScope.launch {
                app.videoRepository.updatePlayStats(it.id)
            }
        }
    }

    private fun onMediaPlayerComplete() {
        isPlaying = false
        updatePlayPauseButton()
        showControls()
        handler.removeCallbacks(hideControlsRunnable)
    }

    private fun adjustVideoSize(videoWidth: Int, videoHeight: Int) {
        val screenWidth = binding.videoSurface.width
        val screenHeight = binding.videoSurface.height

        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
        val screenAspect = screenWidth.toFloat() / screenHeight.toFloat()

        val (newWidth, newHeight) = if (videoAspect > screenAspect) {
            screenWidth to (screenWidth / videoAspect).toInt()
        } else {
            (screenHeight * videoAspect).toInt() to screenHeight
        }

        binding.videoSurface.layoutParams = binding.videoSurface.layoutParams.apply {
            width = newWidth
            height = newHeight
        }
    }

    private fun togglePlayPause() {
        mediaPlayer?.let { mp ->
            if (isPlaying) {
                mp.pause()
                isPlaying = false
            } else {
                mp.start()
                isPlaying = true
            }
            updatePlayPauseButton()
        }
    }

    private fun updatePlayPauseButton() {
        binding.playPauseButton.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun seekRelative(amountMs: Int) {
        mediaPlayer?.let { mp ->
            val newPosition = (mp.currentPosition + amountMs).coerceIn(0, mp.duration)
            mp.seekTo(newPosition)
            updateProgress()
        }
    }

    private fun updateProgress() {
        mediaPlayer?.let { mp ->
            if (mp.isPlaying) {
                binding.seekBar.progress = mp.currentPosition
                binding.currentTime.text = formatTime(mp.currentPosition)
            }
        }
    }

    private fun toggleControls() {
        if (controlsVisible) {
            hideControls()
        } else {
            showControls()
            resetHideControlsTimer()
        }
    }

    private fun showControls() {
        binding.controlsOverlay.visibility = View.VISIBLE
        controlsVisible = true
    }

    private fun hideControls() {
        if (isPlaying) {
            binding.controlsOverlay.visibility = View.GONE
            controlsVisible = false
        }
    }

    private fun resetHideControlsTimer() {
        handler.removeCallbacks(hideControlsRunnable)
        if (isPlaying) {
            handler.postDelayed(hideControlsRunnable, CONTROLS_HIDE_DELAY)
        }
    }

    private fun formatTime(milliseconds: Int): String {
        val totalSeconds = milliseconds / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    private fun releaseMediaPlayer() {
        handler.removeCallbacks(updateProgressRunnable)
        handler.removeCallbacks(hideControlsRunnable)
        mediaPlayer?.release()
        mediaPlayer = null
    }

    override fun onPause() {
        super.onPause()
        mediaPlayer?.pause()
        isPlaying = false
        updatePlayPauseButton()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseMediaPlayer()
    }
}
