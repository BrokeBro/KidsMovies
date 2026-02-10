package com.kidsmovies.app.data.database.dao

import androidx.room.*
import com.kidsmovies.app.data.database.entities.Tag
import com.kidsmovies.app.data.database.entities.VideoTagCrossRef
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {

    // Insert operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tag: Tag): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tags: List<Tag>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertVideoTagCrossRef(crossRef: VideoTagCrossRef)

    // Update operations
    @Update
    suspend fun update(tag: Tag)

    // Delete operations
    @Delete
    suspend fun delete(tag: Tag)

    @Query("DELETE FROM tags WHERE id = :tagId")
    suspend fun deleteById(tagId: Long)

    @Query("DELETE FROM video_tag_cross_ref WHERE videoId = :videoId AND tagId = :tagId")
    suspend fun removeTagFromVideo(videoId: Long, tagId: Long)

    @Query("DELETE FROM video_tag_cross_ref WHERE videoId = :videoId")
    suspend fun removeAllTagsFromVideo(videoId: Long)

    @Query("DELETE FROM tags WHERE isSystemTag = 0")
    suspend fun deleteAllUserTags()

    // Query operations
    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun getAllTagsFlow(): Flow<List<Tag>>

    @Query("SELECT * FROM tags ORDER BY name ASC")
    suspend fun getAllTags(): List<Tag>

    @Query("SELECT * FROM tags WHERE isSystemTag = 0 ORDER BY name ASC")
    fun getUserTagsFlow(): Flow<List<Tag>>

    @Query("SELECT * FROM tags WHERE isSystemTag = 1 ORDER BY name ASC")
    fun getSystemTagsFlow(): Flow<List<Tag>>

    @Query("SELECT * FROM tags WHERE id = :tagId")
    suspend fun getTagById(tagId: Long): Tag?

    @Query("SELECT * FROM tags WHERE name = :name")
    suspend fun getTagByName(name: String): Tag?

    // Get tags for a specific video
    @Query("""
        SELECT t.* FROM tags t
        INNER JOIN video_tag_cross_ref vtc ON t.id = vtc.tagId
        WHERE vtc.videoId = :videoId
        ORDER BY t.name ASC
    """)
    fun getTagsForVideoFlow(videoId: Long): Flow<List<Tag>>

    @Query("""
        SELECT t.* FROM tags t
        INNER JOIN video_tag_cross_ref vtc ON t.id = vtc.tagId
        WHERE vtc.videoId = :videoId
        ORDER BY t.name ASC
    """)
    suspend fun getTagsForVideo(videoId: Long): List<Tag>

    // Get videos with a specific tag
    @Query("""
        SELECT videoId FROM video_tag_cross_ref
        WHERE tagId = :tagId
    """)
    suspend fun getVideoIdsWithTag(tagId: Long): List<Long>

    // Count operations
    @Query("SELECT COUNT(*) FROM tags")
    suspend fun getTagCount(): Int

    @Query("""
        SELECT COUNT(*) FROM video_tag_cross_ref
        WHERE tagId = :tagId
    """)
    suspend fun getVideoCountForTag(tagId: Long): Int

    // Check if tag exists
    @Query("SELECT EXISTS(SELECT 1 FROM tags WHERE name = :name)")
    suspend fun tagExists(name: String): Boolean

    // Check if video has tag
    @Query("SELECT EXISTS(SELECT 1 FROM video_tag_cross_ref WHERE videoId = :videoId AND tagId = :tagId)")
    suspend fun videoHasTag(videoId: Long, tagId: Long): Boolean
}
