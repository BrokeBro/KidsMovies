package com.kidsmovies.app.data.database.entities

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(tableName = "tags")
data class Tag(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val color: String = "#4CAF50", // Default green color
    val description: String = "",
    val isSystemTag: Boolean = false, // For special tags like "Enabled", "Disabled"
    val dateCreated: Long = System.currentTimeMillis()
) : Parcelable
