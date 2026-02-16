package com.kidsmovies.app.player

import android.net.Uri
import android.view.SurfaceHolder
import android.view.View

interface PlayerWrapper {
    fun prepare(uri: Uri, startPosition: Long = 0)
    fun play()
    fun pause()
    fun seekTo(position: Long)
    fun release()

    val currentPosition: Long
    val duration: Long
    val isPlaying: Boolean

    fun setDisplay(holder: SurfaceHolder)
    fun getPlayerView(): View?

    fun setOnPreparedListener(listener: (Int) -> Unit) // duration in ms
    fun setOnCompletionListener(listener: () -> Unit)
    fun setOnErrorListener(listener: (Exception) -> Unit)
    fun setOnVideoSizeChangedListener(listener: (width: Int, height: Int) -> Unit)
}
