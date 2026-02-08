package com.kidsmovies.app.ui.activities

import android.content.Intent
import android.content.res.Configuration
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewTreeObserver
import android.widget.SeekBar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.kidsmovies.app.KidsMoviesApp
import com.kidsmovies.app.R
import com.kidsmovies.app.data.database.entities.Video
import com.kidsmovies.app.data.database.entities.ViewingSession
import com.kidsmovies.app.databinding.ActivityVideoPlayerBinding
import com.kidsmovies.app.enforcement.LockScreenActivity
import com.kidsmovies.app.enforcement.ViewingTimerManager
import com.kidsmovies.app.sync.ContentSyncManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class VideoPlayerActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        const val EXTRA_VIDEO = "extra_video"
        const val EXTRA_COLLECTION_ID = "extra_collection_id"
        const val EXTRA_COLLECTION_NAME = "extra_collection_name"
        private const val SEEK_AMOUNT_MS = 10000 // 10 seconds
        private const val CONTROLS_HIDE_DELAY = 3000L
        private const val PROGRESS_UPDATE_INTERVAL = 1000L
        private const val MIN_RESUME_POSITION = 5000L // Don't resume if less than 5 seconds
        private const val END_THRESHOLD_PERCENT = 0.95 // Consider finished if past 95%
    }

    private lateinit var binding: ActivityVideoPlayerBinding
    private lateinit var app: KidsMoviesApp

    private var video: Video? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false
    private var controlsVisible = true
    private var resumePosition: Long = 0

    // Video dimensions for dynamic resizing
    private var videoWidth: Int = 0
    private var videoHeight: Int = 0

    // Session tracking
    private var currentSessionId: Long = 0
    private var sessionStartTime: Long = 0
    private var totalPlayTime: Long = 0
    private var lastPlayStartTime: Long = 0
    private var collectionId: Long? = null
    private var collectionName: String? = null

    // Parental control tracking
    private var softOffWarningShown = false
    private var lastOneWarningShown = false
    private var lockWarningDialog: AlertDialog? = null

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

        // Get resume position from video
        resumePosition = video?.playbackPosition ?: 0

        // Get collection info if video was opened from a collection
        collectionId = intent.getLongExtra(EXTRA_COLLECTION_ID, -1).takeIf { it > 0 }
        collectionName = intent.getStringExtra(EXTRA_COLLECTION_NAME)

        setupImmersiveMode()
        setupUI()
        setupListeners()
        setupSurface()
        setupBackHandler()
        observeViewingTimer()
        observeLockWarnings()
    }

    private fun observeViewingTimer() {
        lifecycleScope.launch {
            app.viewingTimerManager.timerState.collectLatest { state ->
                when (state.state) {
                    ViewingTimerManager.ViewingState.LOCKED -> {
                        // Time's up and video finished, go to lock screen
                        if (!isFinishing) {
                            notifyTimerVideoEnded()
                            goToLockScreen()
                        }
                    }
                    ViewingTimerManager.ViewingState.SOFT_OFF_WARNING -> {
                        // Show soft-off warning if not already shown
                        if (!softOffWarningShown && state.showSoftOffWarning) {
                            showSoftOffWarning()
                        }
                    }
                    else -> {
                        // Normal operation
                    }
                }
            }
        }
    }

    private fun observeLockWarnings() {
        lifecycleScope.launch {
            app.contentSyncManager.lockWarning.collectLatest { warning ->
                if (warning != null && warning.isLastOne && !lastOneWarningShown) {
                    showLastOneWarning(warning)
                } else if (warning != null && !warning.isLastOne && warning.minutesRemaining <= 2) {
                    // Show countdown warning when less than 2 minutes remain
                    showLockCountdownWarning(warning)
                }
            }
        }
    }

    private fun showLastOneWarning(warning: ContentSyncManager.LockWarning) {
        lastOneWarningShown = true
        lockWarningDialog?.dismiss()

        val message = if (warning.isVideo) {
            getString(R.string.last_one_message, warning.title)
        } else {
            getString(R.string.last_one_collection_message, warning.title)
        }

        lockWarningDialog = AlertDialog.Builder(this, R.style.Theme_KidsMovies_Dialog)
            .setTitle(R.string.last_one_title)
            .setMessage(message)
            .setPositiveButton(R.string.ok) { dialog, _ ->
                dialog.dismiss()
                app.contentSyncManager.dismissLockWarning()
            }
            .setCancelable(false)
            .create()

        lockWarningDialog?.show()
    }

    private fun showLockCountdownWarning(warning: ContentSyncManager.LockWarning) {
        // Don't show repeatedly
        if (lockWarningDialog?.isShowing == true) return

        val contentType = if (warning.isVideo) getString(R.string.video) else getString(R.string.collection)
        val message = if (warning.allowFinishCurrentVideo) {
            getString(R.string.lock_warning_finish_video, contentType, warning.title, warning.minutesRemaining)
        } else {
            getString(R.string.lock_warning_message, contentType, warning.title, warning.minutesRemaining)
        }

        lockWarningDialog = AlertDialog.Builder(this, R.style.Theme_KidsMovies_Dialog)
            .setTitle(R.string.lock_warning_title)
            .setMessage(message)
            .setPositiveButton(R.string.ok) { dialog, _ ->
                dialog.dismiss()
                app.contentSyncManager.dismissLockWarning()
            }
            .setCancelable(false)
            .create()

        lockWarningDialog?.show()
    }

    private fun showSoftOffWarning() {
        softOffWarningShown = true
        AlertDialog.Builder(this, R.style.Theme_KidsMovies_Dialog)
            .setTitle(R.string.soft_off_title)
            .setMessage(R.string.soft_off_message)
            .setPositiveButton(R.string.soft_off_dismiss) { dialog, _ ->
                dialog.dismiss()
                app.viewingTimerManager.dismissSoftOffWarning()
            }
            .setCancelable(false)
            .show()
    }

    private fun goToLockScreen() {
        savePlaybackPosition()
        val intent = Intent(this, LockScreenActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                savePlaybackPosition()
                finish()
            }
        })
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
            savePlaybackPosition()
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

        // Touch layer for showing/hiding controls - always receives clicks
        binding.touchLayer.setOnClickListener {
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

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // Recalculate video size when surface dimensions change (e.g., orientation change)
        adjustVideoSize()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Post to ensure the new layout dimensions are available
        binding.root.post {
            adjustVideoSize()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        savePlaybackPosition()
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

        // Store video dimensions and adjust size
        videoWidth = mp.videoWidth
        videoHeight = mp.videoHeight
        adjustVideoSize()

        // Resume from saved position if applicable
        if (resumePosition > MIN_RESUME_POSITION && 
            resumePosition < duration * END_THRESHOLD_PERCENT) {
            mp.seekTo(resumePosition.toInt())
        }

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

        // Start viewing session tracking
        startViewingSession()
    }

    private fun onMediaPlayerComplete() {
        isPlaying = false
        updatePlayPauseButton()
        showControls()
        handler.removeCallbacks(hideControlsRunnable)

        // End viewing session as completed
        endViewingSession(completed = true)

        // Notify timer manager that video ended
        notifyTimerVideoEnded()

        // Reset playback position when video completes
        video?.let {
            lifecycleScope.launch {
                app.videoRepository.updatePlaybackPosition(it.id, 0)
            }
        }
    }

    private fun adjustVideoSize() {
        if (videoWidth == 0 || videoHeight == 0) return

        // Use the parent container size (root view) for proper full-screen calculation
        val parent = binding.videoSurface.parent as? View ?: return
        val screenWidth = parent.width
        val screenHeight = parent.height

        if (screenWidth == 0 || screenHeight == 0) {
            // Parent not yet laid out, schedule for later
            parent.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    parent.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    adjustVideoSize()
                }
            })
            return
        }

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
                pauseSessionTracking()
            } else {
                mp.start()
                isPlaying = true
                resumeSessionTracking()
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
            try {
                binding.seekBar.progress = mp.currentPosition
                binding.currentTime.text = formatTime(mp.currentPosition)
            } catch (e: Exception) {
                // MediaPlayer may be in invalid state
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
            binding.controlsOverlay.visibility = View.INVISIBLE
            controlsVisible = false
        }
    }

    private fun resetHideControlsTimer() {
        handler.removeCallbacks(hideControlsRunnable)
        if (isPlaying) {
            handler.postDelayed(hideControlsRunnable, CONTROLS_HIDE_DELAY)
        }
    }

    private fun savePlaybackPosition() {
        mediaPlayer?.let { mp ->
            try {
                val position = mp.currentPosition.toLong()
                val duration = mp.duration.toLong()
                
                // Only save if we're not at the beginning or end
                val shouldSave = position > MIN_RESUME_POSITION && 
                                 position < duration * END_THRESHOLD_PERCENT
                
                video?.let { v ->
                    lifecycleScope.launch {
                        app.videoRepository.updatePlaybackPosition(
                            v.id, 
                            if (shouldSave) position else 0
                        )
                    }
                }
            } catch (e: Exception) {
                // MediaPlayer may be in invalid state
            }
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

    // Session tracking methods
    private fun startViewingSession() {
        sessionStartTime = System.currentTimeMillis()
        lastPlayStartTime = sessionStartTime
        totalPlayTime = 0

        video?.let { v ->
            lifecycleScope.launch {
                val session = ViewingSession(
                    videoId = v.id,
                    collectionId = collectionId,
                    videoTitle = v.title,
                    collectionName = collectionName,
                    startTime = sessionStartTime,
                    videoDuration = mediaPlayer?.duration?.toLong() ?: 0
                )
                currentSessionId = app.metricsRepository.startSession(session)
            }

            // Notify timer manager that video started
            app.viewingTimerManager.onVideoStarted(v.id)

            // Notify sync manager that video started (triggers sync to parent)
            app.onVideoStarted(v.title)
        }
    }

    private fun notifyTimerVideoEnded() {
        app.viewingTimerManager.onVideoEnded()
    }

    private fun pauseSessionTracking() {
        if (lastPlayStartTime > 0) {
            totalPlayTime += System.currentTimeMillis() - lastPlayStartTime
            lastPlayStartTime = 0
        }
    }

    private fun resumeSessionTracking() {
        lastPlayStartTime = System.currentTimeMillis()
    }

    private fun endViewingSession(completed: Boolean = false) {
        if (currentSessionId <= 0) return

        pauseSessionTracking()
        val endTime = System.currentTimeMillis()

        lifecycleScope.launch {
            app.metricsRepository.endSession(
                sessionId = currentSessionId,
                endTime = endTime,
                duration = totalPlayTime,
                completed = completed
            )
        }

        // Reset tracking
        currentSessionId = 0
        sessionStartTime = 0
        totalPlayTime = 0
        lastPlayStartTime = 0
    }

    private fun releaseMediaPlayer() {
        handler.removeCallbacks(updateProgressRunnable)
        handler.removeCallbacks(hideControlsRunnable)
        mediaPlayer?.release()
        mediaPlayer = null
    }

    override fun onPause() {
        super.onPause()
        savePlaybackPosition()
        endViewingSession(completed = false)
        mediaPlayer?.pause()
        isPlaying = false
        updatePlayPauseButton()

        // Notify sync manager that video stopped
        app.onVideoStopped()
    }

    override fun onDestroy() {
        super.onDestroy()
        lockWarningDialog?.dismiss()
        lockWarningDialog = null
        releaseMediaPlayer()
    }
}
