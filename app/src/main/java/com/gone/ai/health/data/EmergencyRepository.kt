package com.gone.ai.health.data

import com.gone.ai.health.domain.EmergencyIdGenerator
import com.gone.ai.health.domain.EmergencyPayload
import com.gone.ai.health.domain.Severity
import kotlinx.coroutines.flow.Flow

/**
 * Business-logic layer between the DAO and the ViewModel.
 *
 * RESPONSIBILITIES
 *
 * 1. Ensure a profile always exists for the patient (create on first access).
 * 2. Build an [EmergencyPayload] snapshot from the profile + current DB state
 *    (latest vitals + most recent anomaly severity). The snapshot is built at
 *    write-time, not when the profile was last edited, so it always embeds the
 *    freshest available reading.
 * 3. Stamp write-history timestamps after successful NFC/QR operations.
 * 4. Handle ID regeneration (generate new ID, clear stale timestamps).
 *
 * THREAD SAFETY
 *
 * All suspend functions run on whatever dispatcher the caller provides. Room
 * DAOs are already dispatcher-agnostic (they suspend correctly on IO).
 */
class EmergencyRepository(
    private val dao: EmergencyDao,
    private val vitalsDao: VitalsDao,
    private val anomalyDao: AnomalyDao,
    private val patientId: String
) {

    /** Live stream of the emergency profile. Emits null if no profile exists yet. */
    fun observeProfile(): Flow<EmergencyProfileEntity?> = dao.observe(patientId)

    /**
     * Return the existing profile, or create a default one and persist it.
     *
     * Called at the start of any write operation (NFC / QR) to guarantee the
     * profile row exists before trying to stamp timestamps against it.
     */
    suspend fun ensureProfile(): EmergencyProfileEntity =
        dao.get(patientId) ?: EmergencyProfileEntity(
            patientId  = patientId,
            emergencyId = EmergencyIdGenerator.generate(),
            createdAt  = System.currentTimeMillis(),
            updatedAt  = System.currentTimeMillis()
        ).also { dao.upsert(it) }

    /**
     * Save an edited profile.
     *
     * Always stamps [updatedAt] with the current time so the UI can show when
     * the profile was last modified.
     */
    suspend fun save(profile: EmergencyProfileEntity) =
        dao.upsert(profile.copy(updatedAt = System.currentTimeMillis()))

    /**
     * Build a snapshot payload suitable for writing to the NFC tag or QR code.
     *
     * Called just before the physical write so the snapshot always reflects the
     * most recent reading in the database — not whatever reading was in the DB
     * the last time the profile was edited.
     *
     * Privacy filtering is applied here, not in the DAO or the renderer:
     * a field that the patient opted out of is set to null, which causes
     * [EmergencyPayload.toCompactJson] to omit it from the byte stream entirely.
     *
     * @param profile The profile whose share_* flags control field visibility.
     * @param patientName The patient's display name (from PatientEntity or UserProfile).
     * @param age The patient's age.
     */
    suspend fun buildPayload(
        profile: EmergencyProfileEntity,
        patientName: String?,
        age: Int?
    ): EmergencyPayload {
        val now = System.currentTimeMillis()

        // Latest reading — used for dynamic vitals block
        val reading = vitalsDao.latestWearable(patientId)

        return EmergencyPayload(
            name             = patientName,
            age              = age,
            bloodGroup       = profile.bloodGroup,
            allergies        = profile.allergies.takeIf { profile.shareAllergies },
            chronicConditions = profile.chronicConditions.takeIf { profile.shareConditions },
            medications      = profile.medications.takeIf { profile.shareMedications },
            implantedDevices = profile.implantedDevices,
            emergencyContact = profile.primaryEmergencyContact,
            heartRate        = reading?.heartRate.takeIf { profile.shareLiveVitals },
            spo2             = reading?.spo2.takeIf { profile.shareLiveVitals },
            bodyTempC        = reading?.bodyTempC.takeIf { profile.shareLiveVitals },
            // A snapshot is not enough to establish a fall or a current clinical risk.
            // Stored anomaly events do not carry sufficient provenance for public sharing.
            motionStatus     = null,
            riskStatus       = null,
            readingTimestamp = reading?.timestamp.takeIf { profile.shareLiveVitals },
            snapshotTimestamp = now
        )
    }

    /** Stamp the NFC tag-write timestamp. Call after a confirmed successful write. */
    suspend fun markTagWritten() = dao.markTagWritten(patientId, System.currentTimeMillis())

    /** Stamp the QR generation timestamp. Call after a bitmap is successfully encoded. */
    suspend fun markQrGenerated() = dao.markQrGenerated(patientId, System.currentTimeMillis())

    /**
     * Replace the current emergency ID with a new random one, and clear write history.
     *
     * The old NFC tag and QR code will contain the old ID — they won't link to the
     * new profile record. The UI must warn the user clearly before calling this.
     */
    suspend fun regenerateId(current: EmergencyProfileEntity) {
        dao.upsert(
            current.copy(
                emergencyId   = EmergencyIdGenerator.generate(),
                tagWrittenAt  = null,
                qrGeneratedAt = null,
                updatedAt     = System.currentTimeMillis()
            )
        )
    }
}
