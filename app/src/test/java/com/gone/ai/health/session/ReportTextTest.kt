package com.gone.ai.health.session

import com.gone.ai.health.TestVitals
import com.gone.ai.health.data.ReadingSource
import com.gone.ai.health.data.toEntity
import com.gone.ai.health.domain.AnomalyType
import com.gone.ai.health.domain.Severity
import com.gone.ai.health.domain.VitalsSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ReportTextTest {

    private val t0 = TestVitals.T0
    private val utc = TimeZone.getTimeZone("UTC")

    private fun report(
        source: ReadingSource = ReadingSource.BLE,
        events: List<SessionEvent> = emptyList(),
        aiSummary: String? = null
    ) = SessionReportBuilder.build(
        startedAt = t0,
        endedAt = t0 + 10 * 60_000L,
        readings = (0 until 20).map {
            SessionReading(
                VitalsSample(t0 + it * 30_000L, heartRate = 70 + it % 3, spo2 = 97, skinTempC = 33.4f, emgMean = 500),
                source
            )
        },
        events = events
    )!!.toEntity(sessionId = 1, patientId = "p1", generatedAt = t0 + 10 * 60_000L).copy(aiSummary = aiSummary)

    @Test
    fun `contains the status, every observation and the five points`() {
        val events = listOf(SessionEvent(AnomalyType.FALL_DETECTED, "Possible fall", Severity.CRITICAL, t0 + 60_000))
        val r = report(events = events)
        val text = ReportText.plain(r, utc)

        assertTrue(text, text.contains("Status: CRITICAL — Get help now"))
        r.observationLines.forEach { assertTrue("missing: $it", text.contains(it)) }
        listOf("Start", "25%", "50%", "75%", "End").forEach { assertTrue("missing point $it", text.contains("- $it (")) }
        assertTrue(text.contains("skin 33.4 °C"))
    }

    @Test
    fun `says when no alert was raised`() {
        assertTrue(ReportText.plain(report(), utc).contains("Status: no alerts raised"))
    }

    @Test
    fun `labels a model summary as reworded and keeps the observations`() {
        val text = ReportText.plain(report(aiSummary = "A calm paragraph."), utc)
        assertTrue(text.contains("reworded on this phone by the on-device model"))
        assertTrue(text.contains("A calm paragraph."))
        assertTrue(text.contains("What was recorded"))
    }

    @Test
    fun `ends with the not-a-diagnosis disclaimer`() {
        assertTrue(ReportText.plain(report(), utc).trimEnd().endsWith("not calibrated measurements."))
    }

    @Test
    fun `simulated reports say so`() {
        assertTrue(ReportText.plain(report(ReadingSource.SIMULATED), utc).contains("simulated vitals"))
    }
}

class ReportSummaryPromptTest {

    private val observations = listOf(
        "Recorded for about 42 minutes: 504 readings from the wearable.",
        "Heart rate ranged from 61 to 104 beats a minute, averaging 78.",
        "Skin temperature ranged from 32.8 to 34.1 °C. Skin runs cooler than the body's core, so this is not a fever reading.",
        "The muscle sensor averaged 820 and peaked at 3050 on its 0–4095 scale. These levels are not calibrated to this person.",
        "No alerts were raised during the session."
    )

    @Test
    fun `accepts a faithful rewording`() {
        val text = "Over about 42 minutes the wearable sent 504 readings. Heart rate stayed between 61 and 104, " +
            "averaging 78, and skin temperature sat between 32.8 and 34.1 degrees. No alerts were raised."
        assertNotNull(ReportSummaryPrompt.validate(text, observations))
    }

    @Test
    fun `rejects a number that is not in the observations`() {
        val text = "Over about 42 minutes the heart rate peaked at 131 and averaged 78, with no alerts raised."
        assertNull(ReportSummaryPrompt.validate(text, observations))
    }

    @Test
    fun `rejects a rounded decimal`() {
        val text = "Skin temperature was around 33.0 degrees over the 42 minutes, and no alerts were raised."
        assertNull(ReportSummaryPrompt.validate(text, observations))
    }

    @Test
    fun `rejects advice and diagnosis`() {
        listOf(
            "Over 42 minutes the heart rate averaged 78. You should drink more water and rest.",
            "Over 42 minutes the heart rate averaged 78, which suggests a diagnosis of stress.",
            "Over 42 minutes things looked fine; we recommend light exercise tomorrow."
        ).forEach { assertNull(it, ReportSummaryPrompt.validate(it, observations)) }
    }

    @Test
    fun `rejects refusals and runaway output`() {
        assertNull(ReportSummaryPrompt.validate("As an AI language model I cannot summarise this.", observations))
        assertNull(ReportSummaryPrompt.validate("Too short.", observations))
        assertNull(ReportSummaryPrompt.validate("x".repeat(901), observations))
    }

    @Test
    fun `a simulated session must be described as simulated`() {
        val simulated = listOf("Recorded for about 10 minutes: 120 readings of simulated vitals. These are not measurements from a person.")
        assertNull(ReportSummaryPrompt.validate("Over about 10 minutes, 120 readings were taken and all looked settled.", simulated))
        assertNotNull(ReportSummaryPrompt.validate("Over about 10 minutes, 120 simulated readings were produced for a demonstration.", simulated))
    }

    @Test
    fun `the prompt carries every observation and forbids advice`() {
        val prompt = ReportSummaryPrompt.userPrompt(observations)
        observations.forEach { assertTrue(prompt.contains(it)) }
        assertTrue(ReportSummaryPrompt.SYSTEM_PROMPT.contains("Do NOT give advice"))
        assertFalse(ReportSummaryPrompt.SYSTEM_PROMPT.contains("{"))
        assertEquals(1, ReportSummaryPrompt.SYSTEM_PROMPT.split("STRICT RULES").size - 1)
    }
}
