package com.kidsmovies.app.enforcement

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kidsmovies.app.KidsMoviesApp
import com.kidsmovies.app.databinding.ActivityLockScreenBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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

        // Observe timer state to auto-dismiss when unlocked
        observeTimerState()

        // Handle back press - just exit the app (friendly behavior)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Let the child exit to home screen
                finishAffinity()
            }
        })
    }

    private fun observeTimerState() {
        lifecycleScope.launch {
            app.viewingTimerManager.timerState.collectLatest { state ->
                when (state.state) {
                    ViewingTimerManager.ViewingState.ACTIVE,
                    ViewingTimerManager.ViewingState.WATCHING -> {
                        // Timer unlocked, close this screen
                        finish()
                    }
                    else -> {
                        // Stay on lock screen
                    }
                }
            }
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
