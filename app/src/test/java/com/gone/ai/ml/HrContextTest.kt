package com.gone.ai.ml

import com.gone.ai.health.domain.VitalsSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The heart-rate model must read in the app exactly what it read in training. Compared with the
 * Python specification on real PAMAP2 readings (`hr_context_golden.txt`).
 */
class HrContextTest {

    private val golden = GoldenFile.hrContext()

    @Test
    fun `builds the same inputs as the training code`() {
        assertTrue(golden.size >= 4)
        golden.forEach { case ->
            val mine = HrContextFeatures.features(case.times, case.motion, case.nowMillis)
            assertNotNull(case.name, mine)
            for (k in mine!!.indices) assertEquals("${case.name} input $k", case.features[k], mine[k], 2e-5f)
        }
    }

    /** The model wrapper hands the scorer those inputs and turns its answer into a band. */
    @Test
    fun `the wrapper scores the same inputs and builds the band around the resting rate`() {
        val case = golden.first()
        var seen: FloatArray? = null
        val model = HrContextModel({ input -> seen = input; case.outputs })
        val samples = case.times.indices.map { VitalsSample(case.times[it], motionMagnitudeG = case.motion[it].takeIf { m -> !m.isNaN() }) }
        val band = model.expected(samples, case.nowMillis, case.resting)!!
        for (k in case.features.indices) assertEquals(case.features[k], seen!![k], 2e-5f)
        val sorted = case.outputs.sortedArray()
        assertEquals(case.resting + sorted[0] * 50f, band.low, 1e-3f)
        assertEquals(case.resting + sorted[2] * 50f, band.high, 1e-3f)
        assertTrue(band.low <= band.middle && band.middle <= band.high)
    }

    @Test
    fun `out-of-order model outputs are sorted into a proper band`() {
        val band = HrContextFeatures.band(floatArrayOf(0.4f, -0.1f, 0.1f), resting = 70f, version = 1)
        assertEquals(65f, band.low, 1e-4f)
        assertEquals(75f, band.middle, 1e-4f)
        assertEquals(90f, band.high, 1e-4f)
    }

    @Test
    fun `too little movement data gives no inputs at all`() {
        val now = 1_000_000L
        val times = LongArray(40) { now - it * 5_000L }
        val motion = FloatArray(40) { 1.0f }
        assertNull("40 readings of a possible 60", HrContextFeatures.features(times, motion, now))
    }

    @Test
    fun `missing movement values do not count towards the five minutes`() {
        val now = 1_000_000L
        val times = LongArray(60) { now - it * 5_000L }
        val motion = FloatArray(60) { if (it % 4 == 0) Float.NaN else 1.0f }
        assertNull(HrContextFeatures.features(times, motion, now))
    }

    @Test
    fun `still, then moving, reads as more activity`() {
        val now = 1_000_000L
        val times = LongArray(60) { now - (59 - it) * 5_000L }
        val still = HrContextFeatures.features(times, FloatArray(60) { 1.0f }, now)!!
        val moving = HrContextFeatures.features(times, FloatArray(60) { 1.8f }, now)!!
        assertEquals(0f, still[0], 1e-6f)
        assertEquals(0.8f, moving[0], 1e-5f)
        assertEquals(0f, still[6], 1e-6f)
        assertEquals(1f, moving[6], 1e-6f)
    }
}
