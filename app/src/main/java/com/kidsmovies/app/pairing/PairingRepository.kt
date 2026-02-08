package com.kidsmovies.app.pairing

import android.os.Build
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class PairingRepository(
    private val pairingDao: PairingDao
) {
    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()

    companion object {
        private const val TAG = "PairingRepository"
        private const val PAIRING_CODES_PATH = "pairingCodes"
        private const val FAMILIES_PATH = "families"
    }

    /**
     * Get the current Firebase anonymous UID, signing in if necessary
     */
    suspend fun ensureAuthenticated(): String? {
        return try {
            val currentUser = auth.currentUser
            if (currentUser != null) {
                currentUser.uid
            } else {
                val result = auth.signInAnonymously().await()
                result.user?.uid
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to authenticate", e)
            null
        }
    }

    /**
     * Check if this device is already paired
     */
    suspend fun isPaired(): Boolean {
        return pairingDao.isPaired() ?: false
    }

    /**
     * Get pairing state as a Flow for observing changes
     */
    fun getPairingStateFlow(): Flow<PairingState?> {
        return pairingDao.getPairingStateFlow()
    }

    /**
     * Get current pairing state
     */
    suspend fun getPairingState(): PairingState? {
        return pairingDao.getPairingState()
    }

    /**
     * Attempt to pair with a parent using a 6-digit code
     */
    suspend fun pairWithCode(
        code: String,
        deviceName: String,
        onStatus: ((String) -> Unit)? = null
    ): PairingResult {
        // Ensure we're authenticated
        onStatus?.invoke("Authenticating with Firebase...")
        Log.d(TAG, "Step 1: Authenticating with Firebase")
        val childUid = ensureAuthenticated()
        if (childUid == null) {
            Log.e(TAG, "Authentication failed - childUid is null")
            onStatus?.invoke("Authentication failed")
            return PairingResult.NetworkError
        }
        Log.d(TAG, "Step 1 complete: Authenticated as $childUid")

        return try {
            // Read the pairing code from Firebase
            onStatus?.invoke("Looking up pairing code...")
            Log.d(TAG, "Step 2: Looking up pairing code $code")
            val codeRef = database.getReference("$PAIRING_CODES_PATH/$code")
            val snapshot = codeRef.get().await()
            Log.d(TAG, "Step 2 complete: Got snapshot, exists=${snapshot.exists()}")

            if (!snapshot.exists()) {
                onStatus?.invoke("Code not found")
                return PairingResult.CodeInvalid
            }

            val pairingCode = snapshot.getValue(PairingCode::class.java)
            if (pairingCode == null) {
                Log.e(TAG, "Failed to parse pairing code data")
                onStatus?.invoke("Invalid code format")
                return PairingResult.CodeInvalid
            }
            Log.d(TAG, "Pairing code parsed: familyId=${pairingCode.familyId}")

            // Check if code has expired
            if (System.currentTimeMillis() > pairingCode.expiresAt) {
                onStatus?.invoke("Code expired")
                return PairingResult.CodeExpired
            }

            val familyId = pairingCode.familyId
            val parentUid = pairingCode.parentUid

            // Register this device under the family
            onStatus?.invoke("Registering device...")
            Log.d(TAG, "Step 3: Registering device at $FAMILIES_PATH/$familyId/devices/$childUid")
            val deviceRef = database.getReference("$FAMILIES_PATH/$familyId/devices/$childUid")
            val deviceData = mapOf(
                "deviceName" to deviceName,
                "pairedAt" to System.currentTimeMillis(),
                "lastSeen" to System.currentTimeMillis(),
                "isRevoked" to false,
                "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL}"
            )
            deviceRef.setValue(deviceData).await()
            Log.d(TAG, "Step 3 complete: Device registered")

            // Delete the pairing code (it's been used)
            onStatus?.invoke("Finalizing...")
            Log.d(TAG, "Step 4: Deleting used pairing code")
            codeRef.removeValue().await()
            Log.d(TAG, "Step 4 complete: Pairing code deleted")

            // Save pairing state locally
            onStatus?.invoke("Saving locally...")
            Log.d(TAG, "Step 5: Saving pairing state locally")
            val pairingState = PairingState(
                id = 1,
                familyId = familyId,
                childUid = childUid,
                parentUid = parentUid,
                deviceName = deviceName,
                pairedAt = System.currentTimeMillis(),
                isPaired = true
            )
            pairingDao.savePairingState(pairingState)
            Log.d(TAG, "Step 5 complete: Pairing state saved")

            onStatus?.invoke("Connected!")
            Log.i(TAG, "Successfully paired with family: $familyId")
            PairingResult.Success(familyId)

        } catch (e: Exception) {
            Log.e(TAG, "Pairing failed at some step", e)
            onStatus?.invoke("Error: ${e.message}")
            PairingResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Update last seen timestamp in Firebase
     */
    suspend fun updateLastSeen() {
        val state = pairingDao.getPairingState() ?: return
        if (!state.isPaired || state.familyId == null || state.childUid == null) return

        try {
            val deviceRef = database.getReference(
                "$FAMILIES_PATH/${state.familyId}/devices/${state.childUid}/lastSeen"
            )
            deviceRef.setValue(System.currentTimeMillis()).await()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update lastSeen", e)
        }
    }

    /**
     * Clear local pairing (for testing or when revoked)
     */
    suspend fun clearPairing() {
        pairingDao.clearPairing()
    }

    /**
     * Initialize pairing state if not exists
     */
    suspend fun initializePairingState() {
        val existing = pairingDao.getPairingState()
        if (existing == null) {
            // Get or create UID
            val uid = ensureAuthenticated()
            pairingDao.savePairingState(
                PairingState(
                    id = 1,
                    childUid = uid,
                    isPaired = false
                )
            )
        }
    }
}
