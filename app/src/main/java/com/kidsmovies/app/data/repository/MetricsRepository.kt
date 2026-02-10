package com.kidsmovies.app.data.repository

import com.kidsmovies.app.data.database.dao.CollectionWatchTime
import com.kidsmovies.app.data.database.dao.ViewingSessionDao
import com.kidsmovies.app.data.database.entities.ViewingSession
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit

/**
 * Data class for formatted metrics display
 */
data class UsageMetrics(
    val topCollections: List<CollectionWatchTime>,
    val averageSessionMinutes: Double,
    val totalWatchTimeMs: Long,
    val totalSessions: Int,
    val completedSessions: Int,
    val averageBreakDurationMs: Long,
    val longestBreakDurationMs: Long,
    val shortestBreakDurationMs: Long,
    val recentSessions: List<ViewingSession>
)

/**
 * Repository for viewing session metrics and analytics
 */
class MetricsRepository(private val viewingSessionDao: ViewingSessionDao) {

    fun getAllSessionsFlow(): Flow<List<ViewingSession>> = viewingSessionDao.getAllSessionsFlow()

    suspend fun getAllSessions(): List<ViewingSession> = viewingSessionDao.getAllSessions()

    suspend fun getSessionById(sessionId: Long): ViewingSession? = viewingSessionDao.getSessionById(sessionId)

    suspend fun getActiveSession(): ViewingSession? = viewingSessionDao.getActiveSession()

    suspend fun startSession(session: ViewingSession): Long = viewingSessionDao.insert(session)

    suspend fun endSession(sessionId: Long, endTime: Long, duration: Long, completed: Boolean) {
        viewingSessionDao.endSession(sessionId, endTime, duration, completed)
    }

    suspend fun updateSession(session: ViewingSession) = viewingSessionDao.update(session)

    suspend fun deleteSession(session: ViewingSession) = viewingSessionDao.delete(session)

    suspend fun deleteAllSessions() = viewingSessionDao.deleteAll()

    // Metrics calculations

    /**
     * Get top N collections by total watch time
     */
    suspend fun getTopCollectionsByWatchTime(limit: Int = 5): List<CollectionWatchTime> {
        return viewingSessionDao.getTopCollectionsByWatchTime(limit)
    }

    /**
     * Get average session duration in minutes
     */
    suspend fun getAverageSessionDurationMinutes(): Double {
        val avgMs = viewingSessionDao.getAverageSessionDuration() ?: 0.0
        return avgMs / 60000.0
    }

    /**
     * Get total watch time in milliseconds
     */
    suspend fun getTotalWatchTime(): Long {
        return viewingSessionDao.getTotalWatchTime() ?: 0L
    }

    /**
     * Get total number of sessions
     */
    suspend fun getTotalSessionCount(): Int {
        return viewingSessionDao.getTotalSessionCount()
    }

    /**
     * Get number of completed sessions
     */
    suspend fun getCompletedSessionCount(): Int {
        return viewingSessionDao.getCompletedSessionCount()
    }

    /**
     * Calculate breaks between sessions
     * Returns a list of break durations in milliseconds
     */
    suspend fun getBreakDurations(): List<Long> {
        val sessions = viewingSessionDao.getAllSessions()
            .filter { it.endTime != null }
            .sortedBy { it.endTime }

        if (sessions.size < 2) return emptyList()

        val breaks = mutableListOf<Long>()
        for (i in 0 until sessions.size - 1) {
            val currentEnd = sessions[i].endTime ?: continue
            val nextStart = sessions[i + 1].startTime
            val breakDuration = nextStart - currentEnd
            if (breakDuration > 0) {
                breaks.add(breakDuration)
            }
        }
        return breaks
    }

    /**
     * Get average break duration in milliseconds
     */
    suspend fun getAverageBreakDuration(): Long {
        val breaks = getBreakDurations()
        if (breaks.isEmpty()) return 0L
        return breaks.sum() / breaks.size
    }

    /**
     * Get longest break duration in milliseconds
     */
    suspend fun getLongestBreakDuration(): Long {
        return getBreakDurations().maxOrNull() ?: 0L
    }

    /**
     * Get shortest break duration in milliseconds
     */
    suspend fun getShortestBreakDuration(): Long {
        return getBreakDurations().minOrNull() ?: 0L
    }

    /**
     * Get complete usage metrics
     */
    suspend fun getUsageMetrics(): UsageMetrics {
        val breaks = getBreakDurations()
        return UsageMetrics(
            topCollections = getTopCollectionsByWatchTime(5),
            averageSessionMinutes = getAverageSessionDurationMinutes(),
            totalWatchTimeMs = getTotalWatchTime(),
            totalSessions = getTotalSessionCount(),
            completedSessions = getCompletedSessionCount(),
            averageBreakDurationMs = if (breaks.isEmpty()) 0L else breaks.sum() / breaks.size,
            longestBreakDurationMs = breaks.maxOrNull() ?: 0L,
            shortestBreakDurationMs = breaks.minOrNull() ?: 0L,
            recentSessions = viewingSessionDao.getAllSessions().take(10)
        )
    }

    /**
     * Get sessions for the last N days
     */
    suspend fun getSessionsForLastDays(days: Int): List<ViewingSession> {
        val sinceTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
        return viewingSessionDao.getSessionsSince(sinceTime)
    }

    /**
     * Get sessions for a specific video
     */
    suspend fun getSessionsForVideo(videoId: Long): List<ViewingSession> {
        return viewingSessionDao.getSessionsForVideo(videoId)
    }

    /**
     * Get sessions for a specific collection
     */
    suspend fun getSessionsForCollection(collectionId: Long): List<ViewingSession> {
        return viewingSessionDao.getSessionsForCollection(collectionId)
    }

    companion object {
        /**
         * Format milliseconds to a human-readable duration string
         */
        fun formatDuration(ms: Long): String {
            val totalSeconds = ms / 1000
            val days = totalSeconds / 86400
            val hours = (totalSeconds % 86400) / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60

            return when {
                days > 0 -> String.format("%dd %dh %dm", days, hours, minutes)
                hours > 0 -> String.format("%dh %dm", hours, minutes)
                minutes > 0 -> String.format("%dm %ds", minutes, seconds)
                else -> String.format("%ds", seconds)
            }
        }

        /**
         * Format milliseconds to hours with one decimal
         */
        fun formatHours(ms: Long): String {
            val hours = ms / 3600000.0
            return String.format("%.1f hours", hours)
        }

        /**
         * Format milliseconds to minutes with one decimal
         */
        fun formatMinutes(ms: Long): String {
            val minutes = ms / 60000.0
            return String.format("%.1f min", minutes)
        }
    }
}
