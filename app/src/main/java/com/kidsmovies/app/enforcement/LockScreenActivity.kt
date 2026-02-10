package com.kidsmovies.app.enforcement

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kidsmovies.app.KidsMoviesApp
import com.kidsmovies.app.databinding.ActivityLockScreenBinding
import com.kidsmovies.app.sync.ContentSyncManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Friendly lock screen shown when viewing is not allowed.
 * Shows a non-distressing message for children.
 */
class LockScreenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockScreenBinding
    private lateinit var app: KidsMoviesApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        app = application as KidsMoviesApp

        // Observe both timer state and app lock state
        observeLockStates()

        // Handle back press - just exit the app (friendly behavior)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Let the child exit to home screen
                finishAffinity()
            }
        })
    }

    private fun observeLockStates() {
        lifecycleScope.launch {
            // Combine both timer state and app lock state
            combine(
                app.viewingTimerManager.timerState,
                app.contentSyncManager.appLock
            ) { timerState, appLockState ->
                Pair(timerState, appLockState)
            }.collectLatest { (timerState, appLockState) ->
                // Check app lock first (parent-controlled)
                val blockReason = app.contentSyncManager.shouldBlockApp()
                if (blockReason != null) {
                    // Show appropriate message based on block reason
                    when (blockReason) {
                        is ContentSyncManager.BlockReason.AppLocked -> {
                            updateMessage(blockReason.message, blockReason.unlockAt)
                        }
                        is ContentSyncManager.BlockReason.ScheduleRestriction -> {
                            updateMessage(blockReason.message, null)
                        }
                        is ContentSyncManager.BlockReason.TimeLimitReached -> {
                            updateMessage(blockReason.message, null)
                        }
                    }
                    return@collectLatest
                }

                // Check timer state
                when (timerState.state) {
                    ViewingTimerManager.ViewingState.ACTIVE,
                    ViewingTimerManager.ViewingState.WATCHING -> {
                        // Both unlocked, close this screen
                        finish()
                    }
                    ViewingTimerManager.ViewingState.LOCKED -> {
                        updateMessage(timerState.reason, null)
                    }
                    else -> {
                        // Stay on lock screen
                    }
                }
            }
        }
    }

    private fun updateMessage(message: String, unlockAt: Long?) {
        binding.lockMessage.text = message

        // Show unlock time if available
        if (unlockAt != null && unlockAt > System.currentTimeMillis()) {
            val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
            val unlockTimeStr = timeFormat.format(Date(unlockAt))
            binding.unlockTimeText.text = "Unlocks at $unlockTimeStr"
            binding.unlockTimeText.visibility = View.VISIBLE
        } else {
            binding.unlockTimeText.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-evaluate in case schedule changed
        lifecycleScope.launch {
            app.viewingTimerManager.evaluate()
        }
    }
}
