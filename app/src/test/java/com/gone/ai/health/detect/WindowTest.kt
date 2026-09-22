package com.gone.ai.health.detect

import com.gone.ai.health.TestVitals
import com.gone.ai.health.domain.Trend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VitalsWindowTest {

    /**
     * The DAO returns newest-first for index efficiency; the engine needs oldest-first
     * for trend math. If this normalisation broke, every falling SpO2 would read as
     * rising — a silent inversion of the most safety-critical signal in the app.
     */
    @Test
    fun `normalises any input order to chronological`() {
        val chronological = TestVitals.series(5, spo2From = 97, spo2To = 90)
        val newestFirst = chronological.reversed()

        val w = VitalsWindow.of(newestFirst)

        assertEquals(chronological.first().timestamp, w.oldest!!.timestamp)
        assertEquals(chronological.last().timestamp, w.latest!!.timestamp)
        assertEquals(90, w.latest!!.spo2)
        assertEquals(Trend.FALLING, w.spo2Trend(0.05f))
    }

    @Test
    fun `empty window is safe to query`() {
        val w = VitalsWindow.EMPTY
        assertTrue(w.isEmpty)
        assertNull(w.latest)
        assertNull(w.oldest)
        assertEquals(0L, w.spanMillis)
        assertNull(w.peakMotion())
        assertEquals(Trend.STABLE, w.hrTrend(0.1f))
        assertEquals(EnvironmentContext.NONE, w.environment)
    }

    @Test
    fun `span covers oldest to newest`() {
        val w = VitalsWindow.of(TestVitals.series(11, intervalMinutes = 1.0, hrFrom = 70))
        assertEquals(10 * 60_000L, w.spanMillis)
    }

    @Test
    fun `at rest reflects the newest sample only`() {
        val moving = TestVitals.series(3, hrFrom = 80, motion = TestVitals.MOVING)
        assertFalse(VitalsWindow.of(moving).isAtRest)

        val resting = TestVitals.series(3, hrFrom = 80, motion = TestVitals.REST)
        assertTrue(VitalsWindow.of(resting).isAtRest)
    }

    /** Unknown motion must not be read as movement, or rest-gated rules never fire. */
    @Test
    fun `unknown motion counts as at rest`() {
        assertTrue(VitalsWindow.of(listOf(TestVitals.sample(hr = 80, motion = null))).isAtRest)
    }

    @Test
    fun `peak motion finds the impact`() {
        val samples = listOf(
            TestVitals.sample(0.0, motion = 1.0f),
            TestVitals.sample(0.1, motion = 3.4f),
            TestVitals.sample(0.2, motion = 1.0f)
        )
        assertEquals(3.4f, VitalsWindow.of(samples).peakMotion()!!, 0.001f)
    }

    @Test
    fun `sustained measures a continuous run back from the newest sample`() {
        // 11 samples one minute apart, all below 92.
        val w = VitalsWindow.of(TestVitals.series(11, intervalMinutes = 1.0, spo2From = 91))
        val held = w.sustained({ it.spo2 }, { it < 92 })
        assertEquals(11, held.sampleCount)
        assertEquals(10, held.minutes)
        assertTrue(held.atLeastMinutes(10))
        assertFalse(held.atLeastMinutes(15))
    }

    /**
     * THE subtle one. An SpO2 sensor briefly losing skin contact yields a null, which
     * is *unknown*, not *recovered*. Treating it as a break would reset the streak and
     * a genuine ten-minute desaturation would almost never be reported on real
     * hardware.
     */
    @Test
    fun `sustained skips unknown values without breaking the run`() {
        val samples = listOf(
            TestVitals.sample(0.0, spo2 = 91),
            TestVitals.sample(3.0, spo2 = null),   // sensor dropout
            TestVitals.sample(6.0, spo2 = 91),
            TestVitals.sample(12.0, spo2 = 91)
        )
        val held = VitalsWindow.of(samples).sustained({ it.spo2 }, { it < 92 })
        assertEquals(3, held.sampleCount)          // the null contributed nothing
        assertEquals(12, held.minutes)             // but did not truncate the span
        assertTrue(held.atLeastMinutes(10))
    }

    @Test
    fun `sustained breaks on a known passing value`() {
        val samples = listOf(
            TestVitals.sample(0.0, spo2 = 91),
            TestVitals.sample(5.0, spo2 = 97),     // genuinely recovered
            TestVitals.sample(10.0, spo2 = 91)
        )
        val held = VitalsWindow.of(samples).sustained({ it.spo2 }, { it < 92 })
        assertEquals(1, held.sampleCount)
        assertEquals(0, held.minutes)
        assertFalse(held.atLeastMinutes(10))
    }

    @Test
    fun `sustained requires the minimum sample count`() {
        val samples = listOf(
            TestVitals.sample(0.0, spo2 = 91),
            TestVitals.sample(30.0, spo2 = 91)
        )
        val held = VitalsWindow.of(samples).sustained({ it.spo2 }, { it < 92 })
        assertTrue(held.atLeastMinutes(10, minSamples = 2))
        assertFalse(held.atLeastMinutes(10, minSamples = 5))
    }

    @Test
    fun `sustained on an all-unknown series reports nothing`() {
        val w = VitalsWindow.of(TestVitals.series(5, hrFrom = 70))  // no spo2 at all
        assertEquals(Sustained.NONE, w.sustained({ it.spo2 }, { it < 92 }))
    }

    @Test
    fun `environment derives from the newest sample`() {
        val samples = listOf(
            TestVitals.sample(0.0, hr = 70, ambientTemp = 25f, humidity = 40f, aqi = 50),
            TestVitals.sample(1.0, hr = 70, ambientTemp = 42f, humidity = 70f, aqi = 320)
        )
        val env = VitalsWindow.of(samples).environment
        assertEquals(42f, env.ambientTempC!!, 0.01f)
        assertEquals(320, env.aqi)
    }
}
