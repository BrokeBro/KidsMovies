package com.kidsmovies.app.pairing

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kidsmovies.app.KidsMoviesApp
import com.kidsmovies.app.R
import com.kidsmovies.app.databinding.ActivityPairingBinding
import com.kidsmovies.app.ui.activities.MainActivity
import kotlinx.coroutines.launch

/**
 * Activity for entering the 6-digit pairing code from the parent app.
 */
class PairingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPairingBinding
    private lateinit var app: KidsMoviesApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPairingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        app = application as KidsMoviesApp

        setupListeners()
        checkExistingPairing()
    }

    private fun checkExistingPairing() {
        lifecycleScope.launch {
            val isPaired = app.pairingRepository.isPaired()
            if (isPaired) {
                // Already paired, go to main
                goToMain()
            }
        }
    }

    private fun setupListeners() {
        binding.connectButton.setOnClickListener {
            attemptPairing()
        }

        binding.skipButton.setOnClickListener {
            // Skip pairing, go directly to main
            // App will work without parental controls
            goToMain()
        }

        binding.codeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attemptPairing()
                true
            } else {
                false
            }
        }
    }

    private fun attemptPairing() {
        val code = binding.codeInput.text?.toString()?.trim() ?: ""
        val deviceName = binding.deviceNameInput.text?.toString()?.trim()
            ?: getString(R.string.device_name_hint)

        if (code.length != 6) {
            showError(getString(R.string.code_invalid))
            return
        }

        setLoading(true)
        hideError()

        lifecycleScope.launch {
            when (val result = app.pairingRepository.pairWithCode(code, deviceName)) {
                is PairingResult.Success -> {
                    Toast.makeText(
                        this@PairingActivity,
                        R.string.pairing_success,
                        Toast.LENGTH_SHORT
                    ).show()

                    // Notify app of successful pairing
                    val pairingState = app.pairingRepository.getPairingState()
                    pairingState?.childUid?.let { uid ->
                        app.onPairingComplete(uid)
                    }

                    goToMain()
                }
                is PairingResult.CodeExpired -> {
                    setLoading(false)
                    showError(getString(R.string.code_expired))
                }
                is PairingResult.CodeInvalid -> {
                    setLoading(false)
                    showError(getString(R.string.code_invalid))
                }
                is PairingResult.NetworkError -> {
                    setLoading(false)
                    showError(getString(R.string.network_error))
                }
                is PairingResult.Error -> {
                    setLoading(false)
                    showError(result.message)
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.loadingIndicator.visibility = if (loading) View.VISIBLE else View.GONE
        binding.connectButton.isEnabled = !loading
        binding.codeInput.isEnabled = !loading
        binding.deviceNameInput.isEnabled = !loading
    }

    private fun showError(message: String) {
        binding.errorMessage.text = message
        binding.errorMessage.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.errorMessage.visibility = View.GONE
    }

    private fun goToMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
