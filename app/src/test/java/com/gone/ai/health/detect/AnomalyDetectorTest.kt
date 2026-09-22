package com.gone.ai.health.detect

import com.gone.ai.health.TestVitals
import com.gone.ai.health.domain.AnomalyCandidate
import com.gone.ai.health.domain.AnomalyThresholds
import com.gone.ai.health.domain.AnomalyType
import com.gone.ai.health.domain.PatientBaseline
import com.gone.ai.health.domain.RecentEvent
import com.gone.ai.health.domain.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnomalyDetectorTest {

    private val t = AnomalyThresholds.DEFAULT

    /** A rule that always fires, for testing orchestration independently of physiology. */
    private class AlwaysRule(
        override val id: String,
        private val type: AnomalyType,
        private val severity: Severity
    ) : AnomalyRule {
        override fun evaluate(ctx: DetectionContext): AnomalyCandidate =
            ctx.candidate(type, severity, id)
    }

    private class ThrowingRule(override val id: String = "boom") : AnomalyRule {
        override fun evaluate(ctx: DetectionContext): AnomalyCandidate =
            throw IllegalStateException("rule blew up")
    }

    // ── Always-on risk scoring ────────────────────────────────────────────────

    /**
     * Scores must be produced on every evaluation, not only when something fires.
     * This is the mechanism behind the early-warning claim: the dashboard shows a
     * rising trend before any threshold is crossed.
     */
    @Test
    fun `risk scores are produced even when nothing fires`() {
        val detector = AnomalyDetector(t)
        val healthy = TestVitals.series(10, hrFrom = 72, spo2From = 97, tempFrom = 36.7f)

        val result = detector.evaluate(healthy, now = TestVitals.T0)

        assertFalse(result.hasAnomaly)
        assertNull(result.peakSeverity)
        assertNotNull(result.riskScores)
        assertTrue(result.riskScores.overall in 0..100)
    }

    @Test
    fun `empty input yields no candidates and zero risk`() {
        val result = AnomalyDetector(t).evaluate(emptyList(), now = TestVitals.T0)
        assertFalse(result.hasAnomaly)
        assertEquals(0, result.riskScores.overall)
        assertTrue(result.window.isEmpty)
    }

    // ── Real physiology end-to-end ────────────────────────────────────────────

    @Test
    fun `critical desaturation surfaces as the primary candidate`() {
        val detector = AnomalyDetector(t)
        val samples = TestVitals.series(12, intervalMinutes = 1.0, hrFrom = 105, spo2From = 87)

        val result = detector.evaluate(samples, now = samples.last().timestamp)

        assertTrue(result.hasAnomaly)
        assertEquals(Severity.CRITICAL, result.peakSeverity)
        assertEquals(AnomalyType.LOW_SPO2, result.primary!!.type)
        assertTrue("respiratory risk was ${result.riskScores.respiratory}",
            result.riskScores.respiratory > 50)
    }

    /** Candidates are ordered worst-first so the UI can show the right one. */
    @Test
    fun `candidates are ordered by descending severity`() {
        val detector = AnomalyDetector(
            t,
            rules = listOf(
                AlwaysRule("low", AnomalyType.FATIGUE, Severity.LOW),
                AlwaysRule("mod", AnomalyType.FEVER, Severity.MODERATE),
                AlwaysRule("crit", AnomalyType.LOW_SPO2, Severity.CRITICAL)
            )
        )
        val result = detector.evaluate(TestVitals.series(3, hrFrom = 80), now = TestVitals.T0)
        val ranks = result.candidates.map { it.severity.rank }
        assertEquals(ranks.sortedDescending(), ranks)
        assertEquals(Severity.CRITICAL, result.primary!!.severity)
    }

    // ── Cooldown ──────────────────────────────────────────────────────────────

    /**
     * Without debouncing, a vital hovering at a threshold emits one event per sample.
     * At 1 Hz that is 60 notifications a minute, which teaches the user to ignore
     * alerts — the worst possible outcome for a health-warning app.
     */
    @Test
    fun `repeat events of the same type are suppressed within the cooldown`() {
        val detector = AnomalyDetector(
            t,
            rules = listOf(AlwaysRule("crit", AnomalyType.LOW_SPO2, Severity.CRITICAL))
        )
        val samples = TestVitals.series(3, hrFrom = 80)
        val now = TestVitals.T0 + 60_000

        val first = detector.evaluate(samples, now = now)
        assertTrue(first.hasAnomaly)

        // Same type, same severity, one minute later; cooldown is 15 minutes.
        val second = detector.evaluate(
            samples,
            now = now + 60_000,
            recentEvents = mapOf(AnomalyType.LOW_SPO2 to RecentEvent(now, Severity.CRITICAL))
        )
        assertFalse(second.hasAnomaly)
        assertEquals(1, second.suppressed.size)
        assertEquals(SuppressionReason.COOLDOWN, second.suppressed.first().reason)
    }

    @Test
    fun `the same type fires again once the cooldown expires`() {
        val detector = AnomalyDetector(
            t,
            rules = listOf(AlwaysRule("crit", AnomalyType.LOW_SPO2, Severity.CRITICAL))
        )
        val past = TestVitals.T0
        val afterCooldown = past + (t.perTypeCooldownMinutes + 1) * 60_000L

        val result = detector.evaluate(
            TestVitals.series(3, hrFrom = 80),
            now = afterCooldown,
            recentEvents = mapOf(AnomalyType.LOW_SPO2 to RecentEvent(past, Severity.CRITICAL))
        )
        assertTrue(result.hasAnomaly)
        assertTrue(result.suppressed.isEmpty())
    }

    @Test
    fun `cooldown is per type so a different event still gets through`() {
        val detector = AnomalyDetector(
            t,
            rules = listOf(
                AlwaysRule("a", AnomalyType.LOW_SPO2, Severity.CRITICAL),
                AlwaysRule("b", AnomalyType.FEVER, Severity.MODERATE)
            )
        )
        val now = TestVitals.T0 + 60_000
        val result = detector.evaluate(
            TestVitals.series(3, hrFrom = 80),
            now = now,
            recentEvents = mapOf(AnomalyType.LOW_SPO2 to RecentEvent(now - 1000, Severity.CRITICAL))
        )
        assertEquals(1, result.candidates.size)
        assertEquals(AnomalyType.FEVER, result.candidates.first().type)
        assertEquals(1, result.suppressed.size)
    }

    @Test
    fun `zero cooldown disables debouncing entirely`() {
        val noCooldown = AnomalyThresholds(perTypeCooldownMinutes = 0)
        val detector = AnomalyDetector(
            noCooldown,
            rules = listOf(AlwaysRule("crit", AnomalyType.LOW_SPO2, Severity.CRITICAL))
        )
        val now = TestVitals.T0 + 60_000
        val result = detector.evaluate(
            TestVitals.series(3, hrFrom = 80),
            now = now,
            recentEvents = mapOf(AnomalyType.LOW_SPO2 to RecentEvent(now - 100, Severity.CRITICAL))
        )
        assertTrue(result.hasAnomaly)
    }

    // ── Escalation through the cooldown ───────────────────────────────────────

    /**
     * The cooldown exists to stop repeats, not to hide a situation getting worse. Keyed
     * on type alone, a CRITICAL "fall then no movement" stayed silent for fifteen minutes
     * behind the MODERATE impact alert that preceded it.
     */
    @Test
    fun `a more severe event of the same type gets through the cooldown`() {
        val detector = AnomalyDetector(
            t,
            rules = listOf(AlwaysRule("fall", AnomalyType.FALL_DETECTED, Severity.CRITICAL))
        )
        val now = TestVitals.T0 + 60_000
        val result = detector.evaluate(
            TestVitals.series(3, hrFrom = 80),
            now = now,
            recentEvents = mapOf(AnomalyType.FALL_DETECTED to RecentEvent(now - 25_000, Severity.MODERATE))
        )
        assertTrue(result.hasAnomaly)
        assertEquals(Severity.CRITICAL, result.primary!!.severity)
        assertTrue(result.suppressed.isEmpty())
    }

    @Test
    fun `a less severe event of the same type stays suppressed within the cooldown`() {
        val detector = AnomalyDetector(
            t,
            rules = listOf(AlwaysRule("hr", AnomalyType.HIGH_HEART_RATE, Severity.MODERATE))
        )
        val now = TestVitals.T0 + 60_000
        val result = detector.evaluate(
            TestVitals.series(3, hrFrom = 80),
            now = now,
            recentEvents = mapOf(AnomalyType.HIGH_HEART_RATE to RecentEvent(now - 30_000, Severity.CRITICAL))
        )
        assertFalse(result.hasAnomaly)
        assertEquals(SuppressionReason.COOLDOWN, result.suppressed.single().reason)
    }

    /** The same property on real physiology: 135 bpm at rest, then 160 bpm minutes later. */
    @Test
    fun `heart rate climbing from moderate to critical alerts again inside the cooldown`() {
        val detector = AnomalyDetector(t)

        val moderate = TestVitals.series(6, intervalMinutes = 1.0, hrFrom = 135)
        val first = detector.evaluate(moderate, now = moderate.last().timestamp)
        val firstHr = first.candidates.single { it.type == AnomalyType.HIGH_HEART_RATE }
        assertEquals(Severity.MODERATE, firstHr.severity)

        val climbing = TestVitals.series(8, intervalMinutes = 1.0, hrFrom = 135, hrTo = 160)
        val second = detector.evaluate(
            climbing,
            now = climbing.last().timestamp,
            recentEvents = mapOf(
                AnomalyType.HIGH_HEART_RATE to RecentEvent(moderate.last().timestamp, Severity.MODERATE)
            )
        )
        val secondHr = second.candidates.single { it.type == AnomalyType.HIGH_HEART_RATE }
        assertEquals(Severity.CRITICAL, secondHr.severity)
    }

    // ── Severity shadowing ────────────────────────────────────────────────────

    /** An informational finding next to a critical alert is a distraction. */
    @Test
    fun `low severity findings are withheld while a critical event is active`() {
        val detector = AnomalyDetector(
            t,
            rules = listOf(
                AlwaysRule("crit", AnomalyType.LOW_SPO2, Severity.CRITICAL),
                AlwaysRule("low", AnomalyType.FATIGUE, Severity.LOW),
                AlwaysRule("mod", AnomalyType.FEVER, Severity.MODERATE)
            )
        )
        val result = detector.evaluate(TestVitals.series(3, hrFrom = 80), now = TestVitals.T0)

        assertEquals(2, result.candidates.size)
        assertTrue(result.candidates.none { it.severity == Severity.LOW })
        assertEquals(1, result.suppressed.size)
        assertEquals(SuppressionReason.SHADOWED_BY_CRITICAL, result.suppressed.first().reason)
        // Withheld, but still recorded for the audit trail.
        assertEquals(AnomalyType.FATIGUE, result.suppressed.first().candidate.type)
    }

    @Test
    fun `low severity survives when nothing critical is present`() {
        val detector = AnomalyDetector(
            t,
            rules = listOf(
                AlwaysRule("low", AnomalyType.FATIGUE, Severity.LOW),
                AlwaysRule("mod", AnomalyType.FEVER, Severity.MODERATE)
            )
        )
        val result = detector.evaluate(TestVitals.series(3, hrFrom = 80), now = TestVitals.T0)
        assertEquals(2, result.candidates.size)
        assertTrue(result.suppressed.isEmpty())
    }

    // ── Fault isolation ───────────────────────────────────────────────────────

    /**
     * A bug in one rule must not stop the others. Monitoring continuing with one rule
     * degraded is strictly better than the service dying and the patient going
     * unwatched.
     */
    @Test
    fun `a throwing rule does not stop the other rules`() {
        val detector = AnomalyDetector(
            t,
            rules = listOf(
                ThrowingRule(),
                AlwaysRule("crit", AnomalyType.LOW_SPO2, Severity.CRITICAL)
            )
        )
        val result = detector.evaluate(TestVitals.series(3, hrFrom = 80), now = TestVitals.T0)
        assertEquals(1, result.candidates.size)
        assertEquals(AnomalyType.LOW_SPO2, result.primary!!.type)
    }

    @Test
    fun `risk scores survive a throwing rule`() {
        val detector = AnomalyDetector(t, rules = listOf(ThrowingRule()))
        val result = detector.evaluate(
            TestVitals.series(8, hrFrom = 150, spo2From = 88),
            now = TestVitals.T0
        )
        assertFalse(result.hasAnomaly)
        assertTrue(result.riskScores.overall > 0)
    }

    // ── Evidence integrity ────────────────────────────────────────────────────

    /**
     * The payload handed to the explanation layer must contain the values that were
     * actually measured. Everything downstream — including the model — trusts this.
     */
    @Test
    fun `evidence carries the observed values and the firing rule id`() {
        val detector = AnomalyDetector(t)
        val samples = TestVitals.series(
            12, intervalMinutes = 1.0, hrFrom = 108, spo2From = 87, tempFrom = 37.4f
        )
        val primary = detector.evaluate(samples, now = samples.last().timestamp).primary!!

        assertEquals(87, primary.evidence.spo2)
        assertEquals(108, primary.evidence.heartRate)
        assertEquals(37.4f, primary.evidence.bodyTempC!!, 0.01f)
        assertEquals("spo2.critical", primary.evidence.ruleId)
        assertEquals(samples.last().timestamp, primary.evidence.triggeredAt)
        assertEquals(12, primary.evidence.sampleCount)
        // Risk scores travel with the evidence so the explanation can reference them.
        assertEquals(primary.riskScores, primary.evidence.riskScores)
    }

    @Test
    fun `unsorted input is normalised before evaluation`() {
        val detector = AnomalyDetector(t)
        val chronological = TestVitals.series(12, intervalMinutes = 1.0, spo2From = 91)

        val forward = detector.evaluate(chronological, now = chronological.last().timestamp)
        val reversed = detector.evaluate(chronological.reversed(), now = chronological.last().timestamp)

        assertEquals(forward.candidates.map { it.type }, reversed.candidates.map { it.type })
        assertEquals(forward.riskScores, reversed.riskScores)
    }

    /** Same inputs must always give the same answer — no hidden state, no clock reads. */
    @Test
    fun `evaluation is deterministic`() {
        val detector = AnomalyDetector(t)
        val samples = TestVitals.series(15, intervalMinutes = 1.0, hrFrom = 120, spo2From = 90)
        val baseline = PatientBaseline(restingHeartRate = 70f, sampleCount = 100)

        val a = detector.evaluate(samples, baseline, now = TestVitals.T0)
        val b = detector.evaluate(samples, baseline, now = TestVitals.T0)

        assertEquals(a.candidates.map { it.type to it.severity }, b.candidates.map { it.type to it.severity })
        assertEquals(a.riskScores, b.riskScores)
    }
}
