package com.gone.ai.health.session

import com.gone.ai.health.TestVitals
import com.gone.ai.health.data.ReadingSource
import com.gone.ai.health.domain.AnomalyType
import com.gone.ai.health.domain.Severity
import com.gone.ai.health.domain.VitalsSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReportBuilderTest {

    private val t0 = TestVitals.T0

    private fun reading(
        second: Int,
        hr: Int? = 72,
        spo2: Int? = 97,
        skin: Float? = null,
        core: Float? = null,
        motion: Float? = 1.0f,
        emg: Int? = null,
        emgMax: Int? = emg,
        source: ReadingSource = ReadingSource.BLE
    ) = SessionReading(
        VitalsSample(
            timestamp = t0 + second * 1000L,
            heartRate = hr,
            spo2 = spo2,
            bodyTempC = core,
            motionMagnitudeG = motion,
            skinTempC = skin,
            emgMean = emg,
            emgMax = emgMax
        ),
        source
    )

    /** One reading every 5 s for [count] readings. */
    private fun steady(count: Int, source: ReadingSource = ReadingSource.BLE) =
        (0 until count).map { reading(second = it * 5, hr = 70 + it % 5, spo2 = 96 + it % 3, source = source) }

    private fun end(readings: List<SessionReading>) = readings.last().sample.timestamp

    @Test
    fun `refuses to summarise fewer than the minimum readings`() {
        val four = steady(SessionReportBuilder.MIN_SAMPLES - 1)
        assertNull(SessionReportBuilder.build(t0, end(four), four, emptyList()))
    }

    @Test
    fun `ignores readings outside the session`() {
        val inside = steady(5)
        val before = reading(second = -600, hr = 200)
        val after = reading(second = 10_000, hr = 30)
        val r = SessionReportBuilder.build(t0, end(inside), inside + before + after, emptyList())!!
        assertEquals(5, r.sampleCount)
        assertEquals(74f, r.heartRate!!.max)
        assertEquals(70f, r.heartRate!!.min)
    }

    @Test
    fun `computes channel ranges from what was measured`() {
        val readings = listOf(
            reading(0, hr = 60, spo2 = 94, skin = 33.0f, emg = 400, emgMax = 900),
            reading(5, hr = 80, spo2 = 98, skin = 34.0f, emg = 600, emgMax = 3000),
            reading(10, hr = 70, spo2 = 96, skin = 33.5f, emg = 500),
            reading(15, hr = 90, spo2 = 97, motion = 2.8f),
            reading(20, hr = 100, spo2 = 95)
        )
        val r = SessionReportBuilder.build(t0, end(readings), readings, emptyList())!!
        assertEquals(60f, r.heartRate!!.min)
        assertEquals(80f, r.heartRate!!.mean)
        assertEquals(100f, r.heartRate!!.max)
        assertEquals(94f, r.spo2!!.min)
        assertEquals(33.0f, r.skinTempC!!.min)
        assertEquals(34.0f, r.skinTempC!!.max)
        assertEquals(500f, r.emgMean!!.mean)
        assertEquals(3000, r.emgPeak)
        assertEquals(2.8f, r.motionPeakG!!, 0.0001f)
        assertNull("no core temperature was sent", r.bodyTempC)
    }

    /**
     * REFERENCE estimated temperature and SpO₂ from other channels. A report must state
     * that a channel is absent rather than filling it in.
     */
    @Test
    fun `says a channel is absent instead of estimating it`() {
        val readings = (0 until 6).map { reading(it * 5, hr = null, spo2 = null, emg = 500) }
        val r = SessionReportBuilder.build(t0, end(readings), readings, emptyList())!!
        assertNull(r.heartRate)
        assertNull(r.spo2)
        assertTrue(r.observations.contains("No heart-rate readings were received."))
        assertTrue(r.observations.contains("No blood-oxygen readings were received."))
        assertTrue(r.observations.none { it.contains("temperature", ignoreCase = true) })
    }

    @Test
    fun `labels simulated sessions in the first sentence`() {
        val readings = steady(6, ReadingSource.SIMULATED)
        val r = SessionReportBuilder.build(t0, end(readings), readings, emptyList())!!
        assertTrue(r.isSimulated)
        assertTrue(r.observations.first(), r.observations.first().contains("simulated"))
        assertTrue(r.observations.first().contains("not measurements from a person"))
    }

    @Test
    fun `labels mixed sessions as partly simulated`() {
        val readings = steady(3, ReadingSource.SIMULATED) +
            (3 until 6).map { reading(it * 5, source = ReadingSource.BLE) }
        val r = SessionReportBuilder.build(t0, end(readings), readings, emptyList())!!
        assertEquals(setOf(ReadingSource.SIMULATED, ReadingSource.BLE), r.sources)
        assertTrue(r.observations.first().contains("some of them simulated"))
    }

    @Test
    fun `skin temperature is explicitly not a fever reading`() {
        val readings = (0 until 5).map { reading(it * 5, skin = 34.2f) }
        val r = SessionReportBuilder.build(t0, end(readings), readings, emptyList())!!
        assertTrue(r.observations.any { it.contains("Skin temperature") && it.contains("not a fever reading") })
    }

    /** The report's status is what the engine raised — never a grade of its own. */
    @Test
    fun `status is the most severe alert actually raised`() {
        val readings = steady(10)
        val events = listOf(
            SessionEvent(AnomalyType.FATIGUE, AnomalyType.FATIGUE.label, Severity.LOW, t0 + 5_000),
            SessionEvent(AnomalyType.FALL_DETECTED, AnomalyType.FALL_DETECTED.label, Severity.CRITICAL, t0 + 20_000),
            SessionEvent(AnomalyType.FATIGUE, AnomalyType.FATIGUE.label, Severity.LOW, t0 + 30_000),
            // Raised after the session ended: not part of it.
            SessionEvent(AnomalyType.LOW_SPO2, AnomalyType.LOW_SPO2.label, Severity.CRITICAL, t0 + 999_000)
        )
        val r = SessionReportBuilder.build(t0, end(readings), readings, events)!!
        assertEquals(Severity.CRITICAL, r.highestSeverity)
        assertEquals(3, r.events.size)
        val sentence = r.observations.single { it.contains("alerts were raised") }
        assertTrue(sentence, sentence.contains("Fatigue indicators (2)"))
        assertTrue(sentence, sentence.contains("Possible fall"))
        assertTrue(sentence, sentence.contains("Get help now"))
    }

    @Test
    fun `a quiet session says no alerts were raised`() {
        val readings = steady(6)
        val r = SessionReportBuilder.build(t0, end(readings), readings, emptyList())!!
        assertNull(r.highestSeverity)
        assertTrue(r.observations.contains("No alerts were raised during the session."))
    }

    @Test
    fun `counts gaps longer than a minute including at the end`() {
        val readings = listOf(0, 5, 10, 200, 205).map { reading(it) }   // 190 s break
        val sessionEnd = t0 + 500_000L                                   // 295 s after the last reading
        val r = SessionReportBuilder.build(t0, sessionEnd, readings, emptyList())!!
        assertEquals(2, r.gapCount)
        assertEquals(295_000L, r.longestGapMillis)
        assertTrue(r.observations.any { it.startsWith("Readings stopped 2 times") })
    }

    @Test
    fun `five representative points cover the session in order`() {
        val readings = (0 until 23).map { reading(it * 5, hr = 60 + it, motion = if (it == 21) 3.1f else 1.0f) }
        val r = SessionReportBuilder.build(t0, end(readings), readings, emptyList())!!
        assertEquals(listOf("Start", "25%", "50%", "75%", "End"), r.points.map { it.label })
        assertEquals(r.points.map { it.timestamp }.sorted(), r.points.map { it.timestamp })
        assertTrue(r.points.first().heartRate!! < r.points.last().heartRate!!)
        // The impact is in the last fifth and must survive as a peak, not be averaged away.
        assertEquals(3.1f, r.points.last().motionPeakG!!, 0.0001f)
    }

    @Test
    fun `an impact-level movement is called out`() {
        val readings = (0 until 5).map { reading(it * 5, motion = if (it == 2) 5.4f else 1.0f) }
        val r = SessionReportBuilder.build(t0, end(readings), readings, emptyList())!!
        assertTrue(r.observations.any { it.contains("5.4 g") && it.contains("possible impact") })
    }

    @Test
    fun `smaller knocks are not described as impact level movement`() {
        val readings = (0 until 5).map { reading(it * 5, motion = if (it == 2) 3.4f else 1.0f) }
        val r = SessionReportBuilder.build(t0, end(readings), readings, emptyList())!!
        assertTrue(r.observations.none { it.contains("possible impact") })
    }

    @Test
    fun `EMG levels are reported as uncalibrated`() {
        val readings = (0 until 5).map { reading(it * 5, emg = 1000 + it * 100) }
        val r = SessionReportBuilder.build(t0, end(readings), readings, emptyList())!!
        assertTrue(r.observations.any { it.contains("0–4095") && it.contains("not calibrated") })
    }

    /** Reports describe; tiers advise. Nothing in a report may tell someone what to do. */
    @Test
    fun `observations contain no advice`() {
        val readings = (0 until 30).map {
            reading(it * 5, hr = 50 + it * 3, spo2 = 99 - it / 3, skin = 33f, core = 37f + it * 0.05f, emg = 2000)
        }
        val events = listOf(SessionEvent(AnomalyType.FEVER, AnomalyType.FEVER.label, Severity.MODERATE, t0 + 50_000))
        val text = SessionReportBuilder.build(t0, end(readings), readings, events)!!
            .observations.joinToString(" ").lowercase()
        listOf("you should", "we recommend", "drink", "eat ", "exercise", "diet", "consult", "take ").forEach {
            assertFalse("report contains advice '$it': $text", text.contains(it))
        }
    }

    @Test
    fun `report is deterministic`() {
        val readings = steady(12)
        val a = SessionReportBuilder.build(t0, end(readings), readings, emptyList())
        val b = SessionReportBuilder.build(t0, end(readings), readings.shuffled(java.util.Random(7)), emptyList())
        assertEquals(a, b)
    }

    @Test
    fun `durations read naturally`() {
        assertEquals("less than a minute", SessionReportBuilder.duration(59_000))
        assertEquals("about 1 minute", SessionReportBuilder.duration(60_000))
        assertEquals("about 42 minutes", SessionReportBuilder.duration(42 * 60_000L))
        assertEquals("about 1 hour", SessionReportBuilder.duration(60 * 60_000L))
        assertEquals("about 2 hours 5 minutes", SessionReportBuilder.duration(125 * 60_000L))
    }
}

class RepresentativePointCodecTest {

    @Test
    fun `round trips points including nulls`() {
        val points = listOf(
            RepresentativePoint("Start", 1_700_000_000_000L, heartRate = 71.5f, spo2 = 97f, skinTempC = 33.25f, emgMean = 812f),
            RepresentativePoint("End", 1_700_000_600_000L, motionPeakG = 3.4f, bodyTempC = 37.1f)
        )
        val decoded = RepresentativePointCodec.decode(RepresentativePointCodec.encode(points))
        assertEquals(2, decoded.size)
        assertEquals(points[0].label, decoded[0].label)
        assertEquals(points[0].timestamp, decoded[0].timestamp)
        assertEquals(71.5f, decoded[0].heartRate!!, 0.001f)
        assertEquals(33.25f, decoded[0].skinTempC!!, 0.001f)
        assertNull(decoded[0].motionPeakG)
        assertNull(decoded[1].heartRate)
        assertEquals(3.4f, decoded[1].motionPeakG!!, 0.001f)
        assertNotNull(decoded[1].bodyTempC)
    }

    @Test
    fun `unknown formats decode to nothing rather than garbage`() {
        assertTrue(RepresentativePointCodec.decode("").isEmpty())
        assertTrue(RepresentativePointCodec.decode("v2|Start,1,2,3,4,5,6,7").isEmpty())
        assertTrue(RepresentativePointCodec.decode("{\"json\":true}").isEmpty())
        assertTrue(RepresentativePointCodec.decode("v1|Start,notanumber,,,,,,").isEmpty())
    }
}
