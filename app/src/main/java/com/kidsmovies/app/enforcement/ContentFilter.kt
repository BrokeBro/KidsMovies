package com.kidsmovies.app.enforcement

import com.kidsmovies.app.data.database.entities.Video
import com.kidsmovies.app.data.database.entities.VideoCollection

/**
 * Filters collections and videos based on current schedule restrictions.
 * Used to limit what content the child can see and access.
 */
class ContentFilter {

    /**
     * Filter collections based on schedule rules
     * @param collections All available collections
     * @param scheduleResult Current schedule evaluation result
     * @return Filtered list of allowed collections
     */
    fun filterCollections(
        collections: List<VideoCollection>,
        scheduleResult: ScheduleEvaluator.ScheduleResult
    ): List<VideoCollection> {
        if (!scheduleResult.isAllowed) {
            return emptyList()
        }

        val allowedCollections = scheduleResult.allowedCollections

        // If no collection restrictions, return all
        if (allowedCollections == null) {
            return collections
        }

        // Filter to only allowed collections
        // Collection IDs in Firebase are stored as strings, matching collection.id.toString()
        return collections.filter { collection ->
            allowedCollections.contains(collection.id.toString()) ||
            allowedCollections.contains(collection.name) // Also allow matching by name
        }
    }

    /**
     * Filter videos based on schedule rules
     * @param videos All videos in a collection or category
     * @param collectionId The collection these videos belong to (null for all videos)
     * @param scheduleResult Current schedule evaluation result
     * @return Filtered list of allowed videos
     */
    fun filterVideos(
        videos: List<Video>,
        collectionId: Long?,
        scheduleResult: ScheduleEvaluator.ScheduleResult
    ): List<Video> {
        if (!scheduleResult.isAllowed) {
            return emptyList()
        }

        val allowedCollections = scheduleResult.allowedCollections
        val blockedVideos = scheduleResult.blockedVideos
        val allowedVideos = scheduleResult.allowedVideos

        var filteredVideos = videos

        // Filter by collection if restrictions exist
        if (allowedCollections != null) {
            filteredVideos = filteredVideos.filter { video ->
                val videoCollectionId = video.collectionId?.toString()
                videoCollectionId != null && (
                    allowedCollections.contains(videoCollectionId) ||
                    allowedCollections.any { it == collectionId?.toString() }
                )
            }
        }

        // Remove blocked videos
        if (!blockedVideos.isNullOrEmpty()) {
            filteredVideos = filteredVideos.filter { video ->
                !blockedVideos.contains(video.id.toString()) &&
                !blockedVideos.contains(video.title)
            }
        }

        // If there's a whitelist, only show those videos
        if (!allowedVideos.isNullOrEmpty()) {
            filteredVideos = filteredVideos.filter { video ->
                allowedVideos.contains(video.id.toString()) ||
                allowedVideos.contains(video.title)
            }
        }

        return filteredVideos
    }

    /**
     * Check if a specific video can be played
     * @param video The video to check
     * @param collectionId The collection the video is being played from
     * @param scheduleResult Current schedule evaluation result
     * @return true if the video can be played
     */
    fun canPlayVideo(
        video: Video,
        collectionId: Long?,
        scheduleResult: ScheduleEvaluator.ScheduleResult
    ): Boolean {
        if (!scheduleResult.isAllowed) {
            return false
        }

        val allowedCollections = scheduleResult.allowedCollections
        val blockedVideos = scheduleResult.blockedVideos
        val allowedVideos = scheduleResult.allowedVideos

        // Check collection restrictions
        if (allowedCollections != null) {
            val videoCollectionId = video.collectionId?.toString() ?: collectionId?.toString()
            if (videoCollectionId != null && !allowedCollections.contains(videoCollectionId)) {
                return false
            }
        }

        // Check if video is blocked
        if (!blockedVideos.isNullOrEmpty()) {
            if (blockedVideos.contains(video.id.toString()) ||
                blockedVideos.contains(video.title)) {
                return false
            }
        }

        // Check if there's a whitelist and video is on it
        if (!allowedVideos.isNullOrEmpty()) {
            if (!allowedVideos.contains(video.id.toString()) &&
                !allowedVideos.contains(video.title)) {
                return false
            }
        }

        return true
    }

    /**
     * Get the reason why a video cannot be played
     */
    fun getBlockedReason(
        video: Video,
        collectionId: Long?,
        scheduleResult: ScheduleEvaluator.ScheduleResult
    ): String? {
        if (!scheduleResult.isAllowed) {
            return scheduleResult.reason
        }

        val blockedVideos = scheduleResult.blockedVideos
        if (!blockedVideos.isNullOrEmpty()) {
            if (blockedVideos.contains(video.id.toString()) ||
                blockedVideos.contains(video.title)) {
                return "This video is not available right now"
            }
        }

        val allowedCollections = scheduleResult.allowedCollections
        if (allowedCollections != null) {
            val videoCollectionId = video.collectionId?.toString() ?: collectionId?.toString()
            if (videoCollectionId != null && !allowedCollections.contains(videoCollectionId)) {
                return "This collection is not available right now"
            }
        }

        return null
    }
}
