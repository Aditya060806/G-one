package com.gone.ai.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app must hand the fall model exactly what the training script handed it. These tests run
 * the Kotlin code over real recordings and compare with what the Python code produced from the
 * same recordings (`fall_golden.txt`, written by `python -m ml.fall.train`).
 */
class FallWindowTest {

    private val golden = GoldenFile.fall()

    @Test
    fun `the golden file holds falls and daily activities`() {
        assertTrue(golden.cases.size >= 6)
        assertTrue(golden.cases.any { it.fall })
        assertTrue(golden.cases.any { !it.fall })
        assertTrue(golden.threshold > 0f && golden.threshold < 1f)
    }

    @Test
    fun `finds the same impacts as the training code`() {
        golden.cases.forEach { case ->
            val mag = FloatArray(case.x.size) { FallWindow.magnitude(case.x[it], case.y[it], case.z[it]) }
            assertEquals(case.source, case.candidates, FallWindow.candidates(mag))
            assertTrue("${case.source}: the scored impact is one of them", case.impact in case.candidates)
        }
    }

    @Test
    fun `builds the same model input as the training code`() {
        golden.cases.forEach { case ->
            val mine = FallWindow.features(case.x, case.y, case.z, case.impact)
            assertEquals(case.features.size, mine.size)
            for (k in mine.indices) assertEquals("${case.source} value $k", case.features[k], mine[k], 2e-6f)
        }
    }

    @Test
    fun `a window that runs past the recording repeats the nearest sample`() {
        val x = floatArrayOf(0.1f, 0.2f, 3f)
        val y = floatArrayOf(0f, 0f, 0f)
        val z = floatArrayOf(1f, 1f, 1f)
        val features = FallWindow.features(x, y, z, impact = 2)
        // Row 0 is 25 samples before the impact: before the start, so the first sample.
        assertEquals(0.1f / FallWindow.CLIP_G, features[0], 1e-6f)
        // The last row is far past the end, so the last sample.
        assertEquals(3f / FallWindow.CLIP_G, features[(FallWindow.WINDOW - 1) * 4], 1e-6f)
    }

    @Test
    fun `values beyond four g are clipped as the training sensor clipped them`() {
        val features = FallWindow.features(floatArrayOf(7.5f), floatArrayOf(-9f), floatArrayOf(0f), impact = 0)
        assertEquals(1f, features[0], 1e-6f)
        assertEquals(-1f, features[1], 1e-6f)
        assertEquals(FallWindow.magnitude(4f, 4f, 0f) / FallWindow.MAG_SCALE, features[3], 1e-6f)
    }

    @Test
    fun `nothing below two g is an impact`() {
        assertTrue(FallWindow.candidates(FloatArray(60) { 1.0f + (it % 5) * 0.2f }).isEmpty())
    }

    @Test
    fun `a flat top counts once, at its first sample`() {
        val mag = FloatArray(40) { 1f }
        mag[20] = 4f; mag[21] = 4f; mag[22] = 4f
        assertEquals(listOf(20), FallWindow.candidates(mag))
    }

    // ── As the movement arrives ──────────────────────────────────────────────

    private fun feed(stream: FallStream, x: FloatArray, y: FloatArray, z: FloatArray, perSample: Int = 1) {
        val step = 1_000_000_000L / FallWindow.RATE_HZ / perSample
        var t = 0L
        for (i in x.indices) repeat(perSample) {
            stream.add(t, x[i], y[i], z[i])
            t += step
        }
        // One more reading closes the last bin.
        stream.add(t, x.last(), y.last(), z.last())
    }

    /** Fed sample by sample, a recording gives the same model input as when it is read whole. */
    @Test
    fun `the live stream reports the same input for the same impact`() {
        var compared = 0
        golden.cases.forEach { case ->
            val seen = mutableListOf<FloatArray>()
            val stream = FallStream { features, _ -> seen += features }
            feed(stream, case.x, case.y, case.z)
            // Reported live only when the recording holds the whole window around the impact.
            val complete = case.impact >= FallWindow.PRE && case.impact + (FallWindow.WINDOW - FallWindow.PRE) <= case.x.size
            if (!complete) return@forEach
            val match = seen.firstOrNull { live -> live.indices.all { kotlin.math.abs(live[it] - case.features[it]) < 2e-6f } }
            assertTrue("${case.source}: impact at ${case.impact} was not reported with the same input", match != null)
            compared++
        }
        assertTrue("no golden case was long enough to compare", compared > 0)
    }

    /** A phone sensor is faster than 25 Hz; its readings are averaged onto the grid. */
    @Test
    fun `a faster sensor is averaged down to the same samples`() {
        val case = golden.cases.first { it.impact >= FallWindow.PRE && it.impact + 75 <= it.x.size }
        val once = mutableListOf<FloatArray>()
        val twice = mutableListOf<FloatArray>()
        feed(FallStream { f, _ -> once += f }, case.x, case.y, case.z, perSample = 1)
        feed(FallStream { f, _ -> twice += f }, case.x, case.y, case.z, perSample = 2)
        assertEquals(once.size, twice.size)
        once.zip(twice).forEach { (a, b) -> for (k in a.indices) assertEquals(a[k], b[k], 1e-5f) }
    }

    @Test
    fun `quiet movement reports nothing`() {
        var impacts = 0
        val stream = FallStream { _, _ -> impacts++ }
        val n = 400
        feed(stream, FloatArray(n) { 0.05f }, FloatArray(n) { 0.02f }, FloatArray(n) { 1f })
        assertEquals(0, impacts)
    }

    @Test
    fun `readings that are not numbers are ignored`() {
        var impacts = 0
        val stream = FallStream { _, _ -> impacts++ }
        stream.add(0, Float.NaN, 0f, 1f)
        stream.add(40_000_000, 0f, Float.POSITIVE_INFINITY, 1f)
        assertEquals(0, impacts)
        assertFalse(impacts > 0)
    }
}
