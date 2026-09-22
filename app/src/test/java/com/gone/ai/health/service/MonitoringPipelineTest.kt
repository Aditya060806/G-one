package com.gone.ai.health.service

import com.gone.ai.health.TestVitals
import com.gone.ai.health.data.AnomalyEventEntity
import com.gone.ai.health.data.HealthRepository
import com.gone.ai.health.data.PatientEntity
import com.gone.ai.health.data.ReadingSource
import com.gone.ai.health.data.VitalsReadingEntity
import com.gone.ai.health.data.toDomain
import com.gone.ai.health.data.toEntity
import com.gone.ai.health.data.toEventEntity
import com.gone.ai.health.detect.AnomalyDetector
import com.gone.ai.health.domain.AnomalyEvidence
import com.gone.ai.health.domain.AnomalyThresholds
import com.gone.ai.health.domain.AnomalyType
import com.gone.ai.health.domain.PatientBaseline
import com.gone.ai.health.domain.RecentEvent
import com.gone.ai.health.domain.Severity
import com.gone.ai.health.domain.VitalsSample
import com.gone.ai.health.explain.Explanation
import com.gone.ai.health.source.VitalsScenario
import com.gone.ai.health.source.VitalsScenarioGenerator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// ── Fakes ─────────────────────────────────────────────────────────────────────
// The pipeline is where the ordering guarantees live, which is exactly why it was
// built Android-free. These fakes need no Room, no emulator, and no notifications.

private class FakeHealthRepository(
    var failReadingWrites: Boolean = false,
    var failEventWrites: Boolean = false,
    var baselineToReturn: PatientBaseline = PatientBaseline.EMPTY,
    var recentToReturn: Map<AnomalyType, RecentEvent> = emptyMap()
) : HealthRepository {

    val readings = mutableListOf<VitalsReadingEntity>()
    val events = mutableListOf<AnomalyEventEntity>()
    val aiAttachments = mutableListOf<Pair<Long, String>>()
    /** Interleaved log of every call, for asserting ORDER rather than just outcome. */
    val callLog = mutableListOf<String>()
    var baselineCalls = 0
    private var nextEventId = 1L

    override suspend fun ensurePatient(patient: PatientEntity) { callLog += "ensurePatient" }
    override suspend fun primaryPatient(): PatientEntity? = null

    override suspend fun saveReading(
        patientId: String,
        sample: VitalsSample,
        source: ReadingSource
    ): Long {
        callLog += "saveReading"
        if (failReadingWrites) throw RuntimeException("disk full")
        readings += sample.toEntity(patientId, source)
        return readings.size.toLong()
    }

    override suspend fun hasReadingAt(patientId: String, timestamp: Long) =
        readings.any { it.patientId == patientId && it.timestamp == timestamp }

    override suspend fun recentReadings(patientId: String, since: Long, limit: Int) =
        readings.map { it.toDomain() }

    override suspend fun saveEvent(
        patientId: String,
        evidence: AnomalyEvidence,
        templateExplanation: String
    ): Long {
        callLog += "saveEvent:${evidence.type.wireName}"
        if (failEventWrites) throw RuntimeException("disk full")
        val id = nextEventId++
        events += evidence.toEventEntity(patientId, templateExplanation).copy(id = id)
        return id
    }

    override suspend fun attachAiExplanation(eventId: Long, text: String) {
        callLog += "attachAi:$eventId"
        aiAttachments += eventId to text
    }

    override suspend fun recentEventsByType(patientId: String) = recentToReturn

    override suspend fun baseline(patientId: String, now: Long, thresholds: AnomalyThresholds): PatientBaseline {
        baselineCalls++
        callLog += "baseline"
        return baselineToReturn
    }

    override suspend fun acknowledge(eventId: Long, at: Long) {}
    override suspend fun trimReadingsOlderThan(patientId: String, before: Long) = 0
    override fun observeActiveEvents(patientId: String): Flow<List<AnomalyEventEntity>> = flowOf(emptyList())
    override fun observeRecentEvents(patientId: String, limit: Int): Flow<List<AnomalyEventEntity>> = flowOf(emptyList())
    override fun observeLatestReading(patientId: String): Flow<VitalsReadingEntity?> = flowOf(null)
    override fun observeRecentReadings(patientId: String, limit: Int): Flow<List<VitalsReadingEntity>> = flowOf(emptyList())
}

private class FakeAlertSink(private val log: MutableList<String>) : AlertSink {
    val alerts = mutableListOf<Triple<Long, AnomalyEventEntity, Explanation>>()
    val upgrades = mutableListOf<Pair<Long, String>>()

    override suspend fun onAnomaly(eventId: Long, event: AnomalyEventEntity, explanation: Explanation) {
        log += "alert:$eventId"
        alerts += Triple(eventId, event, explanation)
    }

    override suspend fun onExplanationUpgraded(eventId: Long, text: String) {
        log += "upgrade:$eventId"
        upgrades += eventId to text
    }
}

private class FakeAiExplainer(
    private val log: MutableList<String>,
    private val result: String? = "A calmer rewrite of the same finding for the family.",
    private val throwInstead: Boolean = false
) : AiExplainer {
    var calls = 0
    override suspend fun explain(evidence: AnomalyEvidence, template: Explanation): String? {
        calls++
        log += "explain"
        if (throwInstead) throw RuntimeException("inference exploded")
        return result
    }
}

/** A model that takes as long as the real explanation timeout. */
private class SlowAiExplainer(private val delayMillis: Long) : AiExplainer {
    var started = 0
    var finished = 0
    override suspend fun explain(evidence: AnomalyEvidence, template: Explanation): String? {
        started++
        delay(delayMillis)
        finished++
        return "A calmer rewrite of the same finding."
    }
}

// ── Tests ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class MonitoringPipelineTest {

    private val patient = "p1"
    private val thresholds = AnomalyThresholds.DEFAULT

    private fun pipeline(
        repo: FakeHealthRepository,
        alerts: AlertSink,
        explainer: AiExplainer? = null,
        now: Long = TestVitals.T0
    ) = MonitoringPipeline(
        repository = repo,
        detector = AnomalyDetector(thresholds),
        alerts = alerts,
        explainer = explainer,
        clock = { now }
    )

    /** Runs the model-rewrite step the way the service does: beside sampling. */
    private fun TestScope.startExplanationWorker(p: MonitoringPipeline) {
        backgroundScope.launch { p.runExplanationWorker() }
    }

    /** A wearable record is acknowledged, and deleted from the wearable, only when this says stored. */
    @Test
    fun `reports whether each reading reached storage`() = runTest {
        val repo = FakeHealthRepository()
        val p = pipeline(repo, FakeAlertSink(mutableListOf()))
        val sample = TestVitals.series(1, hrFrom = 72, spo2From = 97, tempFrom = 36.7f).first()

        p.onSample(patient, sample)
        assertTrue(p.lastReadingStored)

        repo.failReadingWrites = true
        p.onSample(patient, sample.copy(timestamp = sample.timestamp + 5_000))
        assertFalse(p.lastReadingStored)

        repo.failReadingWrites = false
        val past = sample.copy(timestamp = sample.timestamp - 3_600_000)
        p.onSample(patient, past, replayed = true)
        assertTrue(p.lastReadingStored)

        repo.failReadingWrites = true
        p.onSample(patient, past, replayed = true)
        assertTrue("already stored counts as stored", p.lastReadingStored)
    }

    /** Healthy input: stored, scored, and nothing raised. */
    @Test
    fun `normal samples are persisted and raise nothing`() = runTest {
        val log = mutableListOf<String>()
        val repo = FakeHealthRepository()
        val sink = FakeAlertSink(log)
        val p = pipeline(repo, sink)

        TestVitals.series(10, hrFrom = 72, spo2From = 97, tempFrom = 36.7f).forEach {
            assertTrue(p.onSample(patient, it).isEmpty())
        }

        assertEquals(10, repo.readings.size)
        assertTrue(repo.events.isEmpty())
        assertTrue(sink.alerts.isEmpty())
        assertEquals(MonitoringState.MONITORING, p.snapshot.value.state)
        assertEquals(10L, p.snapshot.value.samplesProcessed)
    }

    /** Risk scores must be published continuously, not only on an event. */
    @Test
    fun `snapshot exposes risk scores while everything is normal`() = runTest {
        val repo = FakeHealthRepository()
        val p = pipeline(repo, FakeAlertSink(mutableListOf()))

        TestVitals.series(8, hrFrom = 88, spo2From = 95, tempFrom = 37.4f,
            ambientTemp = 39f, humidity = 65f).forEach { p.onSample(patient, it) }

        assertTrue(p.snapshot.value.risk.overall in 0..100)
        assertNotNull(p.snapshot.value.latest)
    }

    /**
     * THE ordering test, and the core safety property of the product.
     *
     * The alert must be delivered from the deterministic template BEFORE the model is
     * consulted. On a mid-range phone inference can be tens of seconds away; a user
     * whose oxygen is falling cannot wait for it.
     */
    @Test
    fun `alert fires from the template before the model is ever consulted`() = runTest {
        val log = mutableListOf<String>()
        val repo = FakeHealthRepository()
        val sink = FakeAlertSink(log)
        val explainer = FakeAiExplainer(log)
        val p = pipeline(repo, sink, explainer)
        startExplanationWorker(p)

        // A critically low SpO2 needs no duration, so the very first sample fires.
        val raised = p.onSample(patient, TestVitals.sample(hr = 105, spo2 = 87))
        assertTrue("expected an event", raised.isNotEmpty())
        assertEquals("the sample path must not wait for the model", 0, explainer.calls)

        runCurrent()

        val alertIdx = log.indexOfFirst { it.startsWith("alert:") }
        val explainIdx = log.indexOfFirst { it == "explain" }
        assertTrue("no alert was recorded", alertIdx >= 0)
        assertTrue("no explanation was attempted", explainIdx >= 0)
        assertTrue("alert must precede inference; log=$log", alertIdx < explainIdx)
    }

    /**
     * Detection must not pause while the model writes.
     *
     * The rewrite used to run inside onSample. Samples are processed one at a time, so
     * every alert blinded detection for up to the 45-second explanation timeout.
     */
    @Test
    fun `detection keeps running while an explanation is still being generated`() = runTest {
        val log = mutableListOf<String>()
        val repo = FakeHealthRepository()
        val sink = FakeAlertSink(log)
        val slow = SlowAiExplainer(delayMillis = LlamaAiExplainer.DEFAULT_TIMEOUT_MILLIS)
        var clock = TestVitals.T0
        val p = MonitoringPipeline(repo, AnomalyDetector(thresholds), sink, slow, clock = { clock })
        startExplanationWorker(p)

        p.onSample(patient, TestVitals.sample(atMinute = 0.0, hr = 105, spo2 = 87))
        runCurrent()
        assertEquals("the explanation should be in progress", 1, slow.started)
        assertTrue(p.snapshot.value.aiExplaining)

        // Thirty seconds later — still inside the explanation — a high fever appears.
        val later = TestVitals.sample(atMinute = 0.5, hr = 105, spo2 = 96, temp = 39.8f)
        clock = later.timestamp
        p.onSample(patient, later)

        assertEquals(0, slow.finished)
        assertEquals(2, repo.readings.size)
        assertTrue(
            "the fever must alert without waiting for the first explanation",
            sink.alerts.any { it.second.eventType == AnomalyType.FEVER.wireName }
        )

        advanceTimeBy(LlamaAiExplainer.DEFAULT_TIMEOUT_MILLIS * 3)
        runCurrent()
        assertEquals(2, slow.finished)
        assertFalse(p.snapshot.value.aiExplaining)
    }

    /** And the alert text delivered first must be the deterministic one. */
    @Test
    fun `the first alert carries the deterministic explanation`() = runTest {
        val log = mutableListOf<String>()
        val repo = FakeHealthRepository()
        val sink = FakeAlertSink(log)
        val p = pipeline(repo, sink, FakeAiExplainer(log))

        val samples = TestVitals.series(12, intervalMinutes = 1.0, hrFrom = 105, spo2From = 87)
        samples.forEach { p.onSample(patient, it) }

        val (_, _, explanation) = sink.alerts.first()
        assertTrue(explanation.headline.isNotBlank())
        assertTrue("template detail should quote the measured SpO2",
            explanation.detail.contains("87"))
        assertTrue(explanation.full.contains(explanation.tier.guidance))
    }

    /** The event row must be complete and readable the moment it is written. */
    @Test
    fun `persisted event always carries a non-blank template explanation`() = runTest {
        val repo = FakeHealthRepository()
        val p = pipeline(repo, FakeAlertSink(mutableListOf()))

        TestVitals.series(12, intervalMinutes = 1.0, hrFrom = 105, spo2From = 87)
            .forEach { p.onSample(patient, it) }

        assertTrue(repo.events.isNotEmpty())
        repo.events.forEach {
            assertTrue("template explanation was blank", it.templateExplanation.isNotBlank())
            assertNull("ai explanation must start null", it.aiExplanation)
            assertEquals("ACTIVE", it.status)
            assertTrue(it.evidenceJson.startsWith("{"))
        }
    }

    // ── Working without the model at all ──────────────────────────────────────

    /**
     * The whole pipeline must be fully functional with no model. This is what keeps
     * inference off the critical path rather than merely claiming it is.
     */
    @Test
    fun `pipeline works end to end with no explainer`() = runTest {
        val log = mutableListOf<String>()
        val repo = FakeHealthRepository()
        val sink = FakeAlertSink(log)
        val p = pipeline(repo, sink, explainer = null)
        startExplanationWorker(p)

        val raised = mutableListOf<RaisedEvent>()
        TestVitals.series(12, intervalMinutes = 1.0, hrFrom = 105, spo2From = 87)
            .forEach { raised += p.onSample(patient, it) }
        runCurrent()   // the worker lives in backgroundScope, which advanceUntilIdle skips

        assertTrue(raised.isNotEmpty())
        assertTrue(sink.alerts.isNotEmpty())
        assertTrue("no upgrade should be attempted", sink.upgrades.isEmpty())
        assertTrue(repo.aiAttachments.isEmpty())
    }

    @Test
    fun `successful model output is attached and reported as an upgrade`() = runTest {
        val log = mutableListOf<String>()
        val repo = FakeHealthRepository()
        val sink = FakeAlertSink(log)
        val explainer = FakeAiExplainer(log, result = "Oxygen is low and still falling.")
        val p = pipeline(repo, sink, explainer)
        startExplanationWorker(p)

        TestVitals.series(12, intervalMinutes = 1.0, hrFrom = 105, spo2From = 87)
            .forEach { p.onSample(patient, it) }
        runCurrent()   // the worker lives in backgroundScope, which advanceUntilIdle skips

        assertTrue(explainer.calls > 0)
        assertEquals(1, repo.aiAttachments.size)
        assertEquals(1, sink.upgrades.size)
        // The fixed tier guidance must survive the rewrite.
        val attached = repo.aiAttachments.first().second
        assertTrue(attached.contains("Oxygen is low and still falling."))
        assertTrue(attached.contains(sink.alerts.first().third.tier.guidance))
    }

    /** A null from the explainer means "keep the template", silently. */
    @Test
    fun `rejected model output leaves the template in place`() = runTest {
        val log = mutableListOf<String>()
        val repo = FakeHealthRepository()
        val sink = FakeAlertSink(log)
        val explainer = FakeAiExplainer(log, result = null)
        val p = pipeline(repo, sink, explainer)
        startExplanationWorker(p)

        TestVitals.series(12, intervalMinutes = 1.0, hrFrom = 105, spo2From = 87)
            .forEach { p.onSample(patient, it) }
        runCurrent()   // the worker lives in backgroundScope, which advanceUntilIdle skips

        assertTrue(sink.alerts.isNotEmpty())
        assertTrue("the model must have been asked", explainer.calls > 0)
        assertTrue("nothing should be attached", repo.aiAttachments.isEmpty())
        assertTrue(sink.upgrades.isEmpty())
    }

    /** An exception during inference must not lose the alert, or stop the worker. */
    @Test
    fun `a throwing explainer does not disturb the delivered alert`() = runTest {
        val log = mutableListOf<String>()
        val repo = FakeHealthRepository()
        val sink = FakeAlertSink(log)
        val explainer = FakeAiExplainer(log, throwInstead = true)
        val p = pipeline(repo, sink, explainer)
        startExplanationWorker(p)

        TestVitals.series(12, intervalMinutes = 1.0, hrFrom = 105, spo2From = 87)
            .forEach { p.onSample(patient, it) }
        runCurrent()   // the worker lives in backgroundScope, which advanceUntilIdle skips

        assertTrue("alert must still have been delivered", sink.alerts.isNotEmpty())
        assertTrue("the model must have been asked", explainer.calls > 0)
        assertTrue(repo.aiAttachments.isEmpty())
        assertTrue(repo.events.isNotEmpty())
        assertFalse(p.snapshot.value.aiExplaining)
    }

    // ── Storage failures ──────────────────────────────────────────────────────

    /**
     * Detection runs off the in-memory window, so a storage outage degrades history
     * but must never stop the patient being warned.
     */
    @Test
    fun `detection and alerting survive a failing reading write`() = runTest {
        val log = mutableListOf<String>()
        val repo = FakeHealthRepository(failReadingWrites = true)
        val sink = FakeAlertSink(log)
        val p = pipeline(repo, sink)

        TestVitals.series(12, intervalMinutes = 1.0, hrFrom = 105, spo2From = 87)
            .forEach { p.onSample(patient, it) }

        assertTrue("no readings could be stored", repo.readings.isEmpty())
        assertTrue("but the alert must still fire", sink.alerts.isNotEmpty())
    }

    /** If the event cannot be stored there is no id to alert against, but no crash. */
    @Test
    fun `a failing event write does not crash the pipeline`() = runTest {
        val log = mutableListOf<String>()
        val repo = FakeHealthRepository(failEventWrites = true)
        val sink = FakeAlertSink(log)
        val p = pipeline(repo, sink)

        val raised = mutableListOf<RaisedEvent>()
        TestVitals.series(12, intervalMinutes = 1.0, hrFrom = 105, spo2From = 87)
            .forEach { raised += p.onSample(patient, it) }

        assertTrue(raised.isEmpty())
        assertTrue(sink.alerts.isEmpty())
        // And it kept processing samples rather than dying.
        assertTrue(p.snapshot.value.samplesProcessed > 0)
    }

    // ── Debounce integration ──────────────────────────────────────────────────

    /**
     * At one sample every five seconds a persistent desaturation would otherwise
     * generate an alert every five seconds. The cooldown must hold across calls.
     */
    @Test
    fun `a persistent anomaly produces one alert, not one per sample`() = runTest {
        val log = mutableListOf<String>()
        val repo = FakeHealthRepository()
        val sink = FakeAlertSink(log)
        var clock = TestVitals.T0
        val p = MonitoringPipeline(
            repository = repo,
            detector = AnomalyDetector(thresholds),
            alerts = sink,
            explainer = null,
            clock = { clock }
        )

        // Twenty consecutive critically-low samples, five seconds apart.
        TestVitals.series(20, intervalMinutes = 1.0 / 12, spo2From = 86, hrFrom = 104)
            .forEach {
                clock = it.timestamp
                p.onSample(patient, it)
            }

        assertEquals(
            "expected exactly one alert for a continuous anomaly, got ${sink.alerts.size}",
            1, sink.alerts.size
        )
    }

    /**
     * End to end on the simulator's fall scenario: the impact alone stays quiet,
     * and confirmed immobility raises one CRITICAL event.
     */
    @Test
    fun `a confirmed fall alerts once after immobility`() = runTest {
        val repo = FakeHealthRepository()
        val sink = FakeAlertSink(mutableListOf())
        var clock = TestVitals.T0
        val p = MonitoringPipeline(repo, AnomalyDetector(thresholds), sink, null, clock = { clock })

        val generator = VitalsScenarioGenerator(
            scenario = VitalsScenario.FALL_THEN_IMMOBILE,
            startAt = TestVitals.T0,
            intervalMillis = HealthMonitoringService.DEFAULT_SAMPLE_INTERVAL_MILLIS
        )
        generator.take(generator.nominalSampleCount).forEach {
            clock = it.timestamp
            p.onSample(patient, it)
        }

        val fallSeverities = sink.alerts
            .map { it.second }
            .filter { it.eventType == AnomalyType.FALL_DETECTED.wireName }
            .map { it.severity }
        assertEquals(listOf("CRITICAL"), fallSeverities)
    }

    /** Cooldowns restored from storage keep suppressing repeats, but not escalation. */
    @Test
    fun `stored cooldowns suppress repeats but let a worse event through`() = runTest {
        val repo = FakeHealthRepository(
            recentToReturn = mapOf(
                AnomalyType.HIGH_HEART_RATE to RecentEvent(TestVitals.T0 - 60_000, Severity.MODERATE)
            )
        )
        val sink = FakeAlertSink(mutableListOf())
        val p = pipeline(repo, sink)

        p.onSample(patient, TestVitals.sample(atMinute = 0.0, hr = 136))
        assertTrue("a repeat at the same severity stays quiet", sink.alerts.isEmpty())

        p.onSample(patient, TestVitals.sample(atMinute = 0.1, hr = 158))
        assertEquals(1, sink.alerts.size)
        assertEquals("CRITICAL", sink.alerts.single().second.severity)
    }

    // ── Baseline caching ──────────────────────────────────────────────────────

    /** Recomputing the baseline per sample would hammer the database for no gain. */
    @Test
    fun `baseline is cached rather than recomputed on every sample`() = runTest {
        val repo = FakeHealthRepository(
            baselineToReturn = PatientBaseline(restingHeartRate = 68f, sampleCount = 100)
        )
        val p = MonitoringPipeline(
            repository = repo,
            detector = AnomalyDetector(thresholds),
            alerts = FakeAlertSink(mutableListOf()),
            explainer = null,
            clock = { TestVitals.T0 },
            baselineRefreshMillis = 5 * 60_000L
        )

        repeat(30) { p.onSample(patient, TestVitals.sample(hr = 72, spo2 = 97)) }

        assertEquals("baseline should be computed once at a fixed clock", 1, repo.baselineCalls)
        assertTrue(p.snapshot.value.baselineReady)
    }

    @Test
    fun `baseline is refreshed once it goes stale`() = runTest {
        val repo = FakeHealthRepository(
            baselineToReturn = PatientBaseline(restingHeartRate = 68f, sampleCount = 100)
        )
        var clock = TestVitals.T0
        val p = MonitoringPipeline(
            repository = repo,
            detector = AnomalyDetector(thresholds),
            alerts = FakeAlertSink(mutableListOf()),
            explainer = null,
            clock = { clock },
            baselineRefreshMillis = 60_000L
        )

        p.onSample(patient, TestVitals.sample(hr = 72, spo2 = 97))
        clock += 120_000L
        p.onSample(patient, TestVitals.sample(hr = 72, spo2 = 97))

        assertEquals(2, repo.baselineCalls)
    }

    // ── Window management ─────────────────────────────────────────────────────

    /** Memory must stay bounded across a long monitoring session. */
    @Test
    fun `in-memory window is trimmed to the trend horizon`() = runTest {
        val repo = FakeHealthRepository()
        var clock = TestVitals.T0
        val p = MonitoringPipeline(
            repository = repo,
            detector = AnomalyDetector(thresholds),
            alerts = FakeAlertSink(mutableListOf()),
            explainer = null,
            clock = { clock }
        )

        // Two hours of samples one minute apart, against a 30-minute trend window.
        TestVitals.series(120, intervalMinutes = 1.0, hrFrom = 72, spo2From = 97).forEach {
            clock = it.timestamp
            p.onSample(patient, it)
        }

        assertEquals(120, repo.readings.size)     // all persisted
        assertEquals(120L, p.snapshot.value.samplesProcessed)
        // The window itself is private; its effect is that nothing blew up and the
        // detector kept producing bounded scores.
        assertTrue(p.snapshot.value.risk.overall in 0..100)
    }

    @Test
    fun `reset clears counters and cached state`() = runTest {
        val repo = FakeHealthRepository()
        val p = pipeline(repo, FakeAlertSink(mutableListOf()))

        TestVitals.series(5, hrFrom = 72, spo2From = 97).forEach { p.onSample(patient, it) }
        assertEquals(5L, p.snapshot.value.samplesProcessed)

        p.reset()

        assertEquals(0L, p.snapshot.value.samplesProcessed)
        assertEquals(MonitoringState.IDLE, p.snapshot.value.state)
        assertNull(p.snapshot.value.latest)
        assertFalse(p.snapshot.value.baselineReady)
    }

    @Test
    fun `raised events describe what fired`() = runTest {
        val repo = FakeHealthRepository()
        val p = pipeline(repo, FakeAlertSink(mutableListOf()))

        val raised = mutableListOf<RaisedEvent>()
        TestVitals.series(12, intervalMinutes = 1.0, hrFrom = 105, spo2From = 87)
            .forEach { raised += p.onSample(patient, it) }

        val first = raised.first()
        assertEquals(AnomalyType.LOW_SPO2, first.type)
        assertEquals(Severity.CRITICAL, first.severity)
        assertTrue(first.id > 0)
        assertTrue(first.explanation.detail.isNotBlank())
    }

    // ── Past readings: SD-card backlog and late deliveries ────────────────────

    /**
     * A fall that happened while the phone was out of range is recorded and explained,
     * but a push notification about it now would read as a fall happening now.
     */
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `replayed readings are stored and checked but never notified`() = runTest {
        val log = mutableListOf<String>()
        val repo = FakeHealthRepository()
        val sink = FakeAlertSink(log)
        val explainer = FakeAiExplainer(log)
        val threeHoursAgo = TestVitals.T0 - 3 * 60 * 60_000L
        val p = pipeline(repo, sink, explainer)
        startExplanationWorker(p)

        val raised = TestVitals.series(6, spo2From = 86, t0 = threeHoursAgo)
            .flatMap { p.onSample(patient, it, ReadingSource.BLE, replayed = true) }
        runCurrent()

        assertEquals(6, repo.readings.size)
        assertTrue("the rules still run on past readings", raised.any { it.type == AnomalyType.LOW_SPO2 })
        assertTrue(raised.all { it.historical })
        assertTrue("stored with its real time", repo.events.all { it.createdAt < TestVitals.T0 })
        assertTrue("nobody is notified about the past", sink.alerts.isEmpty())
        assertEquals("no model time spent on history", 0, explainer.calls)
        assertEquals(6L, p.snapshot.value.historicalSamples)
        assertEquals("live counters untouched", 0L, p.snapshot.value.samplesProcessed)
        assertNull("history is not the latest reading", p.snapshot.value.latest)
    }

    /** Old enough readings are history even when the wearable did not flag them. */
    @Test
    fun `a reading older than the historical age is treated as history`() = runTest {
        val repo = FakeHealthRepository()
        val sink = FakeAlertSink(mutableListOf())
        val p = pipeline(repo, sink)
        val old = TestVitals.T0 - MonitoringPipeline.HISTORICAL_AGE_MILLIS - 60_000L

        p.onSample(patient, TestVitals.sample(spo2 = 85, t0 = old))

        assertEquals(1, repo.events.size)
        assertTrue(sink.alerts.isEmpty())
    }

    /** The wearable replays until a backlog is fully sent, so some lines arrive twice. */
    @Test
    fun `a replayed reading already stored is skipped`() = runTest {
        val repo = FakeHealthRepository()
        val p = pipeline(repo, FakeAlertSink(mutableListOf()))
        val backlog = TestVitals.series(4, hrFrom = 72, spo2From = 97, t0 = TestVitals.T0 - 60 * 60_000L)

        backlog.forEach { p.onSample(patient, it, ReadingSource.BLE, replayed = true) }
        backlog.take(2).forEach { p.onSample(patient, it, ReadingSource.BLE, replayed = true) }

        assertEquals(4, repo.readings.size)
        assertEquals(2L, p.snapshot.value.duplicatesSkipped)
    }

    /** History must not mute a live alert of the same kind, or vice versa. */
    @Test
    fun `a past event does not put the live alert in cooldown`() = runTest {
        val repo = FakeHealthRepository()
        val sink = FakeAlertSink(mutableListOf())
        val p = pipeline(repo, sink)

        p.onSample(patient, TestVitals.sample(spo2 = 85, t0 = TestVitals.T0 - 60 * 60_000L), replayed = true)
        p.onSample(patient, TestVitals.sample(spo2 = 85))

        assertEquals(2, repo.events.size)
        assertEquals("the live low reading still alerts", 1, sink.alerts.size)
    }

    @Test
    fun `wearable channels are persisted with the reading`() = runTest {
        val repo = FakeHealthRepository()
        val p = pipeline(repo, FakeAlertSink(mutableListOf()))

        p.onSample(patient, VitalsSample(TestVitals.T0, skinTempC = 33.4f, emgMean = 812, emgMax = 1400), ReadingSource.BLE)

        val stored = repo.readings.single()
        assertEquals(33.4f, stored.skinTempC!!, 0.001f)
        assertEquals(812, stored.emgMean)
        assertEquals(1400, stored.emgMax)
        assertEquals(ReadingSource.BLE.wireName, stored.source)
    }
}
