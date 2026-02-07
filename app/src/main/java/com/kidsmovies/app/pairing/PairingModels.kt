package com.kidsmovies.app.pairing

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a pairing code stored in Firebase
 */
data class PairingCode(
    val familyId: String = "",
    val parentUid: String = "",
    val createdAt: Long = 0,
    val expiresAt: Long = 0
)

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
