package com.kidsmovies.app.data.database.entities

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(tableName = "scan_folders")
data class ScanFolder(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val path: String,
    val name: String,
    val includeSubfolders: Boolean = true,
    val isEnabled: Boolean = true,
    val isDownloadFolder: Boolean = false,
    val dateAdded: Long = System.currentTimeMillis()
) : Parcelable
