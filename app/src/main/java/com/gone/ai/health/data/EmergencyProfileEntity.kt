package com.gone.ai.health.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.gone.ai.health.domain.EmergencyIdGenerator

/**
 * Persisted emergency profile for a patient.
 *
 * ONE ROW PER PATIENT
 *
 * [patientId] is the primary key, not [emergencyId]. This enforces at the schema level that
 * each patient has exactly one active emergency profile. The [emergencyId] is a display and
 * local display key. Online sharing uses a separate 256-bit read capability held by
 * EmergencySync; the database record is always looked up by [patientId].
 *
 * PRIVACY DEFAULTS
 *
 * The `share_*` fields default toward disclosure of safety-critical information (allergies,
 * conditions, live vitals) and away from disclosure of sensitive medication data. This is the
 * medical triage trade-off: knowing about a penicillin allergy or asthma is more immediately
 * relevant to a first responder than knowing the patient takes metformin.
 *
 * [shareMedications] is false by default — it is the most sensitive static field and requires
 * an explicit opt-in from the patient/caregiver.
 *
 * ADDED IN DATABASE VERSION 5
 *
 * Version 5 migration adds the `emergency_profiles` table additively. No existing table,
 * column, or row is touched. See [GoneMigrations.MIGRATION_4_5].
 */
@Entity(tableName = "emergency_profiles")
data class EmergencyProfileEntity(
    /** FK → patients.id. One profile per patient; primary key enforces that. */
    @PrimaryKey val patientId: String,

    /**
     * Random, non-sequential Emergency ID. Embedded in the NFC tag URL and as the JSON
     * `id` in the offline payload. Generated client-side once via [EmergencyIdGenerator];
     * never derived from [patientId] or any sequential counter.
     */
    val emergencyId: String,

    // ── Medical identity fields ──────────────────────────────────────────────
    val bloodGroup: String? = null,
    /** Free text, comma-separated. Kept intentionally short for QR capacity. */
    val allergies: String? = null,
    /** Free text, comma-separated. Kept intentionally short. */
    val chronicConditions: String? = null,
    /** Opt-in only. Null until the patient explicitly adds this. */
    val medications: String? = null,
    /** e.g. "Pacemaker" — flags contraindications to emergency procedures. */
    val implantedDevices: String? = null,
    /**
     * Primary emergency contact phone number.
     * The offline snapshot carries only one number (space constraint on tag/QR).
     * Additional contacts may be added via [emergencyContactsJson] for in-app display,
     * but only the primary appears in the offline payload.
     */
    val primaryEmergencyContact: String? = null,
    /**
     * Additional contacts as a JSON array of {label, phone} objects.
     * Used only for in-app display — not included in the offline snapshot.
     */
    val emergencyContactsJson: String = "[]",

    // ── Visibility toggles (patient/caregiver controls the public snapshot) ─
    /** Include allergies in the QR/NFC snapshot. Default true (safety-critical). */
    val shareAllergies: Boolean = true,
    /** Include chronic conditions in the QR/NFC snapshot. Default true. */
    val shareConditions: Boolean = true,
    /**
     * Include current medications in the QR/NFC snapshot.
     *
     * DEFAULT FALSE — the most sensitive static field. A first responder who
     * shouldn't see someone's HIV medication or psychiatric medication can't see it
     * unless the patient explicitly opts in. The patient can still write it so it
     * appears in the in-app preview and is available for the live mode later.
     */
    val shareMedications: Boolean = false,
    /** Include the last vitals reading in the QR/NFC snapshot. Default true. */
    val shareLiveVitals: Boolean = true,

    // ── Write history ────────────────────────────────────────────────────────
    /**
     * Epoch millis when the NFC tag was last written from this device.
     * Null means the tag has never been written (new profile, or regenerated ID).
     */
    val tagWrittenAt: Long? = null,
    /**
     * Epoch millis when a QR code was last generated from this profile.
     * Null means a QR has never been generated.
     */
    val qrGeneratedAt: Long? = null,

    val createdAt: Long,
    val updatedAt: Long
)
