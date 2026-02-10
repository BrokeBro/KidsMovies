package com.kidsmovies.app.data.repository

import com.kidsmovies.app.data.database.dao.TagDao
import com.kidsmovies.app.data.database.entities.Tag
import com.kidsmovies.app.data.database.entities.VideoTagCrossRef
import kotlinx.coroutines.flow.Flow

class TagRepository(
    private val tagDao: TagDao
) {
    fun getAllTagsFlow(): Flow<List<Tag>> = tagDao.getAllTagsFlow()

    fun getUserTagsFlow(): Flow<List<Tag>> = tagDao.getUserTagsFlow()

    fun getSystemTagsFlow(): Flow<List<Tag>> = tagDao.getSystemTagsFlow()

    suspend fun getAllTags(): List<Tag> = tagDao.getAllTags()

    suspend fun getTagById(id: Long): Tag? = tagDao.getTagById(id)

    suspend fun getTagByName(name: String): Tag? = tagDao.getTagByName(name)

    suspend fun insertTag(tag: Tag): Long = tagDao.insert(tag)

    suspend fun insertTags(tags: List<Tag>): List<Long> = tagDao.insertAll(tags)

    suspend fun updateTag(tag: Tag) = tagDao.update(tag)

    suspend fun deleteTag(tag: Tag) = tagDao.delete(tag)

    suspend fun deleteTagById(tagId: Long) = tagDao.deleteById(tagId)

    suspend fun deleteAllUserTags() = tagDao.deleteAllUserTags()

    suspend fun tagExists(name: String): Boolean = tagDao.tagExists(name)

    suspend fun getTagCount(): Int = tagDao.getTagCount()

    suspend fun getVideoCountForTag(tagId: Long): Int = tagDao.getVideoCountForTag(tagId)

    suspend fun getVideoIdsWithTag(tagId: Long): List<Long> = tagDao.getVideoIdsWithTag(tagId)

    // Video-Tag associations
    suspend fun addTagToVideo(videoId: Long, tagId: Long) {
        tagDao.insertVideoTagCrossRef(VideoTagCrossRef(videoId, tagId))
    }

    suspend fun removeTagFromVideo(videoId: Long, tagId: Long) {
        tagDao.removeTagFromVideo(videoId, tagId)
    }

    suspend fun removeAllTagsFromVideo(videoId: Long) {
        tagDao.removeAllTagsFromVideo(videoId)
    }

    fun getTagsForVideoFlow(videoId: Long): Flow<List<Tag>> = tagDao.getTagsForVideoFlow(videoId)

    suspend fun getTagsForVideo(videoId: Long): List<Tag> = tagDao.getTagsForVideo(videoId)

    suspend fun videoHasTag(videoId: Long, tagId: Long): Boolean = tagDao.videoHasTag(videoId, tagId)
}
