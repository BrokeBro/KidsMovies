package com.kidsmovies.app.enforcement

import android.util.Log
import com.kidsmovies.app.sync.CachedSettingsDao
import com.kidsmovies.app.sync.EnforcementSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manages viewing time limits and the soft-off feature.
 * This is the core state machine for time enforcement.
 */
class ViewingTimerManager(
    private val cachedSettingsDao: CachedSettingsDao,
    private val scheduleEvaluator: ScheduleEvaluator,
    private val deviceId: String,
    private val coroutineScope: CoroutineScope
) {
    companion object {
        private const val TAG = "ViewingTimerManager"
        private const val TICK_INTERVAL_MS = 60_000L // 1 minute
    }

    enum class ViewingState {
        LOCKED,           // No active schedule or time exceeded
        ACTIVE,           // Within schedule, can browse and pick videos
        WATCHING,         // Video is playing, timer counting
        SOFT_OFF_WARNING, // Time limit hit mid-video, warning shown
        FINISHING_VIDEO   // After warning dismissed, video continues but nothing after
    }

    data class TimerState(
        val state: ViewingState = ViewingState.LOCKED,
        val reason: String = "",
        val remainingMinutes: Int? = null,
        val totalWatchedMinutes: Int = 0,
        val maxMinutes: Int? = null,
        val showSoftOffWarning: Boolean = false,
        val currentVideoId: Long? = null,
        val scheduleLabel: String? = null
    )

    private val _timerState = MutableStateFlow(TimerState())
    val timerState: StateFlow<TimerState> = _timerState

    private var tickerJob: Job? = null
    private var sessionStartTime: Long = 0
    private var totalWatchedMinutes: Int = 0
    private var currentVideoStartTime: Long = 0
    private var currentVideoId: Long? = null
    private var currentSettings: EnforcementSettings? = null
    private var currentScheduleResult: ScheduleEvaluator.ScheduleResult? = null

    /**
     * Initialize and start the timer evaluation loop
     */
    fun start() {
        Log.d(TAG, "Starting ViewingTimerManager")
        startTicker()
    }

    /**
     * Stop the timer
     */
    fun stop() {
        Log.d(TAG, "Stopping ViewingTimerManager")
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = coroutineScope.launch(Dispatchers.IO) {
            while (isActive) {
                evaluate()
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    /**
     * Main evaluation function - called every minute and on state changes
     */
    suspend fun evaluate() {
        try {
            val settings = cachedSettingsDao.getEnforcementSettings()
            currentSettings = settings

            val scheduleResult = scheduleEvaluator.evaluate(settings, deviceId)
            currentScheduleResult = scheduleResult

            // Check device-level blocks first
            if (settings.isDeviceRevoked) {
                transitionTo(ViewingState.LOCKED, "Device has been disconnected")
                return
            }

            if (!settings.isAppEnabled) {
                transitionTo(ViewingState.LOCKED, "App is currently disabled")
                return
            }

            if (!scheduleResult.isAllowed) {
                transitionTo(ViewingState.LOCKED, scheduleResult.reason)
                return
            }

            val maxMinutes = scheduleResult.maxViewingMinutes
            val currentState = _timerState.value.state

            // Check time limits
            if (maxMinutes != null && totalWatchedMinutes >= maxMinutes) {
                when (currentState) {
                    ViewingState.WATCHING -> {
                        if (settings.isSoftOffEnabled) {
                            transitionTo(
                                ViewingState.SOFT_OFF_WARNING,
                                "Time's up! You can finish this video.",
                                showSoftOffWarning = true
                            )
                        } else {
                            // Hard cut
                            transitionTo(ViewingState.LOCKED, "Time's up!")
                        }
                    }
                    ViewingState.SOFT_OFF_WARNING, ViewingState.FINISHING_VIDEO -> {
                        // Already in soft-off, keep going until video ends
                    }
                    else -> {
                        transitionTo(ViewingState.LOCKED, "Time's up!")
                    }
                }
                return
            }

            // Within schedule and within time limit
            if (currentState == ViewingState.LOCKED) {
                transitionTo(
                    ViewingState.ACTIVE,
                    "Ready to watch!",
                    scheduleLabel = scheduleResult.activeSchedule?.label
                )
            }

            updateRemainingTime(maxMinutes)

        } catch (e: Exception) {
            Log.e(TAG, "Error during evaluation", e)
        }
    }

    /**
     * Called when a video starts playing
     */
    fun onVideoStarted(videoId: Long) {
        val currentState = _timerState.value.state
        if (currentState != ViewingState.ACTIVE && currentState != ViewingState.WATCHING) {
            Log.w(TAG, "Cannot start video in state: $currentState")
            return
        }

        currentVideoId = videoId
        currentVideoStartTime = System.currentTimeMillis()

        if (sessionStartTime == 0L) {
            sessionStartTime = currentVideoStartTime
        }

        transitionTo(
            ViewingState.WATCHING,
            "Watching...",
            currentVideoId = videoId
        )

        Log.d(TAG, "Video started: $videoId")
    }

    /**
     * Called when a video finishes (naturally or stopped by user)
     */
    fun onVideoEnded() {
        // Add watched time
        if (currentVideoStartTime > 0) {
            val watchedMs = System.currentTimeMillis() - currentVideoStartTime
            val watchedMinutes = (watchedMs / 60_000).toInt()
            totalWatchedMinutes += watchedMinutes
            Log.d(TAG, "Video ended. Watched $watchedMinutes minutes. Total: $totalWatchedMinutes")
        }

        currentVideoId = null
        currentVideoStartTime = 0

        val currentState = _timerState.value.state

        when (currentState) {
            ViewingState.SOFT_OFF_WARNING, ViewingState.FINISHING_VIDEO -> {
                // Soft-off: video finished, now lock
                transitionTo(ViewingState.LOCKED, "All done for now! Great watching!")
                resetSession()
            }
            else -> {
                // Go back to ACTIVE state
                coroutineScope.launch(Dispatchers.IO) {
                    evaluate()
                }
            }
        }
    }

    /**
     * Dismiss the soft-off warning (video continues playing)
     */
    fun dismissSoftOffWarning() {
        if (_timerState.value.state == ViewingState.SOFT_OFF_WARNING) {
            transitionTo(
                ViewingState.FINISHING_VIDEO,
                "Finishing current video...",
                showSoftOffWarning = false
            )
        }
    }

    /**
     * Check if a video can be started
     */
    fun canStartVideo(): Boolean {
        val state = _timerState.value.state
        return state == ViewingState.ACTIVE || state == ViewingState.WATCHING
    }

    /**
     * Check if new videos can be queued (not in soft-off states)
     */
    fun canBrowseContent(): Boolean {
        val state = _timerState.value.state
        return state == ViewingState.ACTIVE || state == ViewingState.WATCHING
    }

    /**
     * Get the current schedule result for content filtering
     */
    fun getCurrentScheduleResult(): ScheduleEvaluator.ScheduleResult? {
        return currentScheduleResult
    }

    /**
     * Get remaining minutes (null = unlimited)
     */
    fun getRemainingMinutes(): Int? {
        val maxMinutes = currentScheduleResult?.maxViewingMinutes ?: return null
        return (maxMinutes - totalWatchedMinutes).coerceAtLeast(0)
    }

    /**
     * Reset session counters (call at start of new day or schedule window)
     */
    fun resetSession() {
        sessionStartTime = 0
        totalWatchedMinutes = 0
        currentVideoStartTime = 0
        currentVideoId = null
        Log.d(TAG, "Session reset")
    }

    private fun transitionTo(
        newState: ViewingState,
        reason: String,
        showSoftOffWarning: Boolean = false,
        currentVideoId: Long? = this.currentVideoId,
        scheduleLabel: String? = currentScheduleResult?.activeSchedule?.label
    ) {
        val maxMinutes = currentScheduleResult?.maxViewingMinutes
        val remaining = maxMinutes?.let { (it - totalWatchedMinutes).coerceAtLeast(0) }

        _timerState.value = TimerState(
            state = newState,
            reason = reason,
            remainingMinutes = remaining,
            totalWatchedMinutes = totalWatchedMinutes,
            maxMinutes = maxMinutes,
            showSoftOffWarning = showSoftOffWarning,
            currentVideoId = currentVideoId,
            scheduleLabel = scheduleLabel
        )

        Log.d(TAG, "State transition: $newState - $reason")
    }

    private fun updateRemainingTime(maxMinutes: Int?) {
        val currentTimerState = _timerState.value
        val remaining = maxMinutes?.let { (it - totalWatchedMinutes).coerceAtLeast(0) }

        if (currentTimerState.remainingMinutes != remaining) {
            _timerState.value = currentTimerState.copy(
                remainingMinutes = remaining,
                totalWatchedMinutes = totalWatchedMinutes,
                maxMinutes = maxMinutes
            )
        }
    }
}
