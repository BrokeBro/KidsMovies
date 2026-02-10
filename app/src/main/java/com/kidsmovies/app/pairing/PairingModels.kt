package com.kidsmovies.app.pairing

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a pairing code stored in Firebase
 * Must match the structure created by parent app
 */
data class PairingCode(
    val code: String = "",
    val familyId: String = "",
    val createdAt: Long = 0,
    val expiresAt: Long = 0,
    val createdBy: String = "",  // Parent UID who created this code
    val used: Boolean = false,
    val usedBy: String? = null
) {
    // Alias for backwards compatibility
    val parentUid: String get() = createdBy
}

/**
 * Represents the local pairing state stored in Room DB
 */
@Entity(tableName = "pairing_state")
data class PairingState(
    @PrimaryKey val id: Int = 1,  // Singleton
    val familyId: String? = null,
    val childUid: String? = null,
    val parentUid: String? = null,
    val deviceName: String = "Kid's Device",
    val pairedAt: Long? = null,
    val isPaired: Boolean = false
)

/**
 * Result of a pairing attempt
 */
sealed class PairingResult {
    data class Success(val familyId: String) : PairingResult()
    data class Error(val message: String) : PairingResult()
    object CodeExpired : PairingResult()
    object CodeInvalid : PairingResult()
    object NetworkError : PairingResult()
}
