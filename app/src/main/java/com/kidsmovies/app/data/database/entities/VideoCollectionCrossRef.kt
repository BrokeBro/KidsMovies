package com.kidsmovies.app.data.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "video_collection_cross_ref",
    primaryKeys = ["videoId", "collectionId"],
    foreignKeys = [
        ForeignKey(
            entity = Video::class,
            parentColumns = ["id"],
            childColumns = ["videoId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = VideoCollection::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["videoId"]),
        Index(value = ["collectionId"])
    ]
)
data class VideoCollectionCrossRef(
    val videoId: Long,
    val collectionId: Long
)
