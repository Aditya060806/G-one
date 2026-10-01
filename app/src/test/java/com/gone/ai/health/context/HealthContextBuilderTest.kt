package com.gone.ai.health.context

import com.gone.ai.data.library.EntryType
import com.gone.ai.data.library.LibraryEntry
import com.gone.ai.health.TestVitals
import com.gone.ai.health.data.AnomalyEventEntity
import com.gone.ai.health.data.ReadingSource
import com.gone.ai.health.data.VitalsReadingEntity
import com.gone.ai.health.data.toEntity
import com.gone.ai.health.domain.AnomalyType
import com.gone.ai.health.domain.Severity
import com.gone.ai.health.domain.VitalsSample
import com.gone.ai.health.session.SessionEvent
import com.gone.ai.health.session.SessionReading
import com.gone.ai.health.session.SessionReportBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class HealthContextBuilderTest {

    private val now = TestVitals.T0 + 3L * 24 * 60 * 60 * 1000
    private val utc = TimeZone.getTimeZone("UTC")
    private val minute = 60_000L
    private val day = 24 * 60 * minute

    private fun build(input: HealthContextBuilder.Input) = HealthContextBuilder.build(input, utc, Locale.US)

    private fun empty() = HealthContextBuilder.Input(now, age = null, latestReading = null, reports = emptyList(), alerts = emptyList(), records = emptyList())

    private fun reading(ago: Long, source: ReadingSource = ReadingSource.BLE) = VitalsReadingEntity(
        patientId = "p1", timestamp = now - ago, heartRate = 72, spo2 = 97, skinTempC = 33.4f,
        motionMagnitudeG = 1.02f, emgMean = 520, source = source.wireName
    )

    private fun report(startedAt: Long, source: ReadingSource = ReadingSource.BLE, events: List<SessionEvent> = emptyList()) =
        SessionReportBuilder.build(
            startedAt = startedAt,
            endedAt = startedAt + 20 * 30_000L,
            readings = (0 until 20).map {
                SessionReading(VitalsSample(startedAt + it * 30_000L, heartRate = 70 + it % 3, spo2 = 97), source)
            },
            events = events
        )!!.toEntity(sessionId = startedAt, patientId = "p1", generatedAt = startedAt)

    private fun alert(ago: Long, acknowledged: Boolean = false) = AnomalyEventEntity(
        patientId = "p1", eventType = AnomalyType.FALL_DETECTED.wireName, severity = Severity.CRITICAL.wireName,
        riskHeat = 0, riskRespiratory = 0, riskCardiovascular = 0, evidenceJson = "{}", templateExplanation = "t",
        status = if (acknowledged) "ACKNOWLEDGED" else "ACTIVE", createdAt = now - ago
    )

    @Test
    fun `with no data every section says so instead of staying silent`() {
        val text = build(empty())
        assertTrue(text.contains("Age: not given."))
        assertTrue(text.contains("Latest reading: none in the last 10 minutes."))
        assertTrue(text.contains("Monitoring session reports: none yet."))
        assertTrue(text.contains("Alerts in the last 7 days: none."))
        assertFalse(text.contains("Medical records"))
    }

    @Test
    fun `a fresh wearable reading is given with its age and the skin temperature caveat`() {
        val text = build(empty().copy(age = 34, latestReading = reading(3 * minute)))
        assertTrue(text.contains("Age: 34."))
        assertTrue(text, text.contains("Latest reading (3 minutes ago, from the wearable): heart rate 72 bpm, SpO2 97%, " +
            "skin temperature 92.1 °F (skin, not body temperature), motion 1.02 g, muscle sensor level 520 (uncalibrated)."))
    }

    @Test
    fun `a stale reading is not presented as the latest`() {
        val text = build(empty().copy(latestReading = reading(25 * minute)))
        assertTrue(text.contains("Latest reading: none in the last 10 minutes."))
        assertFalse(text.contains("72 bpm"))
    }

    @Test
    fun `simulated data is marked wherever it appears`() {
        val text = build(empty().copy(
            latestReading = reading(minute, ReadingSource.SIMULATED),
            reports = listOf(report(now - day, ReadingSource.SIMULATED))
        ))
        assertEquals(2, Regex("SIMULATED – not measured from a person").findAll(text).count())
    }

    @Test
    fun `sessions are newest first, at most three, with their observations and status`() {
        val fall = SessionEvent(AnomalyType.FALL_DETECTED, "Possible fall", Severity.CRITICAL, now - 2 * day)
        val reports = listOf(
            report(now - 1 * day),
            report(now - 2 * day, events = listOf(fall)),
            report(now - 3 * day),
            report(now - 4 * day)
        )
        val text = build(empty().copy(reports = reports))
        val lines = text.lines().filter { it.startsWith("- ") }
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("no alerts"))
        assertTrue(lines[1], lines[1].contains("1 alert, highest: Get help now"))
        reports[0].observationLines.forEach { assertTrue(lines[0].contains(it)) }
    }

    @Test
    fun `only alerts from the last seven days are included, with whether they were seen`() {
        val text = build(empty().copy(alerts = listOf(alert(2 * day, acknowledged = true), alert(9 * day), alert(30 * minute))))
        val alertLines = text.lines().filter { it.startsWith("- ") }
        assertEquals(2, alertLines.size)
        assertTrue(alertLines[0].contains("not yet marked seen"))
        assertTrue(alertLines[1].contains("marked seen"))
        assertTrue(alertLines.all { it.contains("Possible fall (Get help now)") })
    }

    @Test
    fun `records are included in their own words, trimmed at a word`() {
        val long = LibraryEntry(
            id = 1, type = EntryType.MEDICAL_RECORD, title = "Blood test",
            content = ("Haemoglobin 13.2 g/dL reference 12.0-15.5. ").repeat(60), createdAt = now - day
        )
        val text = build(empty().copy(records = listOf(long)))
        val line = text.lines().first { it.startsWith("- \"Blood test\"") }
        assertTrue(line.endsWith(" …"))
        assertTrue(line.length < HealthContextBuilder.MAX_RECORD_CHARS + 60)
        assertTrue(line.contains("Haemoglobin 13.2 g/dL"))
    }

    @Test
    fun `sections come in priority order so trimming drops the least important`() {
        val text = build(empty().copy(
            age = 40,
            latestReading = reading(minute),
            reports = listOf(report(now - day)),
            alerts = listOf(alert(day)),
            records = listOf(LibraryEntry(id = 2, type = EntryType.MEDICAL_RECORD, title = "Letter", content = "Discharged home.", createdAt = now))
        ))
        val order = listOf("Age: 40.", "Latest reading (", "Monitoring session reports", "Alerts in the last 7 days", "Medical records")
            .map { text.indexOf(it) }
        assertTrue(order.all { it >= 0 })
        assertEquals(order.sorted(), order)
    }
}
