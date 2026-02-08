package com.kidsmovies.parent.ui.activities

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kidsmovies.parent.ParentApp
import com.kidsmovies.parent.R
import com.kidsmovies.parent.databinding.ActivityPairingBinding
import com.kidsmovies.shared.models.PairingCode
import kotlinx.coroutines.launch

class PairingActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FAMILY_ID = "extra_family_id"
    }

    private lateinit var binding: ActivityPairingBinding
    private lateinit var app: ParentApp

    private var familyId: String? = null
    private var currentCode: PairingCode? = null
    private var countdownTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPairingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        app = application as ParentApp
        familyId = intent.getStringExtra(EXTRA_FAMILY_ID)

        setupUI()
        setupListeners()
        generateCode()
    }

    private fun setupUI() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupListeners() {
        binding.generateButton.setOnClickListener {
            generateCode()
        }

        binding.shareButton.setOnClickListener {
            shareCode()
        }
    }

    private fun generateCode() {
        val fid = familyId ?: return

        binding.loadingIndicator.visibility = View.VISIBLE
        binding.pairingCodeText.visibility = View.INVISIBLE
        binding.generateButton.isEnabled = false

        lifecycleScope.launch {
            try {
                // Invalidate old code if exists
                currentCode?.let { code ->
                    app.pairingManager.invalidateCode(code.code)
                }

                // Generate new code
                currentCode = app.pairingManager.generatePairingCode(fid)

                currentCode?.let { code ->
                    displayCode(code)
                } ?: run {
                    showError()
                }
            } catch (e: Exception) {
                showError()
            } finally {
                binding.loadingIndicator.visibility = View.GONE
                binding.pairingCodeText.visibility = View.VISIBLE
                binding.generateButton.isEnabled = true
            }
        }
    }

    private fun displayCode(code: PairingCode) {
        binding.pairingCodeText.text = code.code

        // Start countdown timer
        countdownTimer?.cancel()

        val remaining = code.expiresAt - System.currentTimeMillis()
        if (remaining > 0) {
            countdownTimer = object : CountDownTimer(remaining, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    val minutes = millisUntilFinished / 60000
                    val seconds = (millisUntilFinished % 60000) / 1000
                    binding.expiryText.text = getString(
                        R.string.code_expires_in,
                        String.format("%d:%02d", minutes, seconds)
                    )
                }

                override fun onFinish() {
                    binding.expiryText.text = getString(R.string.code_expired)
                    binding.pairingCodeText.alpha = 0.5f
                }
            }.start()
        }
    }

    private fun shareCode() {
        val code = currentCode?.code ?: return

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, getString(R.string.share_code_message, code))
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_code)))
    }

    private fun showError() {
        binding.pairingCodeText.text = "------"
        binding.expiryText.text = getString(R.string.error_generic)
    }

    override fun onDestroy() {
        super.onDestroy()
        countdownTimer?.cancel()
    }
}
