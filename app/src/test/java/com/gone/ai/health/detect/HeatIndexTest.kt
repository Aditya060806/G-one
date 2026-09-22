package com.gone.ai.health.detect

import com.gone.ai.health.domain.AnomalyThresholds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatIndexTest {

    private val t = AnomalyThresholds.DEFAULT

    @Test
    fun `converts between scales`() {
        assertEquals(32f, HeatIndex.cToF(0f), 0.01f)
        assertEquals(212f, HeatIndex.cToF(100f), 0.01f)
        assertEquals(0f, HeatIndex.fToC(32f), 0.01f)
        assertEquals(37f, HeatIndex.fToC(98.6f), 0.01f)
    }

    /** In mild conditions the apparent temperature should track the air temperature. */
    @Test
    fun `mild conditions read close to air temperature`() {
        assertEquals(25f, HeatIndex.computeC(25f, 50f), 1.5f)
    }

    /**
     * The whole reason this class exists: humidity, not temperature alone, determines
     * how dangerous heat is, because it decides whether sweat can evaporate.
     */
    @Test
    fun `humidity raises the heat index at fixed temperature`() {
        val dry = HeatIndex.computeC(38f, 25f)
        val humid = HeatIndex.computeC(38f, 85f)
        assertTrue("humid ($humid) should exceed dry ($dry)", humid > dry)
        // And the gap should be large enough to matter clinically, not a rounding blip.
        assertTrue("gap was only ${humid - dry}", humid - dry > 5f)
    }

    @Test
    fun `temperature raises the heat index at fixed humidity`() {
        assertTrue(HeatIndex.computeC(42f, 50f) > HeatIndex.computeC(36f, 50f))
    }

    @Test
    fun `hot and humid produces a dangerous reading`() {
        // 40 C at 70% RH is genuinely extreme; it must land well above the raw temp.
        assertTrue(HeatIndex.computeC(40f, 70f) > 50f)
    }

    /** Apparent temperature is never cooler than the actual air temperature. */
    @Test
    fun `never reports below air temperature in the low range`() {
        listOf(10f, 15f, 20f, 24f, 26f).forEach { c ->
            assertTrue("failed at $c", HeatIndex.computeC(c, 40f) >= c - 0.01f)
        }
    }

    @Test
    fun `clamps out of range humidity instead of producing nonsense`() {
        val low = HeatIndex.computeC(35f, -20f)
        val high = HeatIndex.computeC(35f, 150f)
        assertTrue(low.isFinite())
        assertTrue(high.isFinite())
        assertEquals(HeatIndex.computeC(35f, 0f), low, 0.01f)
        assertEquals(HeatIndex.computeC(35f, 100f), high, 0.01f)
    }

    /**
     * Returning null rather than defaulting matters: a fabricated humidity would
     * produce a confident heat index no sensor supports, and that number could go on
     * to justify a heat-stress alert.
     */
    @Test
    fun `requires both inputs`() {
        assertNull(HeatIndex.computeOrNull(null, 50f))
        assertNull(HeatIndex.computeOrNull(30f, null))
        assertNull(HeatIndex.computeOrNull(null, null))
        assertEquals(HeatIndex.computeC(30f, 50f), HeatIndex.computeOrNull(30f, 50f)!!, 0.01f)
    }
}

class EnvironmentContextTest {

    private val t = AnomalyThresholds.DEFAULT

    @Test
    fun `flags heat wave from the heat index when humidity is known`() {
        val env = EnvironmentContext.from(ambientTempC = 38f, humidityPct = 80f, aqi = null)
        assertTrue(env.heatIndexC != null)
        assertTrue(env.isHeatWave(t))
    }

    /** Without humidity there is no heat index, so it must fall back to air temp. */
    @Test
    fun `falls back to air temperature when humidity is unknown`() {
        val env = EnvironmentContext.from(ambientTempC = 41f, humidityPct = null, aqi = null)
        assertNull(env.heatIndexC)
        assertTrue(env.isHeatWave(t))
        assertFalse(env.isSevereHeat(t))
    }

    @Test
    fun `mild weather is not a heat wave`() {
        val env = EnvironmentContext.from(24f, 45f, 60)
        assertFalse(env.isHeatWave(t))
        assertFalse(env.isSevereHeat(t))
    }

    @Test
    fun `applies CPCB style air quality bands`() {
        assertFalse(EnvironmentContext.from(25f, 50f, 150).isPoorAir(t))
        assertTrue(EnvironmentContext.from(25f, 50f, 250).isPoorAir(t))
        assertFalse(EnvironmentContext.from(25f, 50f, 250).isSevereAir(t))
        assertTrue(EnvironmentContext.from(25f, 50f, 350).isSevereAir(t))
    }

    /** Rules must be able to tell "known good" from "no sensor at all". */
    @Test
    fun `reports unknown when nothing environmental is available`() {
        assertTrue(EnvironmentContext.NONE.isUnknown)
        assertFalse(EnvironmentContext.from(25f, null, null).isUnknown)
        // And an absent reading must never masquerade as a hazard.
        assertFalse(EnvironmentContext.NONE.isHeatWave(t))
        assertFalse(EnvironmentContext.NONE.isPoorAir(t))
    }
}
