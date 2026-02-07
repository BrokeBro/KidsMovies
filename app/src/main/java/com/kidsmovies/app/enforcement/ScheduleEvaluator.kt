package com.kidsmovies.app.enforcement

import com.kidsmovies.app.sync.CachedSchedule
import com.kidsmovies.app.sync.EnforcementSettings
import java.util.Calendar

/**
 * Evaluates schedules to determine if viewing is currently allowed
 * and what restrictions apply.
 */
class ScheduleEvaluator {

    /**
     * Result of schedule evaluation
     */
    data class ScheduleResult(
        val isAllowed: Boolean,
        val activeSchedule: CachedSchedule?,
        val reason: String,
        val maxViewingMinutes: Int?,
        val allowedCollections: List<String>?,
        val blockedVideos: List<String>?,
        val allowedVideos: List<String>?
    ) {
        companion object {
            fun blocked(reason: String) = ScheduleResult(
                isAllowed = false,
                activeSchedule = null,
                reason = reason,
                maxViewingMinutes = null,
                allowedCollections = null,
                blockedVideos = null,
                allowedVideos = null
            )
        }
    }

    /**
     * Evaluate current schedules and determine if viewing is allowed
     */
    fun evaluate(
        settings: EnforcementSettings,
        deviceId: String,
        currentTimeMillis: Long = System.currentTimeMillis()
    ): ScheduleResult {
        // Check device-level blocks first
        if (settings.isDeviceRevoked) {
            return ScheduleResult.blocked("Device has been disconnected")
        }

        if (!settings.isAppEnabled) {
            return ScheduleResult.blocked("App is currently disabled")
        }

        // Find active schedule for current time
        val activeSchedule = findActiveSchedule(settings.schedules, deviceId, currentTimeMillis)

        if (activeSchedule == null) {
            return ScheduleResult.blocked("Outside scheduled viewing time")
        }

        // Get effective limits (device overrides take precedence)
        val maxMinutes = settings.deviceOverrides?.maxViewingMinutesOverride
            ?: activeSchedule.maxViewingMinutes

        val allowedCollections = settings.deviceOverrides?.allowedCollectionsJson?.let {
            com.google.gson.Gson().fromJson(it, com.google.gson.reflect.TypeToken.getParameterized(
                List::class.java, String::class.java
            ).type) as? List<String>
        } ?: activeSchedule.getAllowedCollections()

        return ScheduleResult(
            isAllowed = true,
            activeSchedule = activeSchedule,
            reason = "Within schedule: ${activeSchedule.label}",
            maxViewingMinutes = maxMinutes,
            allowedCollections = allowedCollections,
            blockedVideos = activeSchedule.getBlockedVideos(),
            allowedVideos = activeSchedule.getAllowedVideos()
        )
    }

    /**
     * Find the first active schedule that matches current time and device
     */
    private fun findActiveSchedule(
        schedules: List<CachedSchedule>,
        deviceId: String,
        currentTimeMillis: Long
    ): CachedSchedule? {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = currentTimeMillis
        }

        // Calendar.DAY_OF_WEEK: Sunday=1, Monday=2, etc.
        // We use: Monday=1, Sunday=7
        val dayOfWeek = when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY -> 7
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            else -> 1
        }

        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentMinute = calendar.get(Calendar.MINUTE)
        val currentTimeStr = String.format("%02d:%02d", currentHour, currentMinute)

        return schedules.firstOrNull { schedule ->
            schedule.isActive &&
            schedule.daysOfWeek.contains(dayOfWeek) &&
            schedule.appliesToDevice(deviceId) &&
            isTimeInRange(currentTimeStr, schedule.startTime, schedule.endTime)
        }
    }

    /**
     * Check if current time is within the schedule's time range
     */
    private fun isTimeInRange(current: String, start: String, end: String): Boolean {
        val currentMinutes = timeToMinutes(current)
        val startMinutes = timeToMinutes(start)
        val endMinutes = timeToMinutes(end)

        return if (startMinutes <= endMinutes) {
            // Normal range (e.g., 09:00 - 17:00)
            currentMinutes in startMinutes..endMinutes
        } else {
            // Overnight range (e.g., 22:00 - 06:00)
            currentMinutes >= startMinutes || currentMinutes <= endMinutes
        }
    }

    private fun timeToMinutes(time: String): Int {
        val parts = time.split(":")
        return if (parts.size == 2) {
            parts[0].toIntOrNull()?.times(60)?.plus(parts[1].toIntOrNull() ?: 0) ?: 0
        } else {
            0
        }
    }

    /**
     * Get remaining minutes in current schedule window
     */
    fun getRemainingWindowMinutes(
        schedule: CachedSchedule,
        currentTimeMillis: Long = System.currentTimeMillis()
    ): Int {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = currentTimeMillis
        }

        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentMinute = calendar.get(Calendar.MINUTE)
        val currentMinutes = currentHour * 60 + currentMinute

        val endMinutes = timeToMinutes(schedule.endTime)

        return if (endMinutes > currentMinutes) {
            endMinutes - currentMinutes
        } else {
            // Schedule ends tomorrow
            (24 * 60 - currentMinutes) + endMinutes
        }
    }
}
