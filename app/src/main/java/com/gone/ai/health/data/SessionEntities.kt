package com.gone.ai.health.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.gone.ai.health.domain.Severity
import com.gone.ai.health.session.RepresentativePointCodec
import com.gone.ai.health.session.SessionReport

/**
 * A monitoring session the user started and stopped on purpose, so it can be reported on.
 *
 * Added in database version 3.
 *
 * Readings are NOT tagged with a session id. A session is a time range over the readings
 * the pipeline already stores, which keeps `vitals_readings` untouched apart from its new
 * channels and means a report can be rebuilt from the same rows the charts use.
 *
 * Persisted rather than held in memory, as REFERENCE did: a session survives the app
 * process being killed in the background, which on a long session is likely.
 */
@Entity(
    tableName = "health_sessions",
    indices = [
        Index(value = ["patientId", "startedAt"]),
        Index(value = ["status"])
    ]
)
data class HealthSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val patientId: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    /** [SessionStatus.wireName] */
    val status: String,
    /** Why a finished session has no report, e.g. too few readings. */
    val note: String? = null
)

enum class SessionStatus(val wireName: String) {
    ACTIVE("ACTIVE"),
    COMPLETED("COMPLETED"),
    CANCELLED("CANCELLED");

    companion object {
        fun fromWireName(name: String): SessionStatus =
            entries.firstOrNull { it.wireName == name } ?: COMPLETED
    }
}

/**
 * The stored form of a [SessionReport].
 *
 * Statistics are real columns rather than one JSON blob so a report stays readable with
 * plain SQL, and because readings older than the retention window are deleted — once
 * that happens this row is the only record of what the session measured.
 */
@Entity(
    tableName = "session_reports",
    indices = [
        Index(value = ["sessionId"], unique = true),
        Index(value = ["patientId", "generatedAt"])
    ]
)
data class SessionReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val patientId: String,
    val generatedAt: Long,
    val startedAt: Long,
    val endedAt: Long,
    val sampleCount: Int,
    /** Comma-separated [ReadingSource.wireName]s present in the session. */
    val sources: String,
    /** [Severity.wireName] of the most severe alert, or null when none was raised. */
    val highestSeverity: String?,
    val eventCount: Int,
    val hrMin: Float?,
    val hrMean: Float?,
    val hrMax: Float?,
    val spo2Min: Float?,
    val spo2Mean: Float?,
    val spo2Max: Float?,
    val bodyTempMin: Float?,
    val bodyTempMax: Float?,
    val skinTempMin: Float?,
    val skinTempMean: Float?,
    val skinTempMax: Float?,
    val motionPeakG: Float?,
    val emgMean: Float?,
    val emgPeak: Int?,
    val gapCount: Int,
    val longestGapMillis: Long,
    /** [RepresentativePointCodec] text. */
    val representativePoints: String,
    /** Deterministic observations, one per line. */
    val observations: String,
    /** Validated model rewrite of [observations]; null until and unless one passes. */
    val aiSummary: String? = null
) {
    val sourceList: List<ReadingSource>
        get() = sources.split(',').filter { it.isNotBlank() }.map { ReadingSource.fromWireName(it) }

    val severityEnum: Severity? get() = highestSeverity?.let { Severity.fromWireName(it) }

    val observationLines: List<String> get() = observations.lines().filter { it.isNotBlank() }
}

fun SessionReport.toEntity(sessionId: Long, patientId: String, generatedAt: Long) = SessionReportEntity(
    sessionId = sessionId,
    patientId = patientId,
    generatedAt = generatedAt,
    startedAt = startedAt,
    endedAt = endedAt,
    sampleCount = sampleCount,
    sources = sources.joinToString(",") { it.wireName },
    highestSeverity = highestSeverity?.wireName,
    eventCount = events.size,
    hrMin = heartRate?.min,
    hrMean = heartRate?.mean,
    hrMax = heartRate?.max,
    spo2Min = spo2?.min,
    spo2Mean = spo2?.mean,
    spo2Max = spo2?.max,
    bodyTempMin = bodyTempC?.min,
    bodyTempMax = bodyTempC?.max,
    skinTempMin = skinTempC?.min,
    skinTempMean = skinTempC?.mean,
    skinTempMax = skinTempC?.max,
    motionPeakG = motionPeakG,
    emgMean = emgMean?.mean,
    emgPeak = emgPeak,
    gapCount = gapCount,
    longestGapMillis = longestGapMillis,
    representativePoints = RepresentativePointCodec.encode(points),
    observations = observations.joinToString("\n")
)
