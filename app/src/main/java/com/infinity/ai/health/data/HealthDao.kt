package com.infinity.ai.health.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Projection for [AnomalyDao.lastEventPerType]. */
data class TypeLastSeen(
    val eventType: String,
    val lastAt: Long
)

@Dao
interface PatientDao {

    @Upsert
    suspend fun upsert(patient: PatientEntity)

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
     * Latest event time per type, in ONE query.
     *
     * The cooldown check needs this on every evaluation. Doing it as twelve separate
     * `mostRecentOfType` calls would mean twelve round trips per sample — at 1 Hz
     * that is 720 queries a minute for pure bookkeeping. GROUP BY is served directly
     * by the (patientId, eventType, createdAt) index.
     */
    @Query(
        """
        SELECT eventType AS eventType, MAX(createdAt) AS lastAt
        FROM anomaly_events
        WHERE patientId = :patientId
        GROUP BY eventType
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
