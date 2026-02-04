package com.kidsmovies.app.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.kidsmovies.app.KidsMoviesApp
import com.kidsmovies.app.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ParentalControlService(private val context: Context) {

    companion object {
        private const val TAG = "ParentalControlService"
    }

    private val app = KidsMoviesApp.instance
    private val parentalRepository = app.parentalControlRepository

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(Constants.PARENTAL_SERVER_TIMEOUT, TimeUnit.MILLISECONDS)
        .readTimeout(Constants.PARENTAL_SERVER_TIMEOUT, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Check if the app is enabled, considering online status and offline fallback
     */
    suspend fun isAppAllowed(): Boolean = withContext(Dispatchers.IO) {
        try {
            val parentalControl = parentalRepository.getParentalControl()
                ?: return@withContext true // If no settings, allow by default

            // Check if we have a server URL configured
            if (parentalControl.parentalServerUrl.isBlank()) {
                // No server configured, use local setting
                return@withContext parentalControl.isAppEnabled
            }

            // Check if we have network connectivity
            if (!isNetworkAvailable()) {
                Log.d(TAG, "No network, using last known status: ${parentalControl.lastKnownStatus}")
                return@withContext parentalControl.lastKnownStatus
            }

            // Try to get status from server
            val onlineStatus = checkOnlineStatus(parentalControl.parentalServerUrl, parentalControl.deviceId)

            if (onlineStatus != null) {
                // Update local status
                parentalRepository.setAppEnabled(onlineStatus)
                parentalRepository.updateLastKnownStatus(onlineStatus)
                return@withContext onlineStatus
            } else {
                // Server unreachable, use last known status
                Log.d(TAG, "Server unreachable, using last known status: ${parentalControl.lastKnownStatus}")
                return@withContext parentalControl.lastKnownStatus
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking parental control", e)
            // On any error, try to use last known status
            return@withContext parentalRepository.getLastKnownStatus()
        }
    }

    /**
     * Get the blocked message to display when app is disabled
     */
    suspend fun getBlockedMessage(): String {
        val parentalControl = parentalRepository.getParentalControl()
        return parentalControl?.blockedMessage ?: "Ask your parent to enable video time!"
    }

    /**
     * Check time schedule if enabled
     */
    suspend fun isWithinSchedule(): Boolean {
        val parentalControl = parentalRepository.getParentalControl()
            ?: return true

        if (!parentalControl.scheduleEnabled) {
            return true
        }

        val currentTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())

        val startTime = parentalControl.scheduleStartTime
        val endTime = parentalControl.scheduleEndTime

        return currentTime >= startTime && currentTime <= endTime
    }

    /**
     * Full check combining app enabled and schedule
     */
    suspend fun canUseApp(): AppAccessResult {
        // First check if app is enabled at all
        if (!isAppAllowed()) {
            return AppAccessResult(
                allowed = false,
                reason = AppAccessDeniedReason.DISABLED_BY_PARENT,
                message = getBlockedMessage()
            )
        }

        // Then check schedule
        if (!isWithinSchedule()) {
            val parentalControl = parentalRepository.getParentalControl()
            return AppAccessResult(
                allowed = false,
                reason = AppAccessDeniedReason.OUTSIDE_SCHEDULE,
                message = "Video time is from ${parentalControl?.scheduleStartTime} to ${parentalControl?.scheduleEndTime}"
            )
        }

        return AppAccessResult(allowed = true)
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private suspend fun checkOnlineStatus(serverUrl: String, deviceId: String): Boolean? = withContext(Dispatchers.IO) {
        try {
            // Expected API endpoint: GET /api/device/{deviceId}/status
            // Expected response: { "enabled": true/false }
            val url = "$serverUrl/api/device/$deviceId/status"

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = httpClient.newCall(request).execute()

            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val json = JSONObject(body)
                    return@withContext json.optBoolean("enabled", true)
                }
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "Error checking online status", e)
            null
        }
    }

    data class AppAccessResult(
        val allowed: Boolean,
        val reason: AppAccessDeniedReason? = null,
        val message: String = ""
    )

    enum class AppAccessDeniedReason {
        DISABLED_BY_PARENT,
        OUTSIDE_SCHEDULE
    }
}
