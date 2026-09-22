package com.gone.ai.health.data

import android.content.Context
import com.gone.ai.data.library.GoneDatabase
import com.gone.ai.health.domain.AnomalyEvidence
import com.gone.ai.health.domain.AnomalyThresholds
import com.gone.ai.health.domain.AnomalyType
import com.gone.ai.health.domain.PatientBaseline
import com.gone.ai.health.domain.RecentEvent
import com.gone.ai.health.domain.Severity
import com.gone.ai.health.domain.VitalsSample
import kotlinx.coroutines.flow.Flow

/**
 * Persistence API for the health record.
 *
 * Defined as an interface so [com.gone.ai.health.service.MonitoringPipeline] can
 * be unit-tested against an in-memory fake with no Room, no Android, and no emulator.
 * The pipeline is where the ordering guarantees live, so it is the part that most
 * needs to be testable.
 */
interface HealthRepository {

    /** Create the patient row if none exists. Never overwrites an existing profile. */
    suspend fun ensurePatient(patient: PatientEntity)
    suspend fun primaryPatient(): PatientEntity?

    /** Durability point: called for every accepted sample. */
    suspend fun saveReading(patientId: String, sample: VitalsSample, source: ReadingSource): Long

    /**
     * True when a reading with exactly this timestamp is already stored.
     *
     * The wearable replays its SD-card backlog on every reconnect until the replay has
     * been fully sent, so a link that drops mid-replay delivers some readings twice.
     */
    suspend fun hasReadingAt(patientId: String, timestamp: Long): Boolean

    suspend fun recentReadings(patientId: String, since: Long, limit: Int): List<VitalsSample>

    /** @return row id of the stored event. */
    suspend fun saveEvent(
        patientId: String,
        evidence: AnomalyEvidence,
        templateExplanation: String
    ): Long

    suspend fun attachAiExplanation(eventId: Long, text: String)

    /** Most recent stored event per type, with its severity, for the cooldown. */
    suspend fun recentEventsByType(patientId: String): Map<AnomalyType, RecentEvent>

    suspend fun baseline(patientId: String, now: Long, thresholds: AnomalyThresholds): PatientBaseline

    suspend fun acknowledge(eventId: Long, at: Long)

    suspend fun trimReadingsOlderThan(patientId: String, before: Long): Int

    fun observeActiveEvents(patientId: String): Flow<List<AnomalyEventEntity>>
    fun observeRecentEvents(patientId: String, limit: Int): Flow<List<AnomalyEventEntity>>
    fun observeLatestReading(patientId: String): Flow<VitalsReadingEntity?>
    fun observeRecentReadings(patientId: String, limit: Int): Flow<List<VitalsReadingEntity>>
}

/** Room-backed implementation. */
class RoomHealthRepository(
    private val patientDao: PatientDao,
    private val vitalsDao: VitalsDao,
    private val anomalyDao: AnomalyDao,
    private val deviceDao: DeviceDao
) : HealthRepository {

    /**
     * Insert-if-absent, not upsert. The monitoring service calls this on every start, and
     * an upsert overwrote whatever profile already existed — including the one written by
     * onboarding — with placeholder values.
     */
    override suspend fun ensurePatient(patient: PatientEntity) {
        patientDao.insertIfAbsent(patient)
    }

    override suspend fun primaryPatient(): PatientEntity? = patientDao.primary()

    override suspend fun saveReading(
        patientId: String,
        sample: VitalsSample,
        source: ReadingSource
    ): Long = vitalsDao.insert(sample.toEntity(patientId, source))

    override suspend fun hasReadingAt(patientId: String, timestamp: Long): Boolean =
        vitalsDao.existsAt(patientId, timestamp)

    override suspend fun recentReadings(
        patientId: String,
        since: Long,
        limit: Int
    ): List<VitalsSample> =
        vitalsDao.windowSince(patientId, since, limit).map { it.toDomain() }

    override suspend fun saveEvent(
        patientId: String,
        evidence: AnomalyEvidence,
        templateExplanation: String
    ): Long = anomalyDao.insert(evidence.toEventEntity(patientId, templateExplanation))

    override suspend fun attachAiExplanation(eventId: Long, text: String) =
        anomalyDao.attachAiExplanation(eventId, text)

    override suspend fun recentEventsByType(patientId: String): Map<AnomalyType, RecentEvent> =
        anomalyDao.lastEventPerType(patientId)
            .mapNotNull { row ->
                AnomalyType.fromWireName(row.eventType)?.let { type ->
                    type to RecentEvent(row.lastAt, Severity.fromWireName(row.severity))
                }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, events) -> events.maxBy { it.severity.rank } }

    override suspend fun baseline(
        patientId: String,
        now: Long,
        thresholds: AnomalyThresholds
    ): PatientBaseline {
        // A day of history, capped. The cap matters: at 1 Hz a full day is 86,400 rows,
        // and loading all of them to average three numbers would stall the pipeline.
        // Baseline is a slow-moving statistic, so a bounded recent sample is enough.
        val since = now - BASELINE_WINDOW_MILLIS
        val samples = vitalsDao.windowSince(patientId, since, BASELINE_SAMPLE_CAP)
            .map { it.toDomain() }
        return PatientBaseline.fromSamples(samples)
    }

    override suspend fun acknowledge(eventId: Long, at: Long) = anomalyDao.acknowledge(eventId, at)

    override suspend fun trimReadingsOlderThan(patientId: String, before: Long): Int =
        vitalsDao.deleteOlderThan(patientId, before)

    override fun observeActiveEvents(patientId: String) = anomalyDao.observeActive(patientId)

    override fun observeRecentEvents(patientId: String, limit: Int) =
        anomalyDao.observeRecent(patientId, limit)

    override fun observeLatestReading(patientId: String) = vitalsDao.observeMostRecent(patientId)

    override fun observeRecentReadings(patientId: String, limit: Int) =
        vitalsDao.observeLatest(patientId, limit)

    /**
     * Records a wearable as seen now. The MAC address is the row id, so one device stays one
     * row however often it reconnects.
     */
    suspend fun rememberDevice(patientId: String, address: String, name: String, at: Long) =
        deviceDao.upsert(
            DeviceEntity(
                id = address,
                patientId = patientId,
                displayName = name,
                transport = ReadingSource.BLE.wireName,
                lastSeenAt = at
            )
        )

    companion object {
        const val BASELINE_WINDOW_MILLIS = 24L * 60 * 60 * 1000
        const val BASELINE_SAMPLE_CAP = 3_000

        fun from(context: Context): RoomHealthRepository {
            val db = GoneDatabase.getInstance(context)
            return RoomHealthRepository(
                patientDao = db.patientDao(),
                vitalsDao  = db.vitalsDao(),
                anomalyDao = db.anomalyDao(),
                deviceDao  = db.deviceDao()
            )
        }
    }
}
