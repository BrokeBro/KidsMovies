package com.kidsmovies.app.utils

object Constants {
    // Video file extensions
    val VIDEO_EXTENSIONS = listOf(
        ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".webm",
        ".m4v", ".3gp", ".3g2", ".mpeg", ".mpg", ".ts", ".mts"
    )

    // MIME types for videos
    val VIDEO_MIME_TYPES = listOf(
        "video/mp4",
        "video/x-matroska",
        "video/avi",
        "video/quicktime",
        "video/x-ms-wmv",
        "video/x-flv",
        "video/webm",
        "video/3gpp",
        "video/3gpp2",
        "video/mpeg"
    )

    // Default scan folders
    val DEFAULT_SCAN_FOLDERS = listOf(
        "/storage/emulated/0/Movies",
        "/storage/emulated/0/Download",
        "/storage/emulated/0/DCIM",
        "/storage/emulated/0/Video"
    )

    // Thumbnail settings
    const val THUMBNAIL_WIDTH = 320
    const val THUMBNAIL_HEIGHT = 180
    const val THUMBNAIL_DIR = "thumbnails"

    // Parental control
    const val PARENTAL_CHECK_INTERVAL = 300000L // 5 minutes
    const val PARENTAL_SERVER_TIMEOUT = 10000L // 10 seconds

    // OneDrive placeholder
    const val ONEDRIVE_API_BASE = "https://graph.microsoft.com/v1.0"

    // Sort orders
    const val SORT_TITLE_ASC = "title_asc"
    const val SORT_TITLE_DESC = "title_desc"
    const val SORT_DATE_ASC = "date_asc"
    const val SORT_DATE_DESC = "date_desc"
    const val SORT_RECENT = "recent"

    // Grid columns
    const val MIN_GRID_COLUMNS = 2
    const val MAX_GRID_COLUMNS = 6
    const val DEFAULT_GRID_COLUMNS = 4

    // Request codes
    const val REQUEST_STORAGE_PERMISSION = 100
    const val REQUEST_FOLDER_PICKER = 101
    const val REQUEST_IMAGE_PICKER = 102

    // Notification channels
    const val CHANNEL_SCAN_SERVICE = "scan_service_channel"
    const val NOTIFICATION_SCAN_SERVICE = 1001
}

object ColorSchemes {
    data class ColorScheme(
        val name: String,
        val displayName: String,
        val primaryColor: String,
        val primaryDarkColor: String,
        val accentColor: String,
        val backgroundColor: String,
        val cardColor: String
    )

    val schemes = mapOf(
        "blue" to ColorScheme(
            name = "blue",
            displayName = "Ocean Blue",
            primaryColor = "#2196F3",
            primaryDarkColor = "#1976D2",
            accentColor = "#03A9F4",
            backgroundColor = "#1A1A2E",
            cardColor = "#16213E"
        ),
        "green" to ColorScheme(
            name = "green",
            displayName = "Forest Green",
            primaryColor = "#4CAF50",
            primaryDarkColor = "#388E3C",
            accentColor = "#8BC34A",
            backgroundColor = "#1A2E1A",
            cardColor = "#213E21"
        ),
        "purple" to ColorScheme(
            name = "purple",
            displayName = "Royal Purple",
            primaryColor = "#9C27B0",
            primaryDarkColor = "#7B1FA2",
            accentColor = "#E040FB",
            backgroundColor = "#2E1A2E",
            cardColor = "#3E2140"
        ),
        "orange" to ColorScheme(
            name = "orange",
            displayName = "Sunny Orange",
            primaryColor = "#FF9800",
            primaryDarkColor = "#F57C00",
            accentColor = "#FFB74D",
            backgroundColor = "#2E2A1A",
            cardColor = "#3E3521"
        ),
        "pink" to ColorScheme(
            name = "pink",
            displayName = "Pretty Pink",
            primaryColor = "#E91E63",
            primaryDarkColor = "#C2185B",
            accentColor = "#FF4081",
            backgroundColor = "#2E1A24",
            cardColor = "#3E2130"
        ),
        "red" to ColorScheme(
            name = "red",
            displayName = "Ruby Red",
            primaryColor = "#F44336",
            primaryDarkColor = "#D32F2F",
            accentColor = "#FF5252",
            backgroundColor = "#2E1A1A",
            cardColor = "#3E2121"
        )
    )

    fun getScheme(name: String): ColorScheme {
        return schemes[name] ?: schemes["blue"]!!
    }

    fun getAllSchemes(): List<ColorScheme> {
        return schemes.values.toList()
    }
}
