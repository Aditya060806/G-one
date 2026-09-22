package com.gone.ai.health.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [EmergencyProfileEntity].
 *
 * The table has one row per patient, so every write is an upsert — there is no
 * concept of "insert vs update" at the call site.
 */
@Dao
interface EmergencyDao {

    /**
     * Create or replace the emergency profile for a patient.
     * Always overwrites on conflict — the profile is a "latest state" record,
     * not an event log.
     */
    @Upsert
    suspend fun upsert(profile: EmergencyProfileEntity)

    /**
     * Observe the emergency profile for [patientId].
     * Emits null if the patient has no profile yet (first launch).
     */
    @Query("SELECT * FROM emergency_profiles WHERE patientId = :patientId")
    fun observe(patientId: String): Flow<EmergencyProfileEntity?>

    /**
     * One-shot read of the emergency profile.
     * Returns null when the patient has no profile.
     */
    @Query("SELECT * FROM emergency_profiles WHERE patientId = :patientId")
    suspend fun get(patientId: String): EmergencyProfileEntity?

    /**
     * Stamp the tag-write timestamp after a successful NFC write.
     * Called by [EmergencyRepository.markTagWritten].
     */
    @Query("UPDATE emergency_profiles SET tagWrittenAt = :at WHERE patientId = :patientId")
    suspend fun markTagWritten(patientId: String, at: Long)

    /**
     * Stamp the QR-generation timestamp after a QR bitmap is successfully encoded.
     * Called by [EmergencyRepository.markQrGenerated].
     */
    @Query("UPDATE emergency_profiles SET qrGeneratedAt = :at WHERE patientId = :patientId")
    suspend fun markQrGenerated(patientId: String, at: Long)

    /**
     * Clear NFC write and QR timestamps after a regeneration.
     * The old tag/QR are now invalid; the timestamps would be misleading.
     */
    @Query(
        "UPDATE emergency_profiles SET tagWrittenAt = NULL, qrGeneratedAt = NULL WHERE patientId = :patientId"
    )
    suspend fun clearWriteHistory(patientId: String)

    @Query("DELETE FROM emergency_profiles WHERE patientId = :patientId")
    suspend fun delete(patientId: String)
}
