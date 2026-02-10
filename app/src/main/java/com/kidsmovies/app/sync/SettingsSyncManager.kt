package com.kidsmovies.app.sync

import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.gson.Gson
import com.kidsmovies.app.pairing.PairingDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SettingsSyncManager(
    private val pairingDao: PairingDao,
    private val cachedSettingsDao: CachedSettingsDao,
    private val coroutineScope: CoroutineScope
) {
    private val database = FirebaseDatabase.getInstance()
    private val gson = Gson()

    private var settingsListener: ValueEventListener? = null
    private var deviceListener: ValueEventListener? = null
    private var currentFamilyId: String? = null
    private var currentChildUid: String? = null

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState

    companion object {
        private const val TAG = "SettingsSyncManager"
    }

    sealed class SyncState {
        object Idle : SyncState()
        object Syncing : SyncState()
        data class Synced(val timestamp: Long) : SyncState()
        data class Error(val message: String) : SyncState()
    }

    /**
     * Start listening for settings changes from Firebase
     */
    fun startListening() {
        coroutineScope.launch(Dispatchers.IO) {
            val pairingState = pairingDao.getPairingState()
            if (pairingState == null || !pairingState.isPaired) {
                Log.d(TAG, "Not paired, skipping sync")
                return@launch
            }

            val familyId = pairingState.familyId ?: return@launch
            val childUid = pairingState.childUid ?: return@launch

            // Stop existing listeners if family changed
            if (currentFamilyId != familyId) {
                stopListening()
            }

            currentFamilyId = familyId
            currentChildUid = childUid

            // Listen to settings changes
            listenToSettings(familyId)

            // Listen to device-specific changes (revocation, overrides)
            listenToDeviceStatus(familyId, childUid)
        }
    }

    private fun listenToSettings(familyId: String) {
        val settingsRef = database.getReference("families/$familyId/settings")

        settingsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                _syncState.value = SyncState.Syncing
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        parseAndCacheSettings(snapshot)
                        _syncState.value = SyncState.Synced(System.currentTimeMillis())
                        Log.d(TAG, "Settings synced successfully")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse settings", e)
                        _syncState.value = SyncState.Error(e.message ?: "Parse error")
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Settings listener cancelled", error.toException())
                _syncState.value = SyncState.Error(error.message)
            }
        }

        settingsRef.addValueEventListener(settingsListener!!)
    }

    private fun listenToDeviceStatus(familyId: String, childUid: String) {
        val deviceRef = database.getReference("families/$familyId/devices/$childUid")

        deviceListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        parseAndCacheDeviceStatus(snapshot)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse device status", e)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Device listener cancelled", error.toException())
            }
        }

        deviceRef.addValueEventListener(deviceListener!!)
    }

    private suspend fun parseAndCacheSettings(snapshot: DataSnapshot) {
        val now = System.currentTimeMillis()

        // Parse global settings
        val globalSnapshot = snapshot.child("global")
        val globalSettings = CachedGlobalSettings(
            id = 1,
            updatedAt = globalSnapshot.child("updatedAt").getValue(Long::class.java) ?: 0,
            appEnabled = globalSnapshot.child("appEnabled").getValue(Boolean::class.java) ?: true,
            softOffEnabled = globalSnapshot.child("softOffEnabled").getValue(Boolean::class.java) ?: true,
            lastSyncedAt = now
        )
        cachedSettingsDao.saveGlobalSettings(globalSettings)

        // Parse schedules
        val schedulesSnapshot = snapshot.child("schedules")
        val schedules = mutableListOf<CachedSchedule>()
        val scheduleIds = mutableListOf<String>()

        for (scheduleSnapshot in schedulesSnapshot.children) {
            val scheduleId = scheduleSnapshot.key ?: continue
            scheduleIds.add(scheduleId)

            val daysOfWeek = scheduleSnapshot.child("daysOfWeek").children
                .mapNotNull { it.getValue(Int::class.java) }

            val schedule = CachedSchedule(
                scheduleId = scheduleId,
                label = scheduleSnapshot.child("label").getValue(String::class.java) ?: "",
                daysOfWeek = daysOfWeek,
                startTime = scheduleSnapshot.child("startTime").getValue(String::class.java) ?: "00:00",
                endTime = scheduleSnapshot.child("endTime").getValue(String::class.java) ?: "23:59",
                maxViewingMinutes = scheduleSnapshot.child("maxViewingMinutes").getValue(Int::class.java),
                allowedCollectionsJson = parseJsonArray(scheduleSnapshot.child("allowedCollections")),
                blockedVideosJson = parseJsonArray(scheduleSnapshot.child("blockedVideos")),
                allowedVideosJson = parseJsonArray(scheduleSnapshot.child("allowedVideos")),
                appliesToDevicesJson = parseJsonArray(scheduleSnapshot.child("appliesToDevices")),
                isActive = scheduleSnapshot.child("isActive").getValue(Boolean::class.java) ?: true,
                lastSyncedAt = now
            )
            schedules.add(schedule)
        }

        // Save schedules and remove any that no longer exist
        cachedSettingsDao.saveSchedules(schedules)
        if (scheduleIds.isNotEmpty()) {
            cachedSettingsDao.deleteSchedulesNotIn(scheduleIds)
        } else {
            cachedSettingsDao.clearSchedules()
        }

        // Parse device-specific overrides for this device
        currentChildUid?.let { childUid ->
            val overridesSnapshot = snapshot.child("deviceOverrides/$childUid")
            if (overridesSnapshot.exists()) {
                val overrides = CachedDeviceOverrides(
                    id = 1,
                    appEnabled = overridesSnapshot.child("appEnabled").getValue(Boolean::class.java) ?: true,
                    maxViewingMinutesOverride = overridesSnapshot.child("maxViewingMinutesOverride").getValue(Int::class.java),
                    allowedCollectionsJson = parseJsonArray(overridesSnapshot.child("allowedCollections")),
                    lastSyncedAt = now
                )
                cachedSettingsDao.saveDeviceOverrides(overrides)
            }
        }
    }

    private suspend fun parseAndCacheDeviceStatus(snapshot: DataSnapshot) {
        val isRevoked = snapshot.child("isRevoked").getValue(Boolean::class.java) ?: false

        // Update device revocation status
        val existingOverrides = cachedSettingsDao.getDeviceOverrides() ?: CachedDeviceOverrides()
        cachedSettingsDao.saveDeviceOverrides(
            existingOverrides.copy(
                isRevoked = isRevoked,
                lastSyncedAt = System.currentTimeMillis()
            )
        )

        if (isRevoked) {
            Log.w(TAG, "Device has been revoked!")
        }
    }

    private fun parseJsonArray(snapshot: DataSnapshot): String? {
        if (!snapshot.exists()) return null
        val list = snapshot.children.mapNotNull { it.getValue(String::class.java) }
        return if (list.isEmpty()) null else gson.toJson(list)
    }

    /**
     * Stop listening to Firebase
     */
    fun stopListening() {
        currentFamilyId?.let { familyId ->
            settingsListener?.let {
                database.getReference("families/$familyId/settings").removeEventListener(it)
            }
            currentChildUid?.let { childUid ->
                deviceListener?.let {
                    database.getReference("families/$familyId/devices/$childUid").removeEventListener(it)
                }
            }
        }
        settingsListener = null
        deviceListener = null
        currentFamilyId = null
        currentChildUid = null
    }

    /**
     * Get cached settings for enforcement
     */
    suspend fun getEnforcementSettings(): EnforcementSettings {
        return cachedSettingsDao.getEnforcementSettings()
    }

    /**
     * Force a one-time sync (for WorkManager fallback)
     */
    suspend fun forcSync() {
        val pairingState = pairingDao.getPairingState()
        if (pairingState == null || !pairingState.isPaired) return

        val familyId = pairingState.familyId ?: return
        val childUid = pairingState.childUid ?: return

        try {
            _syncState.value = SyncState.Syncing
            val settingsRef = database.getReference("families/$familyId/settings")
            val snapshot = settingsRef.get().await()
            parseAndCacheSettings(snapshot)

            val deviceRef = database.getReference("families/$familyId/devices/$childUid")
            val deviceSnapshot = deviceRef.get().await()
            parseAndCacheDeviceStatus(deviceSnapshot)

            _syncState.value = SyncState.Synced(System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "Force sync failed", e)
            _syncState.value = SyncState.Error(e.message ?: "Sync failed")
        }
    }

}
