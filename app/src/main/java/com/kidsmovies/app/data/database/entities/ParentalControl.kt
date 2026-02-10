package com.kidsmovies.app.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "parental_control")
data class ParentalControl(
    @PrimaryKey
    val id: Int = 1, // Single row for parental control
    val deviceId: String = "", // Unique device identifier for online sync
    val isAppEnabled: Boolean = true, // Master switch for app access
    val lastOnlineCheck: Long = 0,
    val lastKnownStatus: Boolean = true, // Fallback status when offline
    val parentalServerUrl: String = "", // URL for parental control server
    val syncInterval: Long = 300000, // 5 minutes in milliseconds
    val blockedMessage: String = "Ask your parent to enable video time!",
    val scheduleEnabled: Boolean = false,
    val scheduleStartTime: String = "08:00", // HH:mm format
    val scheduleEndTime: String = "20:00" // HH:mm format
)
