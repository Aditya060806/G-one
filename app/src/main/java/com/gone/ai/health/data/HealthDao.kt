package com.gone.ai.health.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Projection for [AnomalyDao.lastEventPerType]. */
data class TypeLastSeen(
    val eventType: String,
    val lastAt: Long,
    val severity: String
)

@Dao
interface PatientDao {

    @Upsert
    suspend fun upsert(patient: PatientEntity)

    /** Creates the row only if it does not exist; never overwrites an existing profile. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(patient: PatientEntity): Long

    @Query("SELECT * FROM patients WHERE id = :id")
    suspend fun byId(id: String): PatientEntity?

    @Query("SELECT * FROM patients ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<PatientEntity>>

    @Query("SELECT * FROM patients ORDER BY createdAt ASC LIMIT 1")
    suspend fun primary(): PatientEntity?

    @Query("DELETE FROM patients WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface DeviceDao {

    @Upsert
    suspend fun upsert(device: DeviceEntity)

    @Query("SELECT * FROM devices WHERE patientId = :patientId ORDER BY lastSeenAt DESC")
    fun observeForPatient(patientId: String): Flow<List<DeviceEntity>>

    @Query("UPDATE devices SET lastSeenAt = :seenAt, batteryPercent = :battery WHERE id = :id")
    suspend fun touch(id: String, seenAt: Long, battery: Int?)

    @Query("DELETE FROM devices WHERE patientId = :patientId")
    suspend fun clearForPatient(patientId: String): Int
}

@Dao
interface VitalsDao {

    /** Emergency sharing must never publish simulated/manual measurements as wearable data. */
    @Query("SELECT * FROM vitals_readings WHERE patientId = :patientId AND source = 'BLE' ORDER BY timestamp DESC LIMIT 1")
    suspend fun latestWearable(patientId: String): VitalsReadingEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(reading: VitalsReadingEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(readings: List<VitalsReadingEntity>)

    /**
     * Newest-first window used by the detection engine.
     *
     * Deliberately bounded by [limit] rather than returning everything since a
     * timestamp: an unbounded query would grow without limit over a long monitoring
     * session and the rules only ever need the recent window.
     */
    @Query(
        """
        SELECT * FROM vitals_readings
        WHERE patientId = :patientId AND timestamp >= :since
        ORDER BY timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun windowSince(patientId: String, since: Long, limit: Int): List<VitalsReadingEntity>

    @Query(
        """
        SELECT * FROM vitals_readings
        WHERE patientId = :patientId
        ORDER BY timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun latest(patientId: String, limit: Int): List<VitalsReadingEntity>

    @Query(
        """
        SELECT * FROM vitals_readings
        WHERE patientId = :patientId
        ORDER BY timestamp DESC
        LIMIT :limit
        """
    )
    fun observeLatest(patientId: String, limit: Int): Flow<List<VitalsReadingEntity>>

    @Query("SELECT * FROM vitals_readings WHERE patientId = :patientId ORDER BY timestamp DESC LIMIT 1")
    fun observeMostRecent(patientId: String): Flow<VitalsReadingEntity?>

    @Query("SELECT COUNT(*) FROM vitals_readings WHERE patientId = :patientId")
    suspend fun count(patientId: String): Int

    /**
     * Chronological readings inside a closed time range, for building a session report.
     * Capped: a report summarises, it does not need every row of a very long session.
     */
    @Query(
        """
        SELECT * FROM vitals_readings
        WHERE patientId = :patientId AND timestamp BETWEEN :from AND :to
        ORDER BY timestamp ASC
        LIMIT :limit
        """
    )
    suspend fun between(patientId: String, from: Long, to: Long, limit: Int): List<VitalsReadingEntity>

    /** Backs de-duplication of readings replayed from the wearable's SD card. */
    @Query("SELECT EXISTS(SELECT 1 FROM vitals_readings WHERE patientId = :patientId AND timestamp = :timestamp)")
    suspend fun existsAt(patientId: String, timestamp: Long): Boolean

    /** Retention trim — monitoring at 1 Hz would otherwise grow unbounded. */
    @Query("DELETE FROM vitals_readings WHERE patientId = :patientId AND timestamp < :before")
    suspend fun deleteOlderThan(patientId: String, before: Long): Int

    @Query("DELETE FROM vitals_readings WHERE patientId = :patientId")
    suspend fun clearAll(patientId: String): Int
}

@Dao
interface AnomalyDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: AnomalyEventEntity): Long

    @Query("SELECT * FROM anomaly_events WHERE id = :id")
    suspend fun byId(id: Long): AnomalyEventEntity?

    /** Attach the LLM rewrite once inference completes. Template stays intact. */
    @Query("UPDATE anomaly_events SET aiExplanation = :text WHERE id = :id")
    suspend fun attachAiExplanation(id: Long, text: String)

    @Query(
        """
        UPDATE anomaly_events
        SET status = '${"ACKNOWLEDGED"}', acknowledgedAt = :at
        WHERE id = :id
        """
    )
    suspend fun acknowledge(id: Long, at: Long)

    @Query("SELECT * FROM anomaly_events WHERE patientId = :patientId ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(patientId: String, limit: Int): Flow<List<AnomalyEventEntity>>

    /** Events raised since [since], newest first, for the chat's personal context. */
    @Query("SELECT * FROM anomaly_events WHERE patientId = :patientId AND createdAt >= :since ORDER BY createdAt DESC")
    suspend fun since(patientId: String, since: Long): List<AnomalyEventEntity>

    /** Events raised inside a closed time range, oldest first, for a session report. */
    @Query(
        """
        SELECT * FROM anomaly_events
        WHERE patientId = :patientId AND createdAt BETWEEN :from AND :to
        ORDER BY createdAt ASC
        """
    )
    suspend fun between(patientId: String, from: Long, to: Long): List<AnomalyEventEntity>

    @Query(
        """
        SELECT * FROM anomaly_events
        WHERE patientId = :patientId AND status = 'ACTIVE'
        ORDER BY createdAt DESC
        """
    )
    fun observeActive(patientId: String): Flow<List<AnomalyEventEntity>>

    /**
     * Most recent event of a given type — backs the per-type cooldown that stops
     * a vital hovering at a threshold from generating an alert per sample.
     */
    @Query(
        """
        SELECT * FROM anomaly_events
        WHERE patientId = :patientId AND eventType = :eventType
        ORDER BY createdAt DESC
        LIMIT 1
        """
    )
    suspend fun mostRecentOfType(patientId: String, eventType: String): AnomalyEventEntity?

    /**
     * Latest event per type — time AND severity — in ONE query.
     *
     * The cooldown needs this when monitoring starts. Doing it as twelve separate
     * `mostRecentOfType` calls would mean twelve round trips. The correlated subquery is
     * served by the (patientId, eventType, createdAt) index.
     *
     * Severity is selected from the row that holds the maximum, rather than relying on
     * SQLite's bare-column behaviour with MAX(). Two events of one type with the same
     * timestamp both come back; the repository keeps the more severe.
     */
    @Query(
        """
        SELECT e.eventType AS eventType, e.createdAt AS lastAt, e.severity AS severity
        FROM anomaly_events e
        WHERE e.patientId = :patientId
          AND e.createdAt = (
              SELECT MAX(i.createdAt) FROM anomaly_events i
              WHERE i.patientId = e.patientId AND i.eventType = e.eventType
          )
        """
    )
    suspend fun lastEventPerType(patientId: String): List<TypeLastSeen>

    @Query("SELECT * FROM anomaly_events WHERE syncedAt IS NULL ORDER BY createdAt ASC LIMIT :limit")
    suspend fun unsynced(limit: Int): List<AnomalyEventEntity>

    @Query("UPDATE anomaly_events SET syncedAt = :at WHERE id = :id")
    suspend fun markSynced(id: Long, at: Long)

    @Query("SELECT COUNT(*) FROM anomaly_events WHERE patientId = :patientId AND status = 'ACTIVE'")
    fun observeActiveCount(patientId: String): Flow<Int>

    @Query("DELETE FROM anomaly_events WHERE patientId = :patientId")
    suspend fun clearAll(patientId: String): Int
}

@Dao
interface SessionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: HealthSessionEntity): Long

    @Query("SELECT * FROM health_sessions WHERE id = :id")
    suspend fun byId(id: Long): HealthSessionEntity?

    @Query(
        """
        SELECT * FROM health_sessions
        WHERE patientId = :patientId AND status = 'ACTIVE'
        ORDER BY startedAt DESC
        LIMIT 1
        """
    )
    suspend fun active(patientId: String): HealthSessionEntity?

    @Query(
        """
        SELECT * FROM health_sessions
        WHERE patientId = :patientId AND status = 'ACTIVE'
        ORDER BY startedAt DESC
        LIMIT 1
        """
    )
    fun observeActive(patientId: String): Flow<HealthSessionEntity?>

    @Query("UPDATE health_sessions SET status = :status, endedAt = :endedAt, note = :note WHERE id = :id")
    suspend fun finish(id: Long, status: String, endedAt: Long, note: String?)

    @Query("DELETE FROM health_sessions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM health_sessions WHERE patientId = :patientId")
    suspend fun clearAll(patientId: String): Int
}

@Dao
interface SessionReportDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(report: SessionReportEntity): Long

    @Query("SELECT * FROM session_reports WHERE id = :id")
    fun observe(id: Long): Flow<SessionReportEntity?>

    @Query("SELECT * FROM session_reports WHERE id = :id")
    suspend fun byId(id: Long): SessionReportEntity?

    @Query("SELECT * FROM session_reports WHERE sessionId = :sessionId")
    suspend fun forSession(sessionId: Long): SessionReportEntity?

    @Query("SELECT * FROM session_reports WHERE patientId = :patientId ORDER BY generatedAt DESC")
    fun observeAll(patientId: String): Flow<List<SessionReportEntity>>

    @Query("SELECT * FROM session_reports WHERE patientId = :patientId ORDER BY generatedAt DESC LIMIT :limit")
    suspend fun recent(patientId: String, limit: Int): List<SessionReportEntity>

    @Query("UPDATE session_reports SET aiSummary = :text WHERE id = :id")
    suspend fun attachAiSummary(id: Long, text: String)

    @Query("DELETE FROM session_reports WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM session_reports WHERE patientId = :patientId")
    suspend fun clearAll(patientId: String): Int
}
