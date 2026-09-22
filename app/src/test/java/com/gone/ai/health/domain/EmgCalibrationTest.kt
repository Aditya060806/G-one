package com.gone.ai.health.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmgCalibrationTest {

    private fun relaxed(level: Int, n: Int = 40) = List(n) { level + (it % 5) * 10 }   // level .. level+40
    private fun clench(level: Int, n: Int = 10) = List(n) { level + (it % 3 - 1) * 20 }

    @Test
    fun `levels sit between the wearer's relaxed and clenched readings`() {
        val result = EmgCalibration.compute(relaxed(300), clench(2300))
        val levels = result as EmgCalibration.Result.Levels
        assertEquals(340, levels.relaxed)
        assertEquals(2300, levels.clench)
        assertEquals(928, levels.active)       // 340 + 0.3 * 1960
        assertEquals(1516, levels.high)        // 340 + 0.6 * 1960
        assertEquals(2104, levels.veryHigh)    // 340 + 0.9 * 1960
        val t = levels.applyTo(AnomalyThresholds.DEFAULT)
        assertEquals(1516, t.emgHighLevel)
    }

    @Test
    fun `a twitch while relaxed does not move the relaxed level much`() {
        val twitchy = relaxed(300).toMutableList().apply { this[3] = 3000 }
        val levels = EmgCalibration.compute(twitchy, clench(2300)) as EmgCalibration.Result.Levels
        assertEquals(340, levels.relaxed)
    }

    @Test
    fun `too few readings asks to try again`() {
        val result = EmgCalibration.compute(relaxed(300, n = 10), clench(2300))
        assertTrue((result as EmgCalibration.Result.Problem).message.contains("Too few"))
    }

    @Test
    fun `no real contraction is refused`() {
        val result = EmgCalibration.compute(relaxed(300), clench(420))
        assertTrue((result as EmgCalibration.Result.Problem).message.contains("too close"))
    }

    @Test
    fun `a clench pinned at the rail asks for less gain`() {
        val result = EmgCalibration.compute(relaxed(300), List(10) { 4095 })
        assertTrue((result as EmgCalibration.Result.Problem).message.contains("gain"))
    }
}
