package com.kidsmovies.app.data.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "video_tag_cross_ref",
    primaryKeys = ["videoId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = Video::class,
            parentColumns = ["id"],
            childColumns = ["videoId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Tag::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["videoId"]),
        Index(value = ["tagId"])
    ]
)
data class VideoTagCrossRef(
    val videoId: Long,
    val tagId: Long
)
