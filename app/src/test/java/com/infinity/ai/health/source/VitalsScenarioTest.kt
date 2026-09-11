package com.infinity.ai.health.source

import com.infinity.ai.health.detect.AnomalyDetector
import com.infinity.ai.health.domain.AnomalyThresholds
import com.infinity.ai.health.domain.AnomalyType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VitalsScenarioGeneratorTest {

    private val t0 = 1_700_000_000_000L

    private fun gen(
        scenario: VitalsScenario,
        intervalMillis: Long = 30_000L,
        seed: Long = 42L
    ) = VitalsScenarioGenerator(scenario, t0, intervalMillis, seed)

    /**
     * The property the whole design hangs on. Because jitter is a hash of
     * (seed, index, field) rather than draws from a sequential PRNG, `sampleAt(n)` is
     * a pure function of n. Tests can jump straight to minute 20 of a scenario, and a
     * failure reproduces byte-for-byte from the seed alone.
     */
    @Test
    fun `sampleAt is order independent`() {
        val g = gen(VitalsScenario.DESATURATION_EPISODE)

        val direct = g.sampleAt(40)
        repeat(40) { g.sampleAt(it) }           // walk the sequence first
        val afterWalking = g.sampleAt(40)

        assertEquals(direct, afterWalking)
    }

    @Test
    fun `same seed reproduces identical output`() {
        val a = gen(VitalsScenario.HEAT_WAVE_EXPOSURE, seed = 7).take(30)
        val b = gen(VitalsScenario.HEAT_WAVE_EXPOSURE, seed = 7).take(30)
        assertEquals(a, b)
    }

    @Test
    fun `different seeds produce different jitter but the same shape`() {
        val a = gen(VitalsScenario.HEALTHY_BASELINE, seed = 1).take(30)
        val b = gen(VitalsScenario.HEALTHY_BASELINE, seed = 2).take(30)
        assertNotEquals(a, b)
        // Same underlying scenario, so means stay close.
        val meanA = a.mapNotNull { it.heartRate }.average()
        val meanB = b.mapNotNull { it.heartRate }.average()
        assertEquals(meanA, meanB, 4.0)
    }

    @Test
    fun `timestamps advance by the configured interval`() {
        val g = gen(VitalsScenario.HEALTHY_BASELINE, intervalMillis = 5_000L)
        assertEquals(t0, g.sampleAt(0).timestamp)
        assertEquals(t0 + 5_000L, g.sampleAt(1).timestamp)
        assertEquals(t0 + 50_000L, g.sampleAt(10).timestamp)
    }

    @Test
    fun `rejects a non-positive interval`() {
        try {
            VitalsScenarioGenerator(VitalsScenario.HEALTHY_BASELINE, t0, 0L)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // correct
        }
    }

    @Test
    fun `rejects a negative index`() {
        try {
            gen(VitalsScenario.HEALTHY_BASELINE).sampleAt(-1)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // correct
        }
    }

    @Test
    fun `every scenario produces physiologically plausible values throughout`() {
        VitalsScenario.entries.forEach { scenario ->
            val g = gen(scenario)
            g.take(minOf(g.nominalSampleCount, 400)).forEach { s ->
                s.heartRate?.let {
                    assertTrue("$scenario produced HR $it", it in 25..220)
                }
                s.spo2?.let {
                    assertTrue("$scenario produced SpO2 $it", it in 70..100)
                }
                s.bodyTempC?.let {
                    assertTrue("$scenario produced temp $it", it in 34f..43f)
                }
                s.motionMagnitudeG?.let {
                    assertTrue("$scenario produced motion $it", it in 0f..8f)
                }
                s.aqi?.let {
                    assertTrue("$scenario produced AQI $it", it in 0..1000)
                }
            }
        }
    }

    // ── Scenario shapes drive the rules they are meant to drive ───────────────

    /**
     * The false-positive control, and arguably the most valuable test here. Thirty
     * minutes of healthy vitals must produce ZERO alerts. A monitor that cries wolf on
     * a healthy person is worse than useless.
     */
    @Test
    fun `healthy baseline never triggers an anomaly`() {
        val g = gen(VitalsScenario.HEALTHY_BASELINE, intervalMillis = 30_000L)
        val detector = AnomalyDetector(AnomalyThresholds.DEFAULT)
        val samples = g.take(g.nominalSampleCount)

        // Slide a growing window across the whole scenario, as the pipeline would.
        samples.indices.forEach { i ->
            val window = samples.subList(maxOf(0, i - 60), i + 1)
            val result = detector.evaluate(window, now = samples[i].timestamp)
            assertTrue(
                "healthy baseline fired ${result.candidates.map { it.type }} at index $i",
                result.candidates.isEmpty()
            )
        }
    }

    @Test
    fun `desaturation episode drives oxygen below the critical threshold`() {
        val g = gen(VitalsScenario.DESATURATION_EPISODE, intervalMillis = 30_000L)
        val samples = g.take(g.nominalSampleCount)

        assertTrue("starts healthy", samples.first().spo2!! >= 95)
        assertTrue("ends desaturated: ${samples.last().spo2}", samples.last().spo2!! <= 90)

        val detector = AnomalyDetector(AnomalyThresholds.DEFAULT)
        val result = detector.evaluate(samples, now = samples.last().timestamp)
        assertTrue(
            "expected a desaturation event, got ${result.candidates.map { it.type }}",
            result.candidates.any {
                it.type == AnomalyType.LOW_SPO2 || it.type == AnomalyType.SUSTAINED_LOW_SPO2
            }
        )
    }

    @Test
    fun `heat wave exposure raises ambient temperature and core temperature together`() {
        val g = gen(VitalsScenario.HEAT_WAVE_EXPOSURE, intervalMillis = 30_000L)
        val samples = g.take(g.nominalSampleCount)

        assertTrue(samples.last().ambientTempC!! > samples.first().ambientTempC!! + 8f)
        assertTrue(samples.last().bodyTempC!! > samples.first().bodyTempC!! + 1f)
        assertTrue(samples.last().heartRate!! > samples.first().heartRate!! + 30)

        val detector = AnomalyDetector(AnomalyThresholds.DEFAULT)
        val result = detector.evaluate(samples, now = samples.last().timestamp)
        assertTrue(
            "expected a heat-related event, got ${result.candidates.map { it.type }}",
            result.candidates.any {
                it.type == AnomalyType.HEAT_STRESS ||
                    it.type == AnomalyType.DEHYDRATION_RISK ||
                    it.type == AnomalyType.FEVER
            }
        )
    }

    @Test
    fun `tachycardia episode reaches the critical rate at rest`() {
        val g = gen(VitalsScenario.TACHYCARDIA_EPISODE, intervalMillis = 30_000L)
        val samples = g.take(g.nominalSampleCount)
        assertTrue("peak HR was ${samples.maxOf { it.heartRate!! }}",
            samples.maxOf { it.heartRate!! } >= 140)

        val detector = AnomalyDetector(AnomalyThresholds.DEFAULT)
        val result = detector.evaluate(samples, now = samples.last().timestamp)
        assertTrue(result.candidates.any { it.type == AnomalyType.HIGH_HEART_RATE })
    }

    @Test
    fun `bradycardia episode drops below the low threshold`() {
        val g = gen(VitalsScenario.BRADYCARDIA_EPISODE, intervalMillis = 30_000L)
        val samples = g.take(g.nominalSampleCount)
        assertTrue(samples.last().heartRate!! <= 45)

        val detector = AnomalyDetector(AnomalyThresholds.DEFAULT)
        val result = detector.evaluate(samples, now = samples.last().timestamp)
        assertTrue(result.candidates.any { it.type == AnomalyType.LOW_HEART_RATE })
    }

    @Test
    fun `fever onset crosses the fever threshold`() {
        val g = gen(VitalsScenario.FEVER_ONSET, intervalMillis = 60_000L)
        val samples = g.take(g.nominalSampleCount)
        assertTrue("final temp was ${samples.last().bodyTempC}", samples.last().bodyTempC!! >= 39f)

        val detector = AnomalyDetector(AnomalyThresholds.DEFAULT)
        val result = detector.evaluate(samples, now = samples.last().timestamp)
        assertTrue(result.candidates.any { it.type == AnomalyType.FEVER })
    }

    @Test
    fun `air quality event drives AQI into the severe band`() {
        val g = gen(VitalsScenario.AIR_QUALITY_EVENT, intervalMillis = 30_000L)
        val samples = g.take(g.nominalSampleCount)
        assertTrue("final AQI was ${samples.last().aqi}", samples.last().aqi!! >= 300)
        assertTrue(samples.last().spo2!! < samples.first().spo2!!)
    }

    /** Impact followed by stillness — both halves must be present in the data. */
    @Test
    fun `fall scenario contains an impact spike then immobility`() {
        val g = gen(VitalsScenario.FALL_THEN_IMMOBILE, intervalMillis = 10_000L)
        val samples = g.take(g.nominalSampleCount)

        val impactIdx = samples.indexOfFirst { (it.motionMagnitudeG ?: 0f) >= 2.5f }
        assertTrue("no impact spike found", impactIdx >= 0)

        val after = samples.drop(impactIdx + 1)
        assertTrue("no samples after the impact", after.isNotEmpty())
        assertTrue("expected immobility after the impact",
            after.take(5).all { (it.motionMagnitudeG ?: 0f) <= 1.05f })

        val detector = AnomalyDetector(AnomalyThresholds.DEFAULT)
        val result = detector.evaluate(samples, now = samples.last().timestamp)
        assertTrue(result.candidates.any { it.type == AnomalyType.FALL_DETECTED })
    }

    /**
     * The early-warning scenario, and the tightest constraint of the set: it must stay
     * inside every fixed threshold while still drifting away from the patient's own
     * baseline, so that ONLY the baseline rule can see it.
     */
    @Test
    fun `gradual deterioration stays inside every fixed threshold`() {
        val t = AnomalyThresholds.DEFAULT
        val g = gen(VitalsScenario.GRADUAL_DETERIORATION, intervalMillis = 60_000L)
        val samples = g.take(g.nominalSampleCount)

        samples.forEach { s ->
            assertTrue("HR ${s.heartRate} reached the tachycardia threshold",
                s.heartRate!! < t.hrHighBpm)
            assertTrue("HR ${s.heartRate} reached the bradycardia threshold",
                s.heartRate!! > t.hrLowBpm)
            assertTrue("SpO2 ${s.spo2} fell to the sustained threshold",
                s.spo2!! >= t.spo2SustainedBelow)
            assertTrue("temp ${s.bodyTempC} reached the fever threshold",
                s.bodyTempC!! < t.tempFeverC)
        }

        // But it does drift meaningfully away from where it started.
        assertTrue(samples.last().heartRate!! >= samples.first().heartRate!! + 12)
        assertTrue(samples.first().spo2!! - samples.last().spo2!! >= 3)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedVitalsSourceTest {

    @Test
    fun `describes itself as a simulated source`() {
        val src = SimulatedVitalsSource(VitalsScenario.HEALTHY_BASELINE)
        assertEquals(
            com.infinity.ai.health.data.ReadingSource.SIMULATED,
            src.descriptor.transport
        )
        assertTrue(src.descriptor.displayName.contains("Simulated"))
    }

    @Test
    fun `starts idle`() {
        val src = SimulatedVitalsSource(VitalsScenario.HEALTHY_BASELINE)
        assertEquals(SourceStatus.Idle, src.status.value)
        assertFalse(src.status.value.isLive)
    }

    /**
     * `runTest` drives `delay` on virtual time, so a scenario that would take minutes
     * of wall clock is exercised in milliseconds. That is what makes it practical to
     * test long physiological timelines at all.
     */
    @Test
    fun `emits the requested number of samples and completes`() = runTest {
        val src = SimulatedVitalsSource(
            scenario = VitalsScenario.DESATURATION_EPISODE,
            intervalMillis = 30_000L,
            startAt = 1_700_000_000_000L,
            sampleLimit = 20
        )
        val samples = src.stream().toList()

        assertEquals(20, samples.size)
        assertEquals(SourceStatus.Stopped, src.status.value)
        // Chronological and evenly spaced.
        samples.zipWithNext().forEach { (a, b) ->
            assertEquals(30_000L, b.timestamp - a.timestamp)
        }
    }

    @Test
    fun `emitted samples match the generator exactly`() = runTest {
        val src = SimulatedVitalsSource(
            scenario = VitalsScenario.HEAT_WAVE_EXPOSURE,
            intervalMillis = 30_000L,
            startAt = 1_700_000_000_000L,
            seed = 99L,
            sampleLimit = 10
        )
        val streamed = src.stream().toList()
        assertEquals(src.generator.take(10), streamed)
    }

    @Test
    fun `reports streaming while active`() = runTest {
        val src = SimulatedVitalsSource(
            scenario = VitalsScenario.HEALTHY_BASELINE,
            intervalMillis = 1_000L,
            sampleLimit = 5
        )
        var sawStreaming = false
        src.stream().collect {
            if (src.status.value.isLive) sawStreaming = true
        }
        assertTrue(sawStreaming)
    }

    /** Cancellation is normal shutdown and must not be reported as a failure. */
    @Test
    fun `cancellation is reported as stopped not failed`() = runTest {
        val src = SimulatedVitalsSource(
            scenario = VitalsScenario.HEALTHY_BASELINE,
            intervalMillis = 1_000L,
            sampleLimit = null
        )
        val taken = mutableListOf<com.infinity.ai.health.domain.VitalsSample>()
        try {
            src.stream().collect {
                taken += it
                if (taken.size >= 3) throw kotlinx.coroutines.CancellationException("done")
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            // expected
        }
        assertEquals(3, taken.size)
        assertEquals(SourceStatus.Stopped, src.status.value)
    }
}
