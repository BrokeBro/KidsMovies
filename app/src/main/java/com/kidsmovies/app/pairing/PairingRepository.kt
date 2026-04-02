package com.kidsmovies.app.pairing

import android.os.Build
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
            // Atomically claim the pairing code using a Firebase transaction
            onStatus?.invoke("Looking up pairing code...")
            Log.d(TAG, "Step 2: Atomically claiming pairing code $code")
            val codeRef = database.getReference("$PAIRING_CODES_PATH/$code")

            val claimedCode = suspendCoroutine<PairingCode?> { cont ->
                codeRef.runTransaction(object : Transaction.Handler {
                    override fun doTransaction(currentData: MutableData): Transaction.Result {
                        val existing = currentData.getValue(PairingCode::class.java)
                            ?: return Transaction.success(currentData) // Code doesn't exist

                        if (existing.used) {
                            return Transaction.success(currentData) // Already claimed
                        }

                        // Mark as used atomically
                        currentData.child("used").value = true
                        currentData.child("usedBy").value = childUid
                        return Transaction.success(currentData)
                    }

                    override fun onComplete(
                        error: com.google.firebase.database.DatabaseError?,
                        committed: Boolean,
                        currentData: com.google.firebase.database.DataSnapshot?
                    ) {
                        if (error != null) {
                            cont.resumeWithException(error.toException())
                            return
                        }
                        val result = currentData?.getValue(PairingCode::class.java)
                        cont.resume(result)
                    }
                })
            }
            Log.d(TAG, "Step 2 complete: Transaction finished")

            if (claimedCode == null) {
                onStatus?.invoke("Code not found")
                return PairingResult.CodeInvalid
            }

            // Check if someone else claimed it (usedBy is set but not to us)
            if (claimedCode.used && claimedCode.usedBy != childUid) {
                Log.w(TAG, "Pairing code was claimed by another device: ${claimedCode.usedBy}")
                onStatus?.invoke("Code already used")
                return PairingResult.CodeInvalid
            }

            // Check if code has expired
            if (System.currentTimeMillis() > claimedCode.expiresAt) {
                onStatus?.invoke("Code expired")
                return PairingResult.CodeExpired
            }

            val familyId = claimedCode.familyId
            val parentUid = claimedCode.parentUid
            Log.d(TAG, "Pairing code claimed: familyId=$familyId")

            // Register this device under the family (use 'children' path to match parent app)
            onStatus?.invoke("Registering device...")
            Log.d(TAG, "Step 3: Registering device at $FAMILIES_PATH/$familyId/children/$childUid/deviceInfo")
            val deviceRef = database.getReference("$FAMILIES_PATH/$familyId/children/$childUid/deviceInfo")
            val deviceData = mapOf(
                "deviceName" to deviceName,
                "pairedAt" to System.currentTimeMillis(),
                "lastSeen" to System.currentTimeMillis(),
                "isRevoked" to false,
                "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL}"
            )
            deviceRef.setValue(deviceData).await()
            Log.d(TAG, "Step 3 complete: Device registered")

            // Delete the used pairing code
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
                "$FAMILIES_PATH/${state.familyId}/children/${state.childUid}/deviceInfo/lastSeen"
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
