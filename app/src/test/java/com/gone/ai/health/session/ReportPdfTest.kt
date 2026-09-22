package com.gone.ai.health.session

import com.gone.ai.health.TestVitals
import com.gone.ai.health.data.AnomalyEventEntity
import com.gone.ai.health.data.ReadingSource
import com.gone.ai.health.data.VitalsReadingEntity
import com.gone.ai.health.data.toEntity
import com.gone.ai.health.domain.AnomalyType
import com.gone.ai.health.domain.Severity
import com.gone.ai.health.domain.VitalsSample
import com.gone.ai.health.session.ReportPdfContent.Tone
import com.gone.ai.health.session.ReportPdfLayout.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class ReportPdfTest {

    /** A monospace stand-in for Paint.measureText. */
    private val measure: (String, Style) -> Float = { text, style -> text.length * style.size * 0.5f }

    private val t0 = TestVitals.T0
    private val utc = TimeZone.getTimeZone("UTC")

    private fun reading(i: Int, source: ReadingSource = ReadingSource.BLE) = VitalsReadingEntity(
        patientId = "p1", timestamp = t0 + i * 30_000L, heartRate = 70 + i % 5, spo2 = 97,
        skinTempC = 33.4f, emgMean = 500 + i, source = source.wireName
    )

    private fun reportFor(readings: List<VitalsReadingEntity>, source: ReadingSource, events: List<SessionEvent> = emptyList(), aiSummary: String? = null) =
        SessionReportBuilder.build(
            startedAt = t0,
            endedAt = t0 + readings.size * 30_000L,
            readings = readings.map {
                SessionReading(VitalsSample(it.timestamp, heartRate = it.heartRate, spo2 = it.spo2, skinTempC = it.skinTempC, emgMean = it.emgMean), source)
            },
            events = events
        )!!.toEntity(sessionId = 1, patientId = "p1", generatedAt = t0).copy(aiSummary = aiSummary)

    private fun event(type: AnomalyType, severity: Severity, at: Long) = AnomalyEventEntity(
        patientId = "p1", eventType = type.wireName, severity = severity.wireName, riskHeat = 0,
        riskRespiratory = 0, riskCardiovascular = 0, evidenceJson = "{}", templateExplanation = "t",
        status = "ACTIVE", createdAt = at
    )

    // ── Content ───────────────────────────────────────────────────────────────

    @Test
    fun `content comes only from the report and its readings`() {
        val readings = (0 until 40).map { reading(it) }
        val events = listOf(event(AnomalyType.FALL_DETECTED, Severity.CRITICAL, t0 + 60_000))
        val report = reportFor(readings, ReadingSource.BLE,
            events = listOf(SessionEvent(AnomalyType.FALL_DETECTED, "Possible fall", Severity.CRITICAL, t0 + 60_000)))
        val content = ReportPdfContent.from(report, events, readings, utc, Locale.US)

        assertEquals(report.observationLines, content.observations)
        assertEquals(Tone.ALERT, content.status.first().tone)
        assertTrue(content.status.first().text.startsWith("1 alert"))
        assertEquals(listOf("Heart rate", "SpO₂", "Skin temperature", "Muscle activity (EMG level)"), content.charts.map { it.title })
        // T0 is 22:13:20 UTC; the fall came a minute later.
        assertEquals("22:14", content.events.single().time)
        assertEquals(AnomalyType.FALL_DETECTED.label, content.events.single().label)
        assertEquals(5, content.points.size)
        assertNull(content.aiSummary)
    }

    @Test
    fun `a simulated session says so in the status`() {
        val readings = (0 until 20).map { reading(it, ReadingSource.SIMULATED) }
        val content = ReportPdfContent.from(reportFor(readings, ReadingSource.SIMULATED), emptyList(), readings, utc, Locale.US)
        assertTrue(content.status.any { it.text.contains("simulated vitals") && it.tone == Tone.WARN })
        assertEquals("No alerts were raised during this session.", content.status.first().text)
    }

    @Test
    fun `a channel with fewer than two readings gets no chart`() {
        val readings = (0 until 20).map { reading(it).copy(skinTempC = if (it == 3) 33f else null) }
        val content = ReportPdfContent.from(reportFor(readings, ReadingSource.BLE), emptyList(), readings, utc, Locale.US)
        assertFalse(content.charts.any { it.title == "Skin temperature" })
    }

    @Test
    fun `thinning a long series keeps the ends and the extremes`() {
        val points = (0 until 1_000).map { it.toLong() to (if (it == 517) 190f else if (it == 733) 40f else 70f) }
        val kept = ReportPdfContent.strideSample(points, 50)
        assertTrue(kept.size <= 54)
        assertEquals(0L, kept.first().first)
        assertEquals(999L, kept.last().first)
        assertTrue(kept.any { it.second == 190f })
        assertTrue(kept.any { it.second == 40f })
        assertEquals(kept.map { it.first }.sorted(), kept.map { it.first })
    }

    @Test
    fun `the model summary is included and labelled when present`() {
        val readings = (0 until 20).map { reading(it) }
        val content = ReportPdfContent.from(reportFor(readings, ReadingSource.BLE, aiSummary = "A calm paragraph."), emptyList(), readings, utc, Locale.US)
        val pages = ReportPdfLayout.layout(content, measure)
        val texts = pages.flatMap { it.items }.filterIsInstance<ReportPdfLayout.Text>().map { it.text }
        assertTrue(texts.contains("Plain-language summary"))
        assertTrue(texts.contains("A calm paragraph."))
        assertTrue(texts.any { it.contains("not a diagnosis") })
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private fun longContent() = ReportPdfContent(
        title = "G-one monitoring report",
        subtitle = "Thu 17 Sep 2026, 10:00 to 12:00 · about 2 hours · 1,440 readings",
        status = listOf(ReportPdfContent.StatusLine("No alerts were raised during this session.", Tone.OK)),
        observations = (1..60).map { "Observation $it: heart rate stayed between 62 and 88 bpm while the wearer was mostly still." },
        points = (1..5).map { ReportPdfContent.PointRow("P$it", "10:0$it", "HR 7$it · SpO₂ 97%") },
        charts = (1..5).map { ReportPdfContent.ChartSeries("Chart $it", "bpm", listOf(0L, 1L), listOf(60f, 70f), 0) },
        events = emptyList(),
        aiSummary = null,
        disclaimer = ReportPdfContent.DISCLAIMER
    )

    @Test
    fun `a long report spans several pages, each numbered n of total`() {
        val pages = ReportPdfLayout.layout(longContent(), measure)
        assertTrue(pages.size > 1)
        pages.forEachIndexed { index, page ->
            val footer = page.items.filterIsInstance<ReportPdfLayout.Text>().last()
            assertEquals("G-one monitoring report · Page ${index + 1} of ${pages.size}", footer.text)
        }
    }

    @Test
    fun `nothing runs past the bottom or the right edge, and charts never split`() {
        val bottom = ReportPdfLayout.PAGE_HEIGHT - ReportPdfLayout.MARGIN - ReportPdfLayout.FOOTER_SPACE
        for (page in ReportPdfLayout.layout(longContent(), measure)) {
            val body = page.items.dropLast(1)
            body.forEach { item ->
                when (item) {
                    is ReportPdfLayout.Text -> {
                        assertTrue("below bottom: ${item.text}", item.y <= bottom)
                        assertTrue("too wide: ${item.text}", item.x + measure(item.text, item.style) <= ReportPdfLayout.PAGE_WIDTH - ReportPdfLayout.MARGIN + 0.01f)
                    }
                    is ReportPdfLayout.Chart -> assertTrue(item.y + item.height <= bottom)
                    is ReportPdfLayout.Box -> assertTrue(item.y + item.height <= bottom)
                }
            }
        }
    }

    @Test
    fun `every observation appears, in order`() {
        val content = longContent()
        val texts = ReportPdfLayout.layout(content, measure).flatMap { it.items }.filterIsInstance<ReportPdfLayout.Text>().joinToString(" ") { it.text }
        var from = 0
        content.observations.forEach { observation ->
            val start = observation.substringBefore(":")
            val at = texts.indexOf(start, from)
            assertTrue("missing or out of order: $start", at >= 0)
            from = at
        }
    }

    @Test
    fun `wrapping keeps every word and splits a word too long for the line`() {
        val lines = ReportPdfLayout.wrap("one two three four five six seven", Style.BODY, 60f, measure)
        assertTrue(lines.all { measure(it, Style.BODY) <= 60f })
        assertEquals("one two three four five six seven", lines.joinToString(" "))

        val long = ReportPdfLayout.wrap("x".repeat(40), Style.BODY, 50f, measure)
        assertTrue(long.all { measure(it, Style.BODY) <= 50f })
        assertEquals("x".repeat(40), long.joinToString(""))
    }
}
