package com.gone.ai.health.service

import com.gone.ai.health.data.AnomalyEventEntity
import com.gone.ai.health.data.HealthRepository
import com.gone.ai.health.data.ReadingSource
import com.gone.ai.health.data.toEventEntity
import com.gone.ai.health.detect.AnomalyDetector
import com.gone.ai.health.domain.AnomalyEvidence
import com.gone.ai.health.domain.AnomalyType
import com.gone.ai.health.domain.PatientBaseline
import com.gone.ai.health.domain.RecentEvent
import com.gone.ai.health.domain.RiskScores
import com.gone.ai.health.domain.Severity
import com.gone.ai.health.domain.VitalsSample
import com.gone.ai.health.explain.Explanation
import com.gone.ai.health.explain.ExplanationTemplates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Receives an anomaly that has been detected, persisted, and explained.
 *
 * An interface so the pipeline can be tested without Android notifications. In
 * production [com.gone.ai.health.service.NotificationAlertSink] posts a
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
    val baselineReady: Boolean = false,
    /** True while the model is rewriting an alert's wording in the background. */
    val aiExplaining: Boolean = false,
    /** Past readings stored this session: SD-card backlog or late deliveries. */
    val historicalSamples: Long = 0,
    /** Past readings skipped because an identical one was already stored. */
    val duplicatesSkipped: Long = 0
)

/**
 * Coarse pipeline state, kept separate from the AI engine's own state so the UI can
 * distinguish "the app is broken" from "you have a health emergency".
 *
 * Model activity is not a state here: it runs alongside sampling rather than instead of
 * it, so it is reported by [MonitoringSnapshot.aiExplaining].
 */
enum class MonitoringState { IDLE, MONITORING, READING, ANALYZING, ANOMALY_DETECTED, ERROR }

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
 *   5. UPGRADE with the LLM, best-effort, in [runExplanationWorker] — NOT inside
 *      [onSample]. If it stalls, times out, produces something that fails validation,
 *      or never runs at all, nothing is lost.
 *
 * Step 5 used to run inline. The first alert did not wait for the model, but because
 * samples are processed one at a time, every sample after it did: detection was blind
 * for up to the 45-second explanation timeout after each alert. Now the sample path only
 * enqueues the rewrite and returns.
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

    /** An alert waiting for its best-effort rewrite. */
    private data class PendingExplanation(
        val eventId: Long,
        val evidence: AnomalyEvidence,
        val template: Explanation
    )

    /**
     * Rewrites waiting for the model. Bounded, dropping the OLDEST when full: a burst of
     * alerts must not queue minutes of inference, and a skipped rewrite only means the
     * deterministic text — which the user already has — stays.
     */
    private val explanationQueue = Channel<PendingExplanation>(
        capacity = EXPLANATION_QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * Rolling in-memory window.
     *
     * Detection needs the recent history on every sample. Re-reading it from Room
     * each time would be ~1,800 rows per second at 1 Hz with a 30-minute window —
     * enough to keep the disk and CPU permanently busy for no benefit. Samples are
     * already being written for durability, so the pipeline keeps its own trimmed
     * copy in memory and the database is only consulted for slow-moving state.
     *
     * Touched only by [onSample], which the caller invokes sequentially.
     */
    private val window = ArrayDeque<VitalsSample>()

    private var cachedBaseline: PatientBaseline = PatientBaseline.EMPTY
    private var baselineComputedAt: Long = 0
    private var cooldowns: MutableMap<AnomalyType, RecentEvent> = mutableMapOf()
    private var cooldownsLoaded = false

    private var samplesProcessed = 0L
    private var eventsRaised = 0L

    /**
     * Window and cooldowns for past readings, kept apart from the live ones.
     *
     * A replayed backlog runs on its own timeline. Mixed into the live window, three-hour-old
     * readings would be trimmed the instant they arrived, or — worse — sit beside live
     * readings and bend every trend. Separate cooldowns stop an old event from muting a
     * live alert of the same type, and vice versa.
     */
    private val historyWindow = ArrayDeque<VitalsSample>()
    private var historyCooldowns: MutableMap<AnomalyType, RecentEvent> = mutableMapOf()
    private var historicalSamples = 0L
    private var duplicatesSkipped = 0L

    /**
     * Whether the last [onSample] left its reading in the database, either written now or
     * already there. A wearable record is acknowledged, and deleted from the wearable, only
     * when this is true.
     */
    var lastReadingStored: Boolean = false
        private set

    /**
     * Reset per-session state. Call when a new streaming session starts.
     *
     * Queued rewrites are kept: they belong to events already stored, and are still valid.
     */
    fun reset() {
        window.clear()
        historyWindow.clear()
        cachedBaseline = PatientBaseline.EMPTY
        baselineComputedAt = 0
        cooldowns = mutableMapOf()
        historyCooldowns = mutableMapOf()
        cooldownsLoaded = false
        samplesProcessed = 0
        eventsRaised = 0
        historicalSamples = 0
        duplicatesSkipped = 0
        _snapshot.value = MonitoringSnapshot()
    }

    /**
     * Process one sample through steps 1–4 of the spine and queue step 5.
     *
     * Never waits for the model.
     *
     * @param replayed true for a reading the wearable buffered while out of range. A
     *   sample older than [HISTORICAL_AGE_MILLIS] is treated the same way whether or not it
     *   was flagged — see [onHistoricalSample].
     * @return the events raised by this sample; empty is the normal case.
     */
    suspend fun onSample(
        patientId: String,
        sample: VitalsSample,
        source: ReadingSource = ReadingSource.SIMULATED,
        replayed: Boolean = false
    ): List<RaisedEvent> {
        val now = clock()
        if (replayed || now - sample.timestamp > HISTORICAL_AGE_MILLIS) {
            return onHistoricalSample(patientId, sample, source, now)
        }

        // ── 1. Durability first ───────────────────────────────────────────────
        _snapshot.update { it.copy(state = MonitoringState.READING, latest = sample) }
        lastReadingStored = runCatching { repository.saveReading(patientId, sample, source) }
            .onFailure {
                // A failed write must not stop monitoring. Detection can still run on
                // the in-memory window, so the user stays protected even if storage
                // is momentarily unavailable.
                _snapshot.update { it.copy(state = MonitoringState.ERROR) }
            }
            .isSuccess

        // ── 2. Maintain the window and detect ─────────────────────────────────
        window.addLast(sample)
        trimWindow(now)

        if (!cooldownsLoaded) {
            cooldowns = runCatching { repository.recentEventsByType(patientId).toMutableMap() }
                .getOrElse { mutableMapOf() }
            cooldownsLoaded = true
        }
        refreshBaselineIfStale(patientId, now)

        _snapshot.update { it.copy(state = MonitoringState.ANALYZING) }
        val result = detector.evaluate(
            samples = window.toList(),
            baseline = cachedBaseline,
            now = now,
            recentEvents = cooldowns
        )

        samplesProcessed++
        _snapshot.update {
            it.copy(
                risk = result.riskScores,
                samplesProcessed = samplesProcessed,
                baselineReady = cachedBaseline.isReliable(detector.thresholds),
                state = if (result.hasAnomaly) MonitoringState.ANOMALY_DETECTED else MonitoringState.MONITORING
            )
        }

        if (!result.hasAnomaly) return emptyList()

        // ── 3-4. Persist, then alert ──────────────────────────────────────────
        val raised = mutableListOf<RaisedEvent>()
        for (candidate in result.candidates) {
            val template = ExplanationTemplates.render(candidate.evidence)

            val eventId = runCatching {
                repository.saveEvent(patientId, candidate.evidence, template.full)
            }.getOrNull()

            // Record the cooldown even if persistence failed, so a storage problem
            // cannot turn into an alert storm. Severity is recorded too, so a later,
            // worse event of the same type still gets through.
            cooldowns[candidate.type] = RecentEvent(now, candidate.severity)
            eventsRaised++

            if (eventId == null) continue

            val entity = candidate.evidence.toEventEntity(patientId, template.full).copy(id = eventId)

            // The user is warned HERE, with deterministic text, before any inference.
            runCatching { alerts.onAnomaly(eventId, entity, template) }

            raised += RaisedEvent(eventId, candidate.type, candidate.severity, template)

            // ── 5. Queue the best-effort rewrite; never wait for it here ──────
            if (explainer != null) {
                explanationQueue.trySend(PendingExplanation(eventId, candidate.evidence, template))
            }
        }

        _snapshot.update { it.copy(eventsRaised = eventsRaised, lastEventAt = now) }
        return raised
    }

    /**
     * Step 5: rewrite queued alerts with the model, one at a time, until cancelled.
     *
     * Run in its own coroutine alongside the sampling loop. Returns immediately when the
     * pipeline has no explainer.
     */
    suspend fun runExplanationWorker() {
        val ex = explainer ?: return
        for (pending in explanationQueue) {
            _snapshot.update { it.copy(aiExplaining = true) }
            try {
                val improved = try {
                    ex.explain(pending.evidence, pending.template)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (!improved.isNullOrBlank()) {
                    val merged = "$improved\n\n${pending.template.tier.guidance}"
                    runCatching { repository.attachAiExplanation(pending.eventId, merged) }
                    runCatching { alerts.onExplanationUpgraded(pending.eventId, merged) }
                }
            } finally {
                _snapshot.update { it.copy(aiExplaining = false) }
            }
        }
    }

    /**
     * A reading from the past: replayed from the wearable's SD card, or delivered late.
     *
     * It is stored and run through the same rules — a fall that happened while the phone
     * was out of range is still a fall, and belongs in the record — but judged against its
     * OWN timeline, and NOBODY IS NOTIFIED. A push reading "a fall may have happened" about
     * something three hours old is read as happening now. Findings are stored with their
     * real time and appear in Trails; the live alert path is untouched.
     *
     * The wearable replays until a backlog has been fully sent, so a link that drops
     * mid-replay delivers some readings twice. A reading whose timestamp is already stored
     * is skipped, which the aggregator's time-aligned buckets make reliable.
     */
    private suspend fun onHistoricalSample(
        patientId: String,
        sample: VitalsSample,
        source: ReadingSource,
        now: Long
    ): List<RaisedEvent> {
        val alreadyStored = runCatching { repository.hasReadingAt(patientId, sample.timestamp) }
            .getOrDefault(false)
        if (alreadyStored) {
            lastReadingStored = true
            duplicatesSkipped++
            _snapshot.update { it.copy(duplicatesSkipped = duplicatesSkipped) }
            return emptyList()
        }

        lastReadingStored = runCatching { repository.saveReading(patientId, sample, source) }
            .onFailure { _snapshot.update { it.copy(state = MonitoringState.ERROR) } }
            .isSuccess

        historyWindow.addLast(sample)
        val horizon = sample.timestamp - detector.thresholds.trendWindowMinutes * 60_000L
        historyWindow.removeAll { it.timestamp < horizon }
        while (historyWindow.size > MAX_WINDOW_SAMPLES) historyWindow.removeFirst()

        refreshBaselineIfStale(patientId, now)
        val result = detector.evaluate(
            samples = historyWindow.toList(),
            baseline = cachedBaseline,
            now = sample.timestamp,
            recentEvents = historyCooldowns
        )

        historicalSamples++
        _snapshot.update { it.copy(historicalSamples = historicalSamples) }
        if (!result.hasAnomaly) return emptyList()

        val raised = mutableListOf<RaisedEvent>()
        for (candidate in result.candidates) {
            val template = ExplanationTemplates.render(candidate.evidence)
            val eventId = runCatching {
                repository.saveEvent(patientId, candidate.evidence, template.full)
            }.getOrNull()
            historyCooldowns[candidate.type] = RecentEvent(sample.timestamp, candidate.severity)
            if (eventId == null) continue
            raised += RaisedEvent(eventId, candidate.type, candidate.severity, template, historical = true)
        }
        eventsRaised += raised.size
        _snapshot.update { it.copy(eventsRaised = eventsRaised) }
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
        const val EXPLANATION_QUEUE_CAPACITY = 8

        /**
         * A sample older than this when it reaches the pipeline is history, not news: it
         * is stored and checked but never pushed as an alert. Ten minutes comfortably
         * covers clock skew and a slow link while still catching a replayed backlog.
         */
        const val HISTORICAL_AGE_MILLIS = 10L * 60 * 1000
    }
}

/** An event this pipeline actually raised. */
data class RaisedEvent(
    val id: Long,
    val type: AnomalyType,
    val severity: Severity,
    val explanation: Explanation,
    /** Found in past readings: stored, but nobody was notified. */
    val historical: Boolean = false
)
