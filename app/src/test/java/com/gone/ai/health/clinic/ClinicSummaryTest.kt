package com.gone.ai.health.clinic

import com.gone.ai.health.domain.AnomalyType
import com.gone.ai.health.domain.Severity
import com.gone.ai.health.domain.StressLog
import com.gone.ai.health.domain.VitalsSample
import com.gone.ai.health.insight.AlertRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ClinicSummaryTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val start = 1_793_000_000_000L
    private val readings = (0 until 100).map {
        VitalsSample(start + it * 5_000L, heartRate = 60 + it % 40, spo2 = if (it < 10) 90 else 97, skinTempC = 33.5f, motionMagnitudeG = if (it == 50) 6.4f else 1f)
    }

    private fun input(medications: String? = "Metformin 500 mg", allergies: String? = "Penicillin") = ClinicInput(
        name = "Asha", age = 62, bloodGroup = "B+", allergies = allergies, conditions = "Type 2 diabetes",
        medications = medications, implants = null,
        readings = readings,
        alerts = listOf(
            AlertRecord(AnomalyType.FALL_DETECTED, Severity.CRITICAL, start + 250_000),
            AlertRecord(AnomalyType.LOW_SPO2, Severity.MODERATE, start + 20_000),
            AlertRecord(AnomalyType.LOW_SPO2, Severity.MODERATE, start + 40_000)
        ),
        sleep = emptyList(),
        stress = listOf(StressLog(LocalDate.of(2026, 10, 28), 4)),
        from = start, to = start + 500_000
    )

    @Test fun `figures come from the readings`() {
        val text = ClinicSummaryBuilder.build(input(), ClinicInclude(), start, zone).toText()
        assertTrue(text, text.contains("min 60 / avg 78 / max 99 bpm"))
        assertTrue(text, text.contains("10.0% of readings <92"))
        assertTrue(text, text.contains("Max impact: 6.4 g"))
        assertTrue(text, text.contains("Possible fall: 1x"))
        assertTrue(text, text.contains("(critical)"))
        assertTrue(text, text.contains("Low blood oxygen: 2x"))
        assertTrue(text, text.contains(ClinicSummaryBuilder.NOTE))
    }

    @Test fun `the wearer can leave out medications`() {
        val text = ClinicSummaryBuilder.build(input(), ClinicInclude(medications = false), start, zone).toText()
        assertFalse(text.contains("Metformin"))
        assertTrue(text.contains("Penicillin"))
    }

    @Test fun `qr text stays within the limit`() {
        val long = "x".repeat(2_000)
        val summary = ClinicSummaryBuilder.build(input(medications = long, allergies = long), ClinicInclude(), start, zone)
        val qr = ClinicSummaryBuilder.toQrText(summary)
        assertTrue(qr.toByteArray(Charsets.UTF_8).size <= ClinicSummaryBuilder.QR_MAX_BYTES)
        assertTrue(qr.contains("Heart rate"))
        assertTrue(qr.endsWith(ClinicSummaryBuilder.NOTE))
    }

    @Test fun `no readings is stated, not filled in`() {
        val summary = ClinicSummaryBuilder.build(input().copy(readings = emptyList()), ClinicInclude(), start, zone)
        assertTrue(summary.toText().contains("Readings: none in this period"))
    }
}
