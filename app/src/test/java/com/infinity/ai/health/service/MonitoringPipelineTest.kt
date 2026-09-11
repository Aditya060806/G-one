package com.infinity.ai.health.service

import com.infinity.ai.health.TestVitals
import com.infinity.ai.health.data.AnomalyEventEntity
import com.infinity.ai.health.data.HealthRepository
import com.infinity.ai.health.data.PatientEntity
import com.infinity.ai.health.data.ReadingSource
import com.infinity.ai.health.data.VitalsReadingEntity
import com.infinity.ai.health.data.toDomain
import com.infinity.ai.health.data.toEntity
import com.infinity.ai.health.data.toEventEntity
import com.infinity.ai.health.detect.AnomalyDetector
import com.infinity.ai.health.domain.AnomalyEvidence
import com.infinity.ai.health.domain.AnomalyThresholds
import com.infinity.ai.health.domain.AnomalyType
import com.infinity.ai.health.domain.PatientBaseline
import com.infinity.ai.health.domain.Severity
import com.infinity.ai.health.domain.VitalsSample
import com.infinity.ai.health.explain.Explanation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
    var cooldownsToReturn: Map<AnomalyType, Long> = emptyMap()
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

    override suspend fun lastEventAtByType(patientId: String) = cooldownsToReturn

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

// ── Tests ─────────────────────────────────────────────────────────────────────

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

        // A critically low SpO2 needs no duration, so the very first sample fires and
        // the cooldown then suppresses every later one. One sample is the whole story.
        val raised = p.onSample(patient, TestVitals.sample(hr = 105, spo2 = 87))

        assertTrue("expected an event", raised.isNotEmpty())

        val alertIdx = log.indexOfFirst { it.startsWith("alert:") }
        val explainIdx = log.indexOfFirst { it == "explain" }
        assertTrue("no alert was recorded", alertIdx >= 0)
        assertTrue("no explanation was attempted", explainIdx >= 0)
        assertTrue(
            "alert must precede inference; log=$log",
            alertIdx < explainIdx
        )
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

        val raised = mutableListOf<RaisedEvent>()
        TestVitals.series(12, intervalMinutes = 1.0, hrFrom = 105, spo2From = 87)
            .forEach { raised += p.onSample(patient, it) }

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

        TestVitals.series(12, intervalMinutes = 1.0, hrFrom = 105, spo2From = 87)
            .forEach { p.onSample(patient, it) }

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
        val p = pipeline(repo, sink, FakeAiExplainer(log, result = null))

        TestVitals.series(12, intervalMinutes = 1.0, hrFrom = 105, spo2From = 87)
            .forEach { p.onSample(patient, it) }

        assertTrue(sink.alerts.isNotEmpty())
        assertTrue("nothing should be attached", repo.aiAttachments.isEmpty())
        assertTrue(sink.upgrades.isEmpty())
    }

    /** An exception during inference must not lose the alert. */
    @Test
    fun `a throwing explainer does not disturb the delivered alert`() = runTest {
        val log = mutableListOf<String>()
        val repo = FakeHealthRepository()
        val sink = FakeAlertSink(log)
        val p = pipeline(repo, sink, FakeAiExplainer(log, throwInstead = true))

        TestVitals.series(12, intervalMinutes = 1.0, hrFrom = 105, spo2From = 87)
            .forEach { p.onSample(patient, it) }

        assertTrue("alert must still have been delivered", sink.alerts.isNotEmpty())
        assertTrue(repo.aiAttachments.isEmpty())
        assertTrue(repo.events.isNotEmpty())
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
}
