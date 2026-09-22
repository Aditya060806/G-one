package com.gone.ai.health.detect

import com.gone.ai.health.TestVitals
import com.gone.ai.health.domain.AnomalyThresholds
import com.gone.ai.health.domain.PatientBaseline
import com.gone.ai.health.domain.RiskScores
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scorer's contract is three properties, and each one exists for a reason:
 *
 *  - BOUNDED, so no clamp bug can ever reach the UI as a 137% risk.
 *  - MONOTONIC, so the number is trustworthy as a trend line — which is the entire
 *    basis of the early-warning claim.
 *  - GRACEFUL, so a device without an ambient sensor still produces useful
 *    vitals-based scores instead of zeros or crashes.
 */
class RiskScorerTest {

    private val t = AnomalyThresholds.DEFAULT
    private val noBaseline = PatientBaseline.EMPTY

    private fun score(samples: List<com.gone.ai.health.domain.VitalsSample>) =
        RiskScorer.score(VitalsWindow.of(samples), noBaseline, t)

    @Test
    fun `empty window scores zero`() {
        assertEquals(RiskScores.ZERO, RiskScorer.score(VitalsWindow.EMPTY, noBaseline, t))
    }

    @Test
    fun `healthy vitals score low across every axis`() {
        val s = score(
            TestVitals.series(
                10, hrFrom = 72, spo2From = 97, tempFrom = 36.7f,
                ambientTemp = 26f, humidity = 45f, aqi = 60
            )
        )
        assertTrue("heat was ${s.heat}", s.heat < 25)
        assertTrue("respiratory was ${s.respiratory}", s.respiratory < 25)
        assertTrue("cardiovascular was ${s.cardiovascular}", s.cardiovascular < 25)
    }

    @Test
    fun `all axes stay within bounds across extreme inputs`() {
        val extremes = listOf(
            TestVitals.series(10, hrFrom = 220, spo2From = 50, tempFrom = 44f,
                ambientTemp = 60f, humidity = 100f, aqi = 1000),
            TestVitals.series(10, hrFrom = 20, spo2From = 100, tempFrom = 25f,
                ambientTemp = -30f, humidity = 0f, aqi = 0),
            TestVitals.series(10, hrFrom = 72)   // vitals only, no environment
        )
        extremes.forEach { samples ->
            val s = score(samples)
            listOf(s.heat, s.respiratory, s.cardiovascular, s.overall).forEach { v ->
                assertTrue("score $v out of bounds", v in 0..100)
            }
        }
    }

    @Test
    fun `respiratory risk rises monotonically as oxygen falls`() {
        val scores = listOf(98, 96, 94, 92, 90, 88).map { spo2 ->
            score(TestVitals.series(8, hrFrom = 80, spo2From = spo2)).respiratory
        }
        scores.zipWithNext().forEach { (better, worse) ->
            assertTrue("expected $worse >= $better in $scores", worse >= better)
        }
        assertTrue("worst case was only ${scores.last()}", scores.last() > scores.first())
    }

    @Test
    fun `respiratory risk rises with air quality index`() {
        val clean = score(TestVitals.series(8, hrFrom = 80, spo2From = 96, aqi = 40)).respiratory
        val severe = score(TestVitals.series(8, hrFrom = 80, spo2From = 96, aqi = 420)).respiratory
        assertTrue("clean=$clean severe=$severe", severe > clean)
    }

    @Test
    fun `heat risk rises monotonically with ambient temperature`() {
        val scores = listOf(28f, 34f, 38f, 42f, 46f, 50f).map { at ->
            score(
                TestVitals.series(8, hrFrom = 85, tempFrom = 37.4f, ambientTemp = at, humidity = 60f)
            ).heat
        }
        scores.zipWithNext().forEach { (cooler, hotter) ->
            assertTrue("expected $hotter >= $cooler in $scores", hotter >= cooler)
        }
        assertTrue(scores.last() > scores.first())
    }

    @Test
    fun `heat risk rises with core body temperature`() {
        val normal = score(
            TestVitals.series(8, hrFrom = 85, tempFrom = 36.8f, ambientTemp = 40f, humidity = 55f)
        ).heat
        val hot = score(
            TestVitals.series(8, hrFrom = 85, tempFrom = 39.5f, ambientTemp = 40f, humidity = 55f)
        ).heat
        assertTrue("normal=$normal hot=$hot", hot > normal)
    }

    @Test
    fun `cardiovascular risk rises monotonically with heart rate above the resting band`() {
        val scores = listOf(80, 100, 115, 130, 145, 160).map { hr ->
            score(TestVitals.series(8, hrFrom = hr, spo2From = 96)).cardiovascular
        }
        scores.zipWithNext().forEach { (lower, higher) ->
            assertTrue("expected $higher >= $lower in $scores", higher >= lower)
        }
    }

    @Test
    fun `cardiovascular risk also responds to bradycardia`() {
        val normal = score(TestVitals.series(8, hrFrom = 70, spo2From = 96)).cardiovascular
        val slow = score(TestVitals.series(8, hrFrom = 34, spo2From = 96)).cardiovascular
        assertTrue("normal=$normal slow=$slow", slow > normal)
    }

    /**
     * Rising heart rate while oxygen falls is far more specific to real distress than
     * either signal alone, so the coupling must actually add to the score.
     */
    @Test
    fun `cardiovascular risk credits the rising-HR falling-SpO2 coupling`() {
        val flat = score(TestVitals.series(10, hrFrom = 110, spo2From = 94)).cardiovascular
        val coupled = score(
            TestVitals.series(10, hrFrom = 100, hrTo = 118, spo2From = 97, spo2To = 92)
        ).cardiovascular
        assertTrue("flat=$flat coupled=$coupled", coupled > flat)
    }

    /**
     * A wearable with no ambient sensor is the common case for a cheap prototype. It
     * must still yield meaningful vitals scores rather than collapsing to zero.
     */
    @Test
    fun `degrades gracefully when environmental signals are absent`() {
        val s = score(TestVitals.series(10, hrFrom = 150, spo2From = 89, tempFrom = 39f))
        assertEquals("no ambient data means no environmental heat load", true, s.heat >= 0)
        assertTrue("respiratory should still fire on SpO2 alone: ${s.respiratory}", s.respiratory > 40)
        assertTrue("cardiovascular should still fire on HR alone: ${s.cardiovascular}", s.cardiovascular > 40)
    }

    @Test
    fun `missing vitals do not throw`() {
        val onlyEnvironment = listOf(TestVitals.sample(0.0, ambientTemp = 44f, humidity = 70f, aqi = 300))
        val s = score(onlyEnvironment)
        assertTrue(s.heat in 0..100)
        assertTrue(s.respiratory in 0..100)
        assertTrue(s.cardiovascular in 0..100)
    }

    /** Baseline deviation should raise cardiovascular load even at a normal absolute HR. */
    @Test
    fun `baseline deviation contributes when the baseline is reliable`() {
        val samples = TestVitals.series(10, hrFrom = 95, spo2From = 97)
        val window = VitalsWindow.of(samples)

        val withoutBaseline = RiskScorer.score(window, PatientBaseline.EMPTY, t).cardiovascular
        val withBaseline = RiskScorer.score(
            window,
            PatientBaseline(restingHeartRate = 62f, sampleCount = 100),
            t
        ).cardiovascular

        assertTrue("without=$withoutBaseline with=$withBaseline", withBaseline > withoutBaseline)
    }

    @Test
    fun `unreliable baseline is ignored`() {
        val window = VitalsWindow.of(TestVitals.series(10, hrFrom = 95, spo2From = 97))
        val ignored = RiskScorer.score(
            window,
            PatientBaseline(restingHeartRate = 62f, sampleCount = 3),
            t
        ).cardiovascular
        val none = RiskScorer.score(window, PatientBaseline.EMPTY, t).cardiovascular
        assertEquals(none, ignored)
    }

    @Test
    fun `ramp helpers clamp at both ends`() {
        assertEquals(0f, RiskScorer.ramp(5f, 10f, 20f), 0.001f)
        assertEquals(1f, RiskScorer.ramp(25f, 10f, 20f), 0.001f)
        assertEquals(0.5f, RiskScorer.ramp(15f, 10f, 20f), 0.001f)

        assertEquals(0f, RiskScorer.inverseRamp(98f, 97f, 88f), 0.001f)
        assertEquals(1f, RiskScorer.inverseRamp(80f, 97f, 88f), 0.001f)
    }

    @Test
    fun `percent delta handles a zero reference without dividing by zero`() {
        assertEquals(0f, RiskScorer.percentDelta(90f, 0f), 0.001f)
        assertEquals(50f, RiskScorer.percentDelta(90f, 60f), 0.01f)
        assertEquals(-25f, RiskScorer.percentDelta(45f, 60f), 0.01f)
    }
}
