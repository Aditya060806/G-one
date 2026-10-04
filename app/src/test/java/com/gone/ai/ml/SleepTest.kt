package com.gone.ai.ml

import com.gone.ai.health.domain.VitalsSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sleep model must read in the app what it read in training: checked against the Python
 * specification on a real night (`sleep_golden.txt`, written by `python -m ml.sleep.train`).
 */
class SleepTest {

    private class Golden(val motion: FloatArray, val epochs: List<Pair<Int, FloatArray>>)

    private fun golden(): Golden {
        val lines = GoldenFile.text("sleep_golden.txt").lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        val n = lines[0].split(' ')[3].toInt()
        val motion = FloatArray(n) { lines[1 + it].let { v -> if (v == "nan") Float.NaN else v.toFloat() } }
        val epochs = mutableListOf<Pair<Int, FloatArray>>()
        var i = 1 + n
        while (lines[i] != "end") {
            val e = lines[i].split(' ')[1].toInt()
            epochs += e to lines[i + 1].split(' ').drop(1).map { it.toFloat() }.toFloatArray()
            i += 2
        }
        return Golden(motion, epochs)
    }

    @Test
    fun `builds the same inputs as the training code on a real night`() {
        val g = golden()
        val per = SleepFeatures.epochs(g.motion)
        assertTrue(g.epochs.size >= 4)
        g.epochs.forEach { (e, expected) ->
            val mine = SleepFeatures.features(per, e)
            assertEquals(expected.size, mine.size)
            for (k in mine.indices) assertEquals("epoch $e value $k", expected[k], mine[k], 2e-5f)
        }
    }

    @Test
    fun `an epoch needs four readings and edges are padded with zeros`() {
        val motion = floatArrayOf(1f, 1f, 1f, 1f, Float.NaN, Float.NaN, 1f, Float.NaN, Float.NaN, Float.NaN, Float.NaN, 1.2f)
        val per = SleepFeatures.epochs(motion)
        assertEquals(1f, per[0][2])
        assertEquals(0f, per[1][2])
        val f = SleepFeatures.features(per, 0)
        assertEquals(SleepFeatures.FEATURES, f.size)
        for (k in 0 until SleepFeatures.CONTEXT * SleepFeatures.CHANNELS) assertEquals(0f, f[k])
    }

    @Test
    fun `readings are laid on a 5-second grid with gaps left empty`() {
        val t0 = 1_700_000_000_000L
        val samples = listOf(VitalsSample(t0, motionMagnitudeG = 1f), VitalsSample(t0 + 15_000, motionMagnitudeG = 1.1f))
        val (start, motion) = SleepFeatures.grid(samples)
        assertEquals(t0, start)
        assertEquals(4, motion.size)
        assertTrue(motion[1].isNaN() && motion[2].isNaN())
        assertEquals(1.1f, motion[3])
    }

    // ── Turning epochs into a night ──────────────────────────────────────────

    private val start = 1_700_000_000_000L

    @Test
    fun `a night is found between sustained sleep at both ends`() {
        val awakeEvening = List(20) { false }
        val night = List(840) { it % 60 != 0 }          // 7 h, a short waking each half hour
        val morning = List(20) { false }
        val e = SleepEstimator.summarise(start, awakeEvening + night + morning)!!
        assertEquals(start + 20 * 30_000L + 30_000L, e.fellAsleep)   // the first epoch of the night is awake
        assertTrue(e.asleepMinutes in 400..420)
        assertTrue(e.awakeMinutes in 5..10)
        assertEquals(1f, e.coverage, 1e-6f)
    }

    @Test
    fun `too little sleep or too little data gives no estimate`() {
        assertNull("40 minutes", SleepEstimator.summarise(start, List(80) { true }))
        val patchy = List(400) { if (it % 2 == 0) true else null }
        assertNull("half the night missing", SleepEstimator.summarise(start, patchy))
        assertNull(SleepEstimator.summarise(start, List(300) { false }))
    }

    @Test
    fun `brief dozing in the evening does not start the night`() {
        val epochs = List(5) { true } + List(30) { false } + List(600) { true } + List(10) { false }
        val e = SleepEstimator.summarise(start, epochs)
        assertNotNull(e)
        assertEquals(start + 35 * 30_000L, e!!.fellAsleep)
    }
}
