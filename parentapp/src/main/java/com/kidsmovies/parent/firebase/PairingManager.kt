package com.kidsmovies.parent.firebase

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.kidsmovies.shared.models.FirebasePaths
import com.kidsmovies.shared.models.PairingCode
import kotlinx.coroutines.tasks.await
import kotlin.random.Random

class PairingManager {

    private val database = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()

    companion object {
        private const val TAG = "PairingManager"
        private const val CODE_LENGTH = 6
        private const val CODE_VALIDITY_MINUTES = 15
    }

    /**
     * Generate a new pairing code for the given family
     */
    suspend fun generatePairingCode(familyId: String): PairingCode? {
        val userId = auth.currentUser?.uid ?: return null

        // Generate a unique 6-digit code
        var code: String
        var attempts = 0
        do {
            code = generateRandomCode()
            val existing = database.getReference(FirebasePaths.pairingCodePath(code))
                .get()
                .await()
            attempts++
        } while (existing.exists() && attempts < 10)

        if (attempts >= 10) {
            Log.e(TAG, "Failed to generate unique code after 10 attempts")
            return null
        }

        val now = System.currentTimeMillis()
        val pairingCode = PairingCode(
            code = code,
            familyId = familyId,
            createdAt = now,
            expiresAt = now + (CODE_VALIDITY_MINUTES * 60 * 1000),
            createdBy = userId,
            used = false,
            usedBy = null
        )

        database.getReference(FirebasePaths.pairingCodePath(code))
            .setValue(pairingCode)
            .await()

        return pairingCode
    }

    /**
     * Get an existing valid pairing code or generate a new one
     */
    suspend fun getOrGeneratePairingCode(familyId: String): PairingCode? {
        val userId = auth.currentUser?.uid ?: return null

        // Look for existing valid code
        val codesSnapshot = database.getReference(FirebasePaths.PAIRING_CODES)
            .orderByChild("createdBy")
            .equalTo(userId)
            .get()
            .await()

        for (child in codesSnapshot.children) {
            val code = child.getValue(PairingCode::class.java)
            if (code != null && code.isValid() && code.familyId == familyId) {
                return code
            }
        }

        // Generate new code
        return generatePairingCode(familyId)
    }

    /**
     * Invalidate an existing pairing code
     */
    suspend fun invalidateCode(code: String) {
        database.getReference(FirebasePaths.pairingCodePath(code))
            .removeValue()
            .await()
    }

    /**
     * Clean up expired pairing codes (can be called periodically)
     */
    suspend fun cleanupExpiredCodes() {
        val userId = auth.currentUser?.uid ?: return

        val codesSnapshot = database.getReference(FirebasePaths.PAIRING_CODES)
            .orderByChild("createdBy")
            .equalTo(userId)
            .get()
            .await()

        for (child in codesSnapshot.children) {
            val code = child.getValue(PairingCode::class.java)
            if (code != null && (code.isExpired() || code.used)) {
                child.ref.removeValue()
            }
        }
    }

    private fun generateRandomCode(): String {
        val digits = "0123456789"
        return (1..CODE_LENGTH)
            .map { digits[Random.nextInt(digits.length)] }
            .joinToString("")
    }
}
