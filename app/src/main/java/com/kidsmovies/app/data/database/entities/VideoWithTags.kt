package com.kidsmovies.app.data.database.entities

import android.os.Parcelable
import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import kotlinx.parcelize.Parcelize

@Parcelize
data class VideoWithTags(
    @Embedded
    val video: Video,

    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = VideoTagCrossRef::class,
            parentColumn = "videoId",
            entityColumn = "tagId"
        )
    )
    val tags: List<Tag>
) : Parcelable
