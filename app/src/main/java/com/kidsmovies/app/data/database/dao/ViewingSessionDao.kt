package com.kidsmovies.app.data.database.dao

import androidx.room.*
import com.kidsmovies.app.data.database.entities.ViewingSession
import kotlinx.coroutines.flow.Flow

/**
 * Data class for collection watch time aggregation
 */
data class CollectionWatchTime(
    val collectionId: Long,
    val collectionName: String,
    val totalWatchTimeMs: Long,
    val sessionCount: Int
)

/**
 * Data class for session gap analysis
 */
data class SessionGap(
    val previousEndTime: Long,
    val nextStartTime: Long,
    val gapDurationMs: Long
)

@Dao
interface ViewingSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: ViewingSession): Long

    @Update
    suspend fun update(session: ViewingSession)

    @Delete
    suspend fun delete(session: ViewingSession)

    @Query("DELETE FROM viewing_sessions WHERE id = :sessionId")
    suspend fun deleteById(sessionId: Long)

    @Query("DELETE FROM viewing_sessions")
    suspend fun deleteAll()

    @Query("SELECT * FROM viewing_sessions WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: Long): ViewingSession?

    @Query("SELECT * FROM viewing_sessions ORDER BY startTime DESC")
    fun getAllSessionsFlow(): Flow<List<ViewingSession>>

    @Query("SELECT * FROM viewing_sessions ORDER BY startTime DESC")
    suspend fun getAllSessions(): List<ViewingSession>

    @Query("SELECT * FROM viewing_sessions WHERE endTime IS NULL ORDER BY startTime DESC LIMIT 1")
    suspend fun getActiveSession(): ViewingSession?

    @Query("UPDATE viewing_sessions SET endTime = :endTime, durationWatched = :duration, completed = :completed WHERE id = :sessionId")
    suspend fun endSession(sessionId: Long, endTime: Long, duration: Long, completed: Boolean)

    // Get sessions within a date range
    @Query("SELECT * FROM viewing_sessions WHERE startTime >= :startTime AND startTime <= :endTime ORDER BY startTime DESC")
    suspend fun getSessionsInRange(startTime: Long, endTime: Long): List<ViewingSession>

    // Get sessions for the last N days
    @Query("SELECT * FROM viewing_sessions WHERE startTime >= :sinceTime ORDER BY startTime DESC")
    suspend fun getSessionsSince(sinceTime: Long): List<ViewingSession>

    // Get total watch time for a collection
    @Query("SELECT SUM(durationWatched) FROM viewing_sessions WHERE collectionId = :collectionId")
    suspend fun getTotalWatchTimeForCollection(collectionId: Long): Long?

    // Get top collections by watch time
    @Query("""
        SELECT collectionId, collectionName, SUM(durationWatched) as totalWatchTimeMs, COUNT(*) as sessionCount
        FROM viewing_sessions
        WHERE collectionId IS NOT NULL AND collectionName IS NOT NULL
        GROUP BY collectionId
        ORDER BY totalWatchTimeMs DESC
        LIMIT :limit
    """)
    suspend fun getTopCollectionsByWatchTime(limit: Int = 5): List<CollectionWatchTime>

    // Get total watch time overall
    @Query("SELECT SUM(durationWatched) FROM viewing_sessions")
    suspend fun getTotalWatchTime(): Long?

    // Get average session duration
    @Query("SELECT AVG(durationWatched) FROM viewing_sessions WHERE endTime IS NOT NULL")
    suspend fun getAverageSessionDuration(): Double?

    // Get total number of completed sessions
    @Query("SELECT COUNT(*) FROM viewing_sessions WHERE completed = 1")
    suspend fun getCompletedSessionCount(): Int

    // Get total number of sessions
    @Query("SELECT COUNT(*) FROM viewing_sessions")
    suspend fun getTotalSessionCount(): Int

    // Get sessions by video
    @Query("SELECT * FROM viewing_sessions WHERE videoId = :videoId ORDER BY startTime DESC")
    suspend fun getSessionsForVideo(videoId: Long): List<ViewingSession>

    // Get sessions by collection
    @Query("SELECT * FROM viewing_sessions WHERE collectionId = :collectionId ORDER BY startTime DESC")
    suspend fun getSessionsForCollection(collectionId: Long): List<ViewingSession>

    // Get most recent session
    @Query("SELECT * FROM viewing_sessions WHERE endTime IS NOT NULL ORDER BY endTime DESC LIMIT 1")
    suspend fun getMostRecentCompletedSession(): ViewingSession?

    // Get oldest session
    @Query("SELECT * FROM viewing_sessions ORDER BY startTime ASC LIMIT 1")
    suspend fun getOldestSession(): ViewingSession?

    // Get sessions count per day (for the last 30 days)
    @Query("""
        SELECT startTime FROM viewing_sessions
        WHERE startTime >= :sinceTime
        ORDER BY startTime ASC
    """)
    suspend fun getSessionStartTimesSince(sinceTime: Long): List<Long>
}
