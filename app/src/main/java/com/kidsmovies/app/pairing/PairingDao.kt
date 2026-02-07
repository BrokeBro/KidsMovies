package com.kidsmovies.app.pairing

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PairingDao {
    @Query("SELECT * FROM pairing_state WHERE id = 1")
    suspend fun getPairingState(): PairingState?

    @Query("SELECT * FROM pairing_state WHERE id = 1")
    fun getPairingStateFlow(): Flow<PairingState?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePairingState(state: PairingState)

    @Query("UPDATE pairing_state SET isPaired = 0, familyId = NULL, parentUid = NULL, pairedAt = NULL WHERE id = 1")
    suspend fun clearPairing()

    @Query("SELECT isPaired FROM pairing_state WHERE id = 1")
    suspend fun isPaired(): Boolean?
}
