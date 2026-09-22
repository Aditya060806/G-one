package com.gone.ai.health.data

import android.content.Context
import androidx.room.withTransaction
import com.gone.ai.data.library.GoneDatabase
import com.gone.ai.health.domain.AnomalyThresholds
import com.gone.ai.health.domain.AnomalyType
import com.gone.ai.health.session.SessionEvent
import com.gone.ai.health.session.SessionReading
import com.gone.ai.health.session.SessionReportBuilder
import kotlinx.coroutines.flow.Flow

/**
 * Monitoring sessions and their reports.
 *
 * Kept apart from [HealthRepository] on purpose: the monitoring pipeline never touches
 * sessions, so its test fake does not have to grow with them.
 */
class SessionRepository(private val db: GoneDatabase) {

    private val sessions = db.sessionDao()
    private val reports = db.sessionReportDao()

    /** Outcome of ending a session. */
    sealed interface FinishResult {
        data class Reported(val reportId: Long) : FinishResult
        /** Ended cleanly but with too little data to summarise. [reason] is shown as-is. */
        data class NoReport(val reason: String) : FinishResult
        data object NotActive : FinishResult
    }

    fun observeActive(patientId: String): Flow<HealthSessionEntity?> = sessions.observeActive(patientId)

    fun observeReports(patientId: String): Flow<List<SessionReportEntity>> = reports.observeAll(patientId)

    fun observeReport(reportId: Long): Flow<SessionReportEntity?> = reports.observe(reportId)

    /** Starts a session, or returns the one already running — never two at once. */
    suspend fun start(patientId: String, now: Long): Long = db.withTransaction {
        sessions.active(patientId)?.id
            ?: sessions.insert(
                HealthSessionEntity(
                    patientId = patientId,
                    startedAt = now,
                    status = SessionStatus.ACTIVE.wireName
                )
            )
    }

    /** Ends the active session without a report. Its readings stay in the health record. */
    suspend fun cancel(patientId: String, now: Long) {
        val active = sessions.active(patientId) ?: return
        sessions.finish(active.id, SessionStatus.CANCELLED.wireName, now, note = "Cancelled")
    }

    /**
     * Ends the active session and stores its report.
     *
     * The readings and events are read and the report written in one transaction, so a
     * retention trim running at the same moment cannot delete readings between the two.
     */
    suspend fun finish(
        patientId: String,
        now: Long,
        thresholds: AnomalyThresholds = AnomalyThresholds.DEFAULT
    ): FinishResult = db.withTransaction {
        val active = sessions.active(patientId) ?: return@withTransaction FinishResult.NotActive
        val end = maxOf(now, active.startedAt)

        val readings = db.vitalsDao()
            .between(patientId, active.startedAt, end, MAX_REPORT_READINGS)
            .map { SessionReading(it.toDomain(), ReadingSource.fromWireName(it.source)) }
        val events = db.anomalyDao().between(patientId, active.startedAt, end).map {
            val type = AnomalyType.fromWireName(it.eventType)
            SessionEvent(
                type = type,
                label = type?.label ?: it.eventType,
                severity = it.severityEnum(),
                at = it.createdAt
            )
        }

        // Past the cap the report covers what was loaded and says so through its own
        // duration, rather than reporting the unloaded remainder as a gap in the record.
        val reportEnd = if (readings.size >= MAX_REPORT_READINGS) readings.last().sample.timestamp else end
        val report = SessionReportBuilder.build(active.startedAt, reportEnd, readings, events, thresholds)
        if (report == null) {
            val reason = "Only ${readings.size} reading${if (readings.size == 1) "" else "s"} " +
                "were recorded. A report needs at least ${SessionReportBuilder.MIN_SAMPLES}."
            sessions.finish(active.id, SessionStatus.COMPLETED.wireName, end, note = reason)
            return@withTransaction FinishResult.NoReport(reason)
        }

        val reportId = reports.insert(report.toEntity(active.id, patientId, generatedAt = now))
        sessions.finish(active.id, SessionStatus.COMPLETED.wireName, end, note = null)
        FinishResult.Reported(reportId)
    }

    suspend fun attachAiSummary(reportId: Long, text: String) = reports.attachAiSummary(reportId, text)

    suspend fun reportById(reportId: Long): SessionReportEntity? = reports.byId(reportId)

    /**
     * The alerts and readings inside a report's time range, for its PDF: the same rows the
     * report was built from, as long as retention has not trimmed them yet.
     */
    suspend fun pdfSources(report: SessionReportEntity): Pair<List<AnomalyEventEntity>, List<VitalsReadingEntity>> =
        db.withTransaction {
            db.anomalyDao().between(report.patientId, report.startedAt, report.endedAt) to
                db.vitalsDao().between(report.patientId, report.startedAt, report.endedAt, MAX_REPORT_READINGS)
        }

    /** Deletes a report and its session. The readings themselves are not touched. */
    suspend fun deleteReport(reportId: Long) = db.withTransaction {
        val report = reports.byId(reportId) ?: return@withTransaction
        reports.delete(reportId)
        sessions.delete(report.sessionId)
    }

    companion object {
        /** About 28 hours at the 5-second cadence; a report summarises, it need not load more. */
        const val MAX_REPORT_READINGS = 20_000

        fun from(context: Context) = SessionRepository(GoneDatabase.getInstance(context))
    }
}
