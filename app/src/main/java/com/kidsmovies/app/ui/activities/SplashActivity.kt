package com.kidsmovies.app.ui.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.kidsmovies.app.KidsMoviesApp
import com.kidsmovies.app.databinding.ActivitySplashBinding
import com.kidsmovies.app.services.ParentalControlService
import com.kidsmovies.app.utils.Constants
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private lateinit var app: KidsMoviesApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        app = application as KidsMoviesApp

        lifecycleScope.launch {
            // Short delay for splash effect
            delay(1000)

            // Check parental control status
            val parentalControlService = ParentalControlService(this@SplashActivity)
            val accessResult = parentalControlService.canUseApp()

            if (!accessResult.allowed) {
                showBlockedScreen(accessResult.message)
                return@launch
            }

            // Check permissions
            if (!hasStoragePermission()) {
                requestStoragePermission()
                return@launch
            }

            proceedToNextScreen()
        }
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_VIDEO
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        ActivityCompat.requestPermissions(
            this,
            arrayOf(permission),
            Constants.REQUEST_STORAGE_PERMISSION
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == Constants.REQUEST_STORAGE_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                lifecycleScope.launch {
                    proceedToNextScreen()
                }
            } else {
                // Permission denied, show message or close app
                // For now, still proceed but videos won't be found
                lifecycleScope.launch {
                    proceedToNextScreen()
                }
            }
        }
    }

    private suspend fun proceedToNextScreen() {
        val isSetupComplete = app.settingsRepository.isSetupComplete()

        if (isSetupComplete) {
            startActivity(Intent(this, MainActivity::class.java))
        } else {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        finish()
    }

    private fun showBlockedScreen(message: String) {
        binding.loadingIndicator.visibility = View.GONE
        binding.blockedOverlay.visibility = View.VISIBLE
        binding.blockedMessage.text = message
    }
}
