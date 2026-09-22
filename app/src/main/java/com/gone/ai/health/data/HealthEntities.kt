package com.gone.ai.health.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.gone.ai.health.domain.AnomalyEvidence
import com.gone.ai.health.domain.AnomalyType
import com.gone.ai.health.domain.RiskScores
import com.gone.ai.health.domain.Severity
import com.gone.ai.health.domain.VitalsSample

/**
 * Room entities for the G-one health record.
 *
 * These are the PERSISTENCE model and are intentionally separate from the pure
 * domain model in `health.domain`. Enums are stored as their stable `wireName`
 * strings rather than via TypeConverters so that:
 *   - the domain package stays free of any Room dependency, and
 *   - reordering or renaming an enum constant cannot silently corrupt stored rows.
 *
 * Added in database version 2 by an additive migration; nothing in version 1
 * (the `library_entries` tables) is touched.
 */

@Entity(tableName = "patients")
data class PatientEntity(
    @PrimaryKey val id: String,
    val name: String,
    val age: Int,
    val sex: String? = null,
    /** Comma-separated free text; a normalised table is Phase 2. */
    val chronicConditions: String? = null,
    val emergencyContactName: String? = null,
    val emergencyContactPhone: String? = null,
    val createdAt: Long
)

@Entity(
    tableName = "devices",
    indices = [Index(value = ["patientId"])]
)
data class DeviceEntity(
    /** Stable device identifier —    val aqi: Int? = null,
    /** SIMULATED | BLE | MANUAL — provenance matters for clinical trust. */
    val source: String,

    // ── Added in version 3 by ALTER TABLE, which appends columns, so declared last ──

    /** Skin temperature, °C. Never core temperature — see [VitalsSample.skinTempC]. */
    val skinTempC: Float? = null,
    /** Mean EMG envelope over the stored interval, 12-bit ADC counts. */
    val emgMean: Int? = null,
    /** Highest EMG envelope value within the stored interval, 12-bit ADC counts. */
    val emgMax: Int? = null
)MAC address, or a synthetic id when simulated. */
    @PrimaryKey val id: String,
    val patientId: String,
    val displayName: String,
    val transport: String,
    val lastSeenAt: Long,
    val batteryPercent: Int? = null,
    val firmwareVersion: String? = null
)

@Entity(
    tableName = "vitals_readings",
    indices = [
        // Composite index in (patientId, timestamp) order: every read path is
        // "latest N readings for this patient", so the index must lead with
        // patientId and allow a descending range scan on timestamp.
        Index(value = ["patientId", "timestamp"])
    ]
)
data class VitalsReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val patientId: String,
    val timestamp: Long,
    val heartRate: Int? = null,
    val spo2: Int? = null,
    val bodyTempC: Float? = null,
    val motionMagnitudeG: Float? = null,
    val ambientTempC: Float? = null,
    val ambientHumidityPct: Float? = null,
    val aqi: Int? = null,
    /** SIMULATED | BLE | MANUAL — provenance matters for clinical trust. */
    val source: String,

    // ── Added in version 3 by ALTER TABLE, which appends columns, so declared last ──

    /** Skin temperature, °C. Never core temperature: see [VitalsSample.skinTempC]. */
    val skinTempC: Float? = null,
    /** Mean EMG envelope over the stored interval, 12-bit ADC counts. */
    val emgMean: Int? = null,
    /** Highest EMG envelope value within the stored interval, 12-bit ADC counts. */
    val emgMax: Int? = null
)

@Entity(
    tableName = "anomaly_events",
    indices = [
        Index(value = ["patientId", "createdAt"]),
        Index(value = ["status"]),
        // Supports the per-type cooldown lookup without a table scan.
        Index(value = ["patientId", "eventType", "createdAt"])
    ]
)
data class AnomalyEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val patientId: String,
    /** [AnomalyType.wireName] */
    val eventType: String,
    /** [Severity.wireName] */
    val severity: String,
    val riskHeat: Int,
    val riskRespiratory: Int,
    val riskCardiovascular: Int,
    /** The exact structured snapshot the explanation layer was given. */
    val evidenceJson: String,
    /**
     * Deterministic explanation. NON-NULL by design — it is written before the
     * event is ever surfaced, so the user is warned in understandable language
     * even if the model never runs.
     */
    val templateExplanation: String,
    /**
     * LLM-generated rewrite. Null until inference succeeds; stored alongside the
     * template rather than replacing it so both remain auditable after the fact.
     */
    val aiExplanation: String? = null,
    /** ACTIVE | ACKNOWLEDGED */
    val status: String,
    val createdAt: Long,
    val acknowledgedAt: Long? = null,
    val syncedAt: Long? = null
) {
    /** What the UI renders: the AI rewrite when available, the template otherwise. */
    val displayExplanation: String get() = aiExplanation ?: templateExplanation
}

/** Lifecycle of an [AnomalyEventEntity]. */
enum class EventStatus(val wireName: String) {
    ACTIVE("ACTIVE"),
    ACKNOWLEDGED("ACKNOWLEDGED");

    companion object {
        fun fromWireName(name: String): EventStatus =
            entries.firstOrNull { it.wireName == name } ?: ACTIVE
    }
}

/**
 * Provenance of a stored reading.
 *
 * Named `ReadingSource` rather than `VitalsSource` because the latter is the
 * streaming interface in `health.source`. Provenance is clinically meaningful: a
 * chart built from SIMULATED data must never be mistaken for real measurements.
 */
enum class ReadingSource(val wireName: String) {
    SIMULATED("SIMULATED"),
    BLE("BLE"),
    MANUAL("MANUAL");

    companion object {
        fun fromWireName(name: String): ReadingSource =
            entries.firstOrNull { it.wireName == name } ?: SIMULATED
    }
}

// ── Domain <-> entity mapping ─────────────────────────────────────────────────

fun VitalsReadingEntity.toDomain(): VitalsSample = VitalsSample(
    timestamp          = timestamp,
    heartRate          = heartRate,
    spo2               = spo2,
    bodyTempC          = bodyTempC,
    motionMagnitudeG   = motionMagnitudeG,
    ambientTempC       = ambientTempC,
    ambientHumidityPct = ambientHumidityPct,
    aqi                = aqi,
    skinTempC          = skinTempC,
    emgMean            = emgMean,
    emgMax             = emgMax
)

fun VitalsSample.toEntity(
    patientId: String,
    source: ReadingSource
): VitalsReadingEntity = VitalsReadingEntity(
    patientId          = patientId,
    timestamp          = timestamp,
    heartRate          = heartRate,
    spo2               = spo2,
    bodyTempC          = bodyTempC,
    motionMagnitudeG   = motionMagnitudeG,
    ambientTempC       = ambientTempC,
    ambientHumidityPct = ambientHumidityPct,
    aqi                = aqi,
    source             = source.wireName,
    skinTempC          = skinTempC,
    emgMean            = emgMean,
    emgMax             = emgMax
)

/**
 * Build the persistable event from a confirmed detection.
 *
 * [templateExplanation] is a required parameter, not an optional one: there is no
 * legal path that stores an event without a human-readable explanation already
 * attached.
 */
fun AnomalyEvidence.toEventEntity(
    patientId: String,
    templateExplanation: String
): AnomalyEventEntity = AnomalyEventEntity(
    patientId           = patientId,
    eventType           = type.wireName,
    severity            = severity.wireName,
    riskHeat            = riskScores.heat,
    riskRespiratory     = riskScores.respiratory,
    riskCardiovascular  = riskScores.cardiovascular,
    evidenceJson        = toJson(),
    templateExplanation = templateExplanation,
    aiExplanation       = null,
    status              = EventStatus.ACTIVE.wireName,
    createdAt           = triggeredAt
)

fun AnomalyEventEntity.anomalyType(): AnomalyType? = AnomalyType.fromWireName(eventType)
fun AnomalyEventEntity.severityEnum(): Severity = Severity.fromWireName(severity)
fun AnomalyEventEntity.statusEnum(): EventStatus = EventStatus.fromWireName(status)
fun AnomalyEventEntity.riskScores(): RiskScores =
    RiskScores(riskHeat, riskRespiratory, riskCardiovascular)
