package com.infinity.ai.health.service

import com.infinity.ai.health.data.AnomalyEventEntity
import com.infinity.ai.health.data.HealthRepository
import com.infinity.ai.health.data.ReadingSource
import com.infinity.ai.health.detect.AnomalyDetector
import com.infinity.ai.health.domain.AnomalyEvidence
import com.infinity.ai.health.domain.AnomalyType
import com.infinity.ai.health.domain.PatientBaseline
import com.infinity.ai.health.domain.RiskScores
import com.infinity.ai.health.domain.Severity
import com.infinity.ai.health.domain.VitalsSample
import com.infinity.ai.health.explain.Explanation
import com.infinity.ai.health.explain.ExplanationTemplates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Receives an anomaly that has been detected, persisted, and explained.
 *
 * An interface so the pipeline can be tested without Android notifications. In
 * production [com.infinity.ai.health.service.NotificationAlertSink] posts a
 * notification; in tests a fake records calls.
 */
interface AlertSink {
    suspend fun onAnomaly(eventId: Long, event: AnomalyEventEntity, explanation: Explanation)
    /** Called if the model later produces a better-worded version of the same alert. */
    suspend fun onExplanationUpgraded(eventId: Long, text: String)
}

/**
 * Optional LLM rewrite of a deterministic explanation.
 *
 * Nullable everywhere it is used. The pipeline must work end-to-end with no model at
 * all, which is what keeps the model off the critical path.
 */
interface AiExplainer {
    /** @return validated replacement text, or null to keep the template. */
    suspend fun explain(evidence: AnomalyEvidence, template: Explanation): String?
}

/** Live snapshot for the dashboard. */
data class MonitoringSnapshot(
    val state: MonitoringState = MonitoringState.IDLE,
    val latest: VitalsSample? = null,
    val risk: RiskScores = RiskScores.ZERO,
    val samplesProcessed: Long = 0,
    val eventsRaised: Long = 0,
    val lastEventAt: Long? = null,
    val baselineReady: Boolean = false
)

/**
 * Coarse pipeline state, kept separate from the AI engine's own state so the UI can
 * distinguish "the app is broken" from "you have a health emergency".
 */
enum class MonitoringState { IDLE, MONITORING, READING, ANALYZING, ANOMALY_DETECTED, AI_EXPLAINING, ERROR }

/**
 * The ordered spine of G-one.
 *
 * ORDERING IS THE ENTIRE POINT OF THIS CLASS, AND IT IS NOT ARBITRARY:
 *
 *   1. PERSIST the reading first. Nothing downstream can lose data that is already
 *      on disk, even if detection later throws or the process is killed.
 *   2. DETECT deterministically. No model involved, no network, no I/O.
 *   3. PERSIST the event WITH its template explanation. The alert is complete and
 *      readable at this instant.
 *   4. ALERT immediately, using the template. The user is warned now, not after
 *      inference finishes — which on a mid-range phone could be 30+ seconds away.
 *   5. UPGRADE with the LLM, best-effort. If it stalls, times out, produces
 *      something that fails validation, or never runs at all, nothing is lost.
 *
 * Steps 1–4 have no dependency on the network or the model. That is what satisfies
 * "operate effectively with intermittent or no internet connectivity" and
 * "recognize health risks before they become emergencies" simultaneously.
 *
 * Android-free on purpose: this class is where the guarantees live, so it is the part
 * that must be unit-testable.
 */
class MonitoringPipeline(
    private val repository: HealthRepository,
    private val detector: AnomalyDetector,
    private val alerts: AlertSink,
    private val explainer: AiExplainer? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Recompute the patient baseline no more often than this. */
    private val baselineRefreshMillis: Long = DEFAULT_BASELINE_REFRESH_MILLIS
) {

    private val _snapshot = MutableStateFlow(MonitoringSnapshot())
    val snapshot: StateFlow<MonitoringSnapshot> = _snapshot.asStateFlow()

    /**
     * Rolling in-memory window.
     *
     * Detection needs the recent history on every sample. Re-reading it from Room
     * each time would be ~1,800 rows per second at 1 Hz with a 30-minute window —
     * enough to keep the disk and CPU permanently busy for no benefit. Samples are
     * already being written for durability, so the pipeline keeps its own trimmed
     * copy in memory and the database is only consulted for slow-moving state.
     */
    private val window = ArrayDeque<VitalsSample>()

    private var cachedBaseline: PatientBaseline = PatientBaseline.EMPTY
    private var baselineComputedAt: Long = 0
    private var cooldowns: MutableMap<AnomalyType, Long> = mutableMapOf()
    private var cooldownsLoaded = false

    private var samplesProcessed = 0L
    private var eventsRaised = 0L

    /** Reset per-session state. Call when a new streaming session starts. */
    fun reset() {
        window.clear()
        cachedBaseline = PatientBaseline.EMPTY
        baselineComputedAt = 0
        cooldowns = mutableMapOf()
        cooldownsLoaded = false
        samplesProcessed = 0
        eventsRaised = 0
        _snapshot.value = MonitoringSnapshot()
    }

    /**
     * Process one sample through the whole spine.
     *
     * @return the events raised by this sample; empty is the normal case.
     */
    suspend fun onSample(
        patientId: String,
        sample: VitalsSample,
        source: ReadingSource = ReadingSource.SIMULATED
    ): List<RaisedEvent> {
        val now = clock()

        // ── 1. Durability first ───────────────────────────────────────────────
        _snapshot.value = _snapshot.value.copy(state = MonitoringState.READING, latest = sample)
        runCatching { repository.saveReading(patientId, sample, source) }
            .onFailure {
                // A failed write must not stop monitoring. Detection can still run on
                // the in-memory window, so the user stays protected even if storage
                // is momentarily unavailable.
                _snapshot.value = _snapshot.value.copy(state = MonitoringState.ERROR)
            }

        // ── 2. Maintain the window and detect ─────────────────────────────────
        window.addLast(sample)
        trimWindow(now)

        if (!cooldownsLoaded) {
            cooldowns = runCatching { repository.lastEventAtByType(patientId).toMutableMap() }
                .getOrElse { mutableMapOf() }
            cooldownsLoaded = true
        }
        refreshBaselineIfStale(patientId, now)

        _snapshot.value = _snapshot.value.copy(state = MonitoringState.ANALYZING)
        val result = detector.evaluate(
            samples = window.toList(),
            baseline = cachedBaseline,
            now = now,
            lastEventAtByType = cooldowns
        )

        samplesProcessed++
        _snapshot.value = _snapshot.value.copy(
            risk = result.riskScores,
            samplesProcessed = samplesProcessed,
            baselineReady = cachedBaseline.isReliable(detector.thresholds),
            state = if (result.hasAnomaly) MonitoringState.ANOMALY_DETECTED else MonitoringState.MONITORING
        )

        if (!result.hasAnomaly) return emptyList()

        // ── 3-5. Persist, alert, then try to improve the wording ──────────────
        val raised = mutableListOf<RaisedEvent>()
        for (candidate in result.candidates) {
            val template = ExplanationTemplates.render(candidate.evidence)

            val eventId = runCatching {
                repository.saveEvent(patientId, candidate.evidence, template.full)
            }.getOrNull()

            // Record the cooldown even if persistence failed, so a storage problem
            // cannot turn into an alert storm.
            cooldowns[candidate.type] = now
            eventsRaised++

            if (eventId == null) continue

            val entity = candidate.evidence.let {
                com.infinity.ai.health.data.AnomalyEventEntity(
                    id                  = eventId,
                    patientId           = patientId,
                    eventType           = it.type.wireName,
                    severity            = it.severity.wireName,
                    riskHeat            = it.riskScores.heat,
                    riskRespiratory     = it.riskScores.respiratory,
                    riskCardiovascular  = it.riskScores.cardiovascular,
                    evidenceJson        = it.toJson(),
                    templateExplanation = template.full,
                    aiExplanation       = null,
                    status              = com.infinity.ai.health.data.EventStatus.ACTIVE.wireName,
                    createdAt           = it.triggeredAt
                )
            }

            // The user is warned HERE, with deterministic text, before any inference.
            runCatching { alerts.onAnomaly(eventId, entity, template) }

            raised += RaisedEvent(eventId, candidate.type, candidate.severity, template)
        }

        _snapshot.value = _snapshot.value.copy(
            eventsRaised = eventsRaised,
            lastEventAt = now
        )

        // ── 5. Best-effort model upgrade ──────────────────────────────────────
        val ex = explainer
        if (ex != null) {
            for ((event, candidate) in raised.zip(result.candidates)) {
                _snapshot.value = _snapshot.value.copy(state = MonitoringState.AI_EXPLAINING)
                val improved = runCatching {
                    ex.explain(candidate.evidence, event.explanation)
                }.getOrNull()
                if (!improved.isNullOrBlank()) {
                    val merged = "$improved\n\n${event.explanation.tier.guidance}"
                    runCatching { repository.attachAiExplanation(event.id, merged) }
                    runCatching { alerts.onExplanationUpgraded(event.id, merged) }
                }
            }
            _snapshot.value = _snapshot.value.copy(state = MonitoringState.MONITORING)
        }

        return raised
    }

    /** Drop samples older than the trend window so memory stays bounded. */
    private fun trimWindow(now: Long) {
        val horizon = now - detector.thresholds.trendWindowMinutes * 60_000L
        while (window.isNotEmpty() && window.first().timestamp < horizon) {
            window.removeFirst()
        }
        // Hard cap as well: a replayed SD-card backlog can carry thousands of samples
        // with timestamps inside the horizon all at once.
        while (window.size > MAX_WINDOW_SAMPLES) window.removeFirst()
    }

    private suspend fun refreshBaselineIfStale(patientId: String, now: Long) {
        if (now - baselineComputedAt < baselineRefreshMillis && baselineComputedAt != 0L) return
        cachedBaseline = runCatching {
            repository.baseline(patientId, now, detector.thresholds)
        }.getOrElse { cachedBaseline }
        baselineComputedAt = now
    }

    companion object {
        const val DEFAULT_BASELINE_REFRESH_MILLIS = 5L * 60 * 1000
        const val MAX_WINDOW_SAMPLES = 4_000
    }
}

/** An event this pipeline actually raised. */
data class RaisedEvent(
    val id: Long,
    val type: AnomalyType,
    val severity: Severity,
    val explanation: Explanation
)
