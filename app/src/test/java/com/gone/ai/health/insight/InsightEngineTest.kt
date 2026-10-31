package com.gone.ai.health.insight

import com.gone.ai.health.domain.AnomalyType
import com.gone.ai.health.domain.Severity
import com.gone.ai.health.domain.SleepLog
import com.gone.ai.health.domain.VitalsSample
import com.gone.ai.voice.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class InsightEngineTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    // Thursday 30 Oct 2026, 14:00.
    private val now = LocalDateTime.of(2026, 10, 30, 14, 0).atZone(zone).toInstant().toEpochMilli()
    private fun at(day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private val yesterday = listOf(
        VitalsSample(at(29, 9), heartRate = 70, spo2 = 98),
        VitalsSample(at(29, 12, 30), heartRate = 112, spo2 = 96),
        VitalsSample(at(29, 18), heartRate = 80, spo2 = 97),
        VitalsSample(at(30, 10), heartRate = 150, spo2 = 99) // today: must not count for yesterday
    )

    private fun ask(q: HealthQuery, data: InsightData, compare: InsightData? = null, lang: AppLanguage = AppLanguage.ENGLISH) =
        InsightEngine.answer(q, data, compare, now, zone, lang)

    @Test fun `highest heart rate yesterday names the value and time`() {
        val a = ask(HealthQuery(InsightMetric.HEART_RATE, InsightOp.HIGHEST, TimeRange.Yesterday), InsightData(yesterday))
        assertTrue(a.hasData)
        assertTrue(a.answer, a.answer.contains("112 bpm"))
        assertTrue(a.answer, a.answer.contains("12:30"))
        val chart = a.chart as InsightChart.Line
        assertEquals(3, chart.points.size)
        assertEquals(112f, chart.highlight!!.value)
    }

    @Test fun `average is computed from the readings`() {
        val a = ask(HealthQuery(InsightMetric.HEART_RATE, InsightOp.AVERAGE, TimeRange.Yesterday), InsightData(yesterday))
        // (70 + 112 + 80) / 3 = 87.3
        assertTrue(a.answer, a.answer.contains("87 bpm"))
        assertTrue(a.answer, a.answer.contains("3 readings"))
    }

    @Test fun `no readings says so without inventing numbers`() {
        val a = ask(HealthQuery(InsightMetric.SPO2, InsightOp.TREND, TimeRange.LastNight), InsightData(yesterday))
        assertFalse(a.hasData)
        assertEquals(InsightChart.None, a.chart)
        assertFalse(a.answer.any { it.isDigit() })
    }

    @Test fun `hindi answer uses hindi words and the same number`() {
        val a = ask(HealthQuery(InsightMetric.HEART_RATE, InsightOp.HIGHEST, TimeRange.Yesterday), InsightData(yesterday), lang = AppLanguage.HINDI)
        assertTrue(a.answer, a.answer.contains("112 प्रति मिनट"))
        assertTrue(a.answer, a.answer.contains("दिल की धड़कन"))
    }

    @Test fun `fall count uses only fall alerts in the period`() {
        val alerts = listOf(
            AlertRecord(AnomalyType.FALL_DETECTED, Severity.CRITICAL, at(28, 10)),
            AlertRecord(AnomalyType.FALL_DETECTED, Severity.CRITICAL, at(29, 11)),
            AlertRecord(AnomalyType.HIGH_HEART_RATE, Severity.MODERATE, at(29, 12)),
            AlertRecord(AnomalyType.FALL_DETECTED, Severity.CRITICAL, at(10, 12)) // outside a 7-day window
        )
        val a = ask(HealthQuery(InsightMetric.ALERTS, InsightOp.COUNT, TimeRange.LastDays(7), alertType = AnomalyType.FALL_DETECTED),
            InsightData(alerts = alerts))
        assertTrue(a.answer, a.answer.startsWith("2 possible falls"))
        assertEquals(2, (a.chart as InsightChart.Bars).bars.sumOf { it.value.toInt() })
    }

    @Test fun `no falls is a clear no`() {
        val a = ask(HealthQuery(InsightMetric.ALERTS, InsightOp.COUNT, TimeRange.Today, alertType = AnomalyType.FALL_DETECTED), InsightData())
        assertEquals("No falls were detected today.", a.answer)
    }

    @Test fun `compare today with yesterday`() {
        val today = listOf(VitalsSample(at(30, 9), heartRate = 100), VitalsSample(at(30, 11), heartRate = 104))
        val q = HealthQuery(InsightMetric.HEART_RATE, InsightOp.COMPARE, TimeRange.Today)
        val periods = InsightEngine.periods(q, now, zone)
        assertEquals(TimeRange.Yesterday, periods.compareRange)
        val a = ask(q, InsightData(today), InsightData(yesterday))
        assertTrue(a.answer, a.answer.contains("102 bpm"))
        assertTrue(a.answer, a.answer.contains("87 bpm"))
        assertTrue(a.answer, a.answer.endsWith("higher."))
    }

    @Test fun `sleep average over logged nights`() {
        val logs = listOf(
            SleepLog(LocalDate.of(2026, 10, 28), 23 * 60, 7 * 60, 0, 4),  // 8 h
            SleepLog(LocalDate.of(2026, 10, 29), 0, 6 * 60, 30, 2)         // 5 h 30
        )
        val a = ask(HealthQuery(InsightMetric.SLEEP, InsightOp.AVERAGE, TimeRange.LastDays(7)), InsightData(sleep = logs))
        assertTrue(a.answer, a.answer.contains("6 h 45 min"))
        assertTrue(a.answer, a.answer.contains("3.0 out of 5"))
    }

    @Test fun `trend says rising when the last third is higher`() {
        val rising = (0 until 12).map { VitalsSample(at(30, 1, it * 5), heartRate = 60 + it * 3) }
        val a = ask(HealthQuery(InsightMetric.HEART_RATE, InsightOp.TREND, TimeRange.Today), InsightData(rising))
        assertTrue(a.answer, a.answer.contains("rose overall"))
    }

    @Test fun `long series are thinned for the chart`() {
        val many = (0 until 5_000).map { VitalsSample(at(30, 1) + it * 2_000L, heartRate = 70) }
        val a = ask(HealthQuery(InsightMetric.HEART_RATE, InsightOp.AVERAGE, TimeRange.Today), InsightData(many))
        assertTrue((a.chart as InsightChart.Line).points.size <= 240)
        assertTrue(a.answer, a.answer.contains("5000 readings"))
    }
}
