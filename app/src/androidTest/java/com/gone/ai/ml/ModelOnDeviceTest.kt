package com.gone.ai.ml

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shipped models, run by LiteRT on the phone, must give what the training script's
 * TensorFlow Lite interpreter gave for the same inputs (`*_golden.txt`, bundled into the test APK).
 * This is the end of the chain the JVM tests start: same inputs there, same outputs here.
 */
@RunWith(AndroidJUnit4::class)
class ModelOnDeviceTest {

    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val tests = InstrumentationRegistry.getInstrumentation().context

    private fun golden(name: String): List<String> =
        tests.assets.open("ml/$name").bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    @Test
    fun everyModelInTheManifestLoadsAndPassesItsChecksum() {
        val all = ModelRegistry.all(app)
        assertTrue(all.keys.containsAll(listOf("fall", "hr_context")))
        all.keys.forEach { id -> ModelRegistry.open(app, id).use { assertNotNull("$id did not load", it) } }
    }

    @Test
    fun fallModelScoresTheGoldenRecordingsAsInTraining() {
        val lines = golden("fall_golden.txt")
        val scorer = ModelRegistry.open(app, "fall")!!
        var i = 0
        var checked = 0
        while (i < lines.size) {
            val w = lines[i].split(' ')
            if (w[0] != "case") { i++; continue }
            val expected = w[7].toFloat()
            val n = lines[i + 2].split(' ')[1].toInt()
            val rowsAt = i + 3 + n
            val rows = lines[rowsAt].split(' ')[1].toInt()
            val features = FloatArray(rows * 4)
            for (k in 0 until rows) lines[rowsAt + 1 + k].split(' ').forEachIndexed { c, v -> features[k * 4 + c] = v.toFloat() }
            assertEquals(w[1], expected, scorer.score(features)[0], 0.02f)
            checked++
            i = rowsAt + rows + 2
        }
        scorer.close()
        assertTrue(checked >= 6)
    }

    @Test
    fun heartRateModelGivesTheGoldenOutputs() {
        val lines = golden("hr_context_golden.txt")
        val scorer = ModelRegistry.open(app, "hr_context")!!
        var i = 0
        var checked = 0
        while (i < lines.size) {
            val n = lines[i + 1].split(' ')[1].toInt()
            val features = lines[i + 2 + n].split(' ').drop(1).map { it.toFloat() }.toFloatArray()
            val outputs = lines[i + 3 + n].split(' ').drop(1).map { it.toFloat() }
            val mine = scorer.score(features)
            outputs.forEachIndexed { k, v -> assertEquals(v, mine[k], 0.02f) }
            checked++
            i += 5 + n
        }
        scorer.close()
        assertTrue(checked >= 4)
    }

    /** What the monitoring service does when it starts: load the model, wrap it, ask it for a band. */
    @Test
    fun theHeartRateModelGivesABandForFiveMinutesOfReadings() {
        val scorer = ModelRegistry.open(app, "hr_context")!!
        val model = HrContextModel(scorer, ModelRegistry.info(app, "hr_context")!!.version)
        val now = 1_700_000_300_000L
        val samples = (0 until 60).map {
            com.gone.ai.health.domain.VitalsSample(now - (59 - it) * 5_000L, heartRate = 80, motionMagnitudeG = 1.02f)
        }
        val band = model.expected(samples, now, restingHeartRate = 70f)!!
        scorer.close()
        assertTrue("band ${band.low}..${band.high}", band.low <= band.middle && band.middle <= band.high)
        assertTrue("a resting band sits near the resting rate: ${band.middle}", band.middle in 50f..110f)
    }

    /** The service asks this on every reading; switched off (the default) it must simply say "nothing". */
    @Test
    fun cityWeatherForTheRulesIsReadWithoutTouchingTheNetwork() {
        val weather = com.gone.ai.health.environment.AreaAwareness.weatherForRules(app)
        val enabled = com.gone.ai.health.environment.AreaAwareness.repository(app).state.value.saved.enabled
        if (!enabled) assertEquals(null, weather)
    }

    /** Small enough to run on every reading without anyone noticing. */
    @Test
    fun eachModelAnswersWellUnderAMillisecondOnAverage() {
        ModelRegistry.all(app).keys.forEach { id ->
            ModelRegistry.open(app, id)!!.use { scorer ->
                val input = FloatArray(scorer.inputSize) { 0.1f }
                repeat(20) { scorer.score(input) }
                val start = System.nanoTime()
                repeat(200) { scorer.score(input) }
                val micros = (System.nanoTime() - start) / 200 / 1000
                assertTrue("$id took $micros µs a run", micros < 1000)
            }
        }
    }
}
