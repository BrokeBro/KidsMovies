package com.kidsmovies.app.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_settings")
data class AppSettings(
    @PrimaryKey
    val id: Int = 1, // Single row for app settings
    val isSetupComplete: Boolean = false,
    val colorScheme: String = "blue", // blue, green, purple, orange, pink, red
    val childName: String = "",
    val lastScanTime: Long = 0,
    val autoScanOnStartup: Boolean = true,
    val showVideoInfo: Boolean = true,
    val gridColumns: Int = 4, // Number of columns in grid
    val sortOrder: String = "title_asc", // title_asc, title_desc, date_asc, date_desc, recent
    val oneDriveFolderUrl: String = "",
    val oneDriveEnabled: Boolean = false
)
