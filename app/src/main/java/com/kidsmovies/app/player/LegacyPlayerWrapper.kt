package com.kidsmovies.app.player

import android.media.MediaPlayer
import android.net.Uri
import android.view.SurfaceHolder
import android.view.View

class LegacyPlayerWrapper : PlayerWrapper {

    private var mediaPlayer: MediaPlayer? = null
    private var isPrepared = false
    private var _isPlaying = false

    private var onPreparedListener: ((Int) -> Unit)? = null
    private var onCompletionListener: (() -> Unit)? = null
    private var onErrorListener: ((Exception) -> Unit)? = null
    private var onVideoSizeChangedListener: ((Int, Int) -> Unit)? = null

    private var pendingSurfaceHolder: SurfaceHolder? = null
    private var pendingUri: Uri? = null
    private var pendingStartPosition: Long = 0

    override val currentPosition: Long
        get() = try { mediaPlayer?.currentPosition?.toLong() ?: 0 } catch (e: Exception) { 0 }

    override val duration: Long
        get() = try { mediaPlayer?.duration?.toLong() ?: 0 } catch (e: Exception) { 0 }

    override val isPlaying: Boolean
        get() = _isPlaying

    override fun setDisplay(holder: SurfaceHolder) {
        pendingSurfaceHolder = holder
        mediaPlayer?.setDisplay(holder)
    }

    override fun getPlayerView(): View? = null // Legacy player uses SurfaceView directly

    override fun prepare(uri: Uri, startPosition: Long) {
        release()
        pendingUri = uri
        pendingStartPosition = startPosition

        try {
            mediaPlayer = MediaPlayer().apply {
                pendingSurfaceHolder?.let { setDisplay(it) }
                setDataSource(uri.toString())

                setOnPreparedListener { mp ->
                    isPrepared = true
                    val videoWidth = mp.videoWidth
                    val videoHeight = mp.videoHeight
                    onVideoSizeChangedListener?.invoke(videoWidth, videoHeight)

                    if (startPosition > 0) {
                        mp.seekTo(startPosition.toInt())
                    }

                    onPreparedListener?.invoke(mp.duration)
                }

                setOnCompletionListener {
                    _isPlaying = false
                    onCompletionListener?.invoke()
                }

                setOnErrorListener { _, what, extra ->
                    _isPlaying = false
                    onErrorListener?.invoke(Exception("MediaPlayer error: what=$what extra=$extra"))
                    true
                }

                setOnVideoSizeChangedListener { _, width, height ->
                    onVideoSizeChangedListener?.invoke(width, height)
                }

                prepareAsync()
            }
        } catch (e: Exception) {
            onErrorListener?.invoke(e)
        }
    }

    override fun play() {
        try {
            mediaPlayer?.start()
            _isPlaying = true
        } catch (e: Exception) {
            onErrorListener?.invoke(e)
        }
    }

    override fun pause() {
        try {
            mediaPlayer?.pause()
            _isPlaying = false
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun seekTo(position: Long) {
        try {
            mediaPlayer?.seekTo(position.toInt())
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun release() {
        isPrepared = false
        _isPlaying = false
        try {
            mediaPlayer?.release()
        } catch (e: Exception) {
            // Ignore
        }
        mediaPlayer = null
    }

    override fun setOnPreparedListener(listener: (Int) -> Unit) {
        onPreparedListener = listener
    }

    override fun setOnCompletionListener(listener: () -> Unit) {
        onCompletionListener = listener
    }

    override fun setOnErrorListener(listener: (Exception) -> Unit) {
        onErrorListener = listener
    }

    override fun setOnVideoSizeChangedListener(listener: (width: Int, height: Int) -> Unit) {
        onVideoSizeChangedListener = listener
    }
}
