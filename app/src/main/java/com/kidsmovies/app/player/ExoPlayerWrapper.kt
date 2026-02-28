package com.kidsmovies.app.player

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.SurfaceHolder
import android.view.View
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ExoPlayerWrapper(
    private val context: Context,
    private val tokenProvider: suspend () -> String?
) : PlayerWrapper {

    companion object {
        private const val TAG = "ExoPlayerWrapper"
    }

    private var exoPlayer: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var storedSurfaceHolder: SurfaceHolder? = null
    private var _isPlaying = false

    private var onPreparedListener: ((Int) -> Unit)? = null
    private var onCompletionListener: (() -> Unit)? = null
    private var onErrorListener: ((Exception) -> Unit)? = null
    private var onVideoSizeChangedListener: ((Int, Int) -> Unit)? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override val currentPosition: Long
        get() = exoPlayer?.currentPosition ?: 0

    override val duration: Long
        get() = exoPlayer?.duration ?: 0

    override val isPlaying: Boolean
        get() = _isPlaying

    override fun setDisplay(holder: SurfaceHolder) {
        storedSurfaceHolder = holder
        exoPlayer?.setVideoSurfaceHolder(holder)
    }

    override fun getPlayerView(): View? {
        if (playerView == null) {
            playerView = PlayerView(context).apply {
                useController = false // We use our own controls
            }
        }
        return playerView
    }

    override fun prepare(uri: Uri, startPosition: Long) {
        release()

        exoPlayer = ExoPlayer.Builder(context).build().apply {
            // Set up player view if available
            playerView?.player = this

            // Apply stored surface holder (setDisplay is called before prepare)
            storedSurfaceHolder?.let { setVideoSurfaceHolder(it) }

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            val durationMs = duration.toInt()
                            onPreparedListener?.invoke(durationMs)
                        }
                        Player.STATE_ENDED -> {
                            _isPlaying = false
                            onCompletionListener?.invoke()
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    _isPlaying = false
                    Log.e(TAG, "ExoPlayer error", error)
                    onErrorListener?.invoke(Exception("ExoPlayer error: ${error.message}"))
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        onVideoSizeChangedListener?.invoke(videoSize.width, videoSize.height)
                    }
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    _isPlaying = playing
                }
            })

            // Create data source factory for streaming
            val dataSourceFactory = OkHttpDataSource.Factory(httpClient)

            val mediaSource: MediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(uri))

            setMediaSource(mediaSource)

            if (startPosition > 0) {
                seekTo(startPosition)
            }

            prepare()
        }
    }

    override fun play() {
        exoPlayer?.play()
    }

    override fun pause() {
        exoPlayer?.pause()
    }

    override fun seekTo(position: Long) {
        exoPlayer?.seekTo(position)
    }

    override fun release() {
        _isPlaying = false
        playerView?.player = null
        exoPlayer?.release()
        exoPlayer = null
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
