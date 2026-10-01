package com.gone.ai.health.sos

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

class SosPolicyTest {

    private fun sample(hr: Int? = null, spo2: Int? = null, motion: Float? = null) =
        VitalsSample(timestamp = 0, heartRate = hr, spo2 = spo2, motionMagnitudeG = motion)

    private fun reason(type: AnomalyType, severity: Severity, latest: VitalsSample? = null, fallImpactG: Float? = null) =
        SosPolicy.reasonFor(type, severity, latest, fallImpactG)

    @Test
    fun `critically low blood oxygen sends, with the reading`() {
        assertEquals("Blood oxygen 86 %", reason(AnomalyType.LOW_SPO2, Severity.CRITICAL, sample(spo2 = 86)))
    }

    @Test
    fun `very high and very low heart rates send, with the reading`() {
        assertEquals("Heart rate 158 beats a minute, very high", reason(AnomalyType.HIGH_HEART_RATE, Severity.CRITICAL, sample(hr = 158)))
        assertEquals("Heart rate 34 beats a minute, very low", reason(AnomalyType.LOW_HEART_RATE, Severity.CRITICAL, sample(hr = 34)))
    }

    @Test
    fun `confirmed moderate heart rate anomalies also send`() {
        assertNotNull(reason(AnomalyType.HIGH_HEART_RATE, Severity.MODERATE, sample(hr = 132)))
        assertNotNull(reason(AnomalyType.LOW_HEART_RATE, Severity.MODERATE, sample(hr = 43)))
    }

    @Test
    fun `stillness does not bypass the high impact requirement`() {
        assertNull(reason(AnomalyType.FALL_DETECTED, Severity.CRITICAL, sample(motion = 1.0f)))
    }

    @Test
    fun `unconfirmed impacts do not send and confirmed severe falls do`() {
        assertNull(reason(AnomalyType.FALL_DETECTED, Severity.MODERATE, sample(motion = 5.4f), 5.4f))
        assertNull(reason(AnomalyType.FALL_DETECTED, Severity.CRITICAL, sample(motion = 1.0f), 2.9f))
        assertEquals(
            "Very hard impact (9.5 g); a possible fall",
            reason(AnomalyType.FALL_DETECTED, Severity.CRITICAL, sample(motion = 1.0f), 9.47f)
        )
        assertNotNull(reason(AnomalyType.FALL_DETECTED, Severity.CRITICAL, sample(motion = 1.0f), SosPolicy.SEVERE_IMPACT_G))
    }

    @Test
    fun `every confirmed non motion anomaly sends at every severity`() {
        for (type in AnomalyType.entries.filter { it != AnomalyType.FALL_DETECTED }) {
            for (severity in Severity.entries) assertNotNull("$type $severity", reason(type, severity))
        }
    }

    @Test
    fun `recorded impact must be finite and at least five g for a confirmed fall`() {
        for (severity in Severity.entries) {
            for (g in listOf<Float?>(null, 1f, 2.5f, 4.99f, Float.NaN, Float.POSITIVE_INFINITY)) {
                assertNull(reason(AnomalyType.FALL_DETECTED, severity, sample(motion = 1.0f), g))
            }
            if (severity == Severity.CRITICAL) {
                assertNotNull(reason(AnomalyType.FALL_DETECTED, severity, sample(motion = 1.0f), 5f))
            } else {
                assertNull(reason(AnomalyType.FALL_DETECTED, severity, sample(motion = 1.0f), 5f))
            }
        }
        assertEquals(com.gone.ai.health.domain.AnomalyThresholds.DEFAULT.fallImpactG, SosPolicy.SEVERE_IMPACT_G)
    }

}

class SosMessageTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val at = 1_789_700_000_000L   // 02:53 UTC

    @Test
    fun `says who, what and when, and that it was sent automatically`() {
        val text = SosMessage.compose("Aditya", listOf("Blood oxygen 86 %"), at, zone = utc)
        assertEquals(
            "SOS from G-one: Aditya may need help. Blood oxygen 86 %, at 02:53. " +
                "Sent automatically by their health wearable. Please call them or check on them now.",
            text
        )
    }

    @Test
    fun `alerts that came together are listed once each`() {
        val text = SosMessage.compose("A", listOf("Heart rate very high", "Blood oxygen 86 %", "Heart rate very high"), at, zone = utc)
        assertTrue(text.contains("Heart rate very high; Blood oxygen 86 %,"))
    }

    @Test
    fun `with no name it still reads properly`() {
        assertTrue(SosMessage.compose(null, listOf("x"), at, zone = utc).startsWith("SOS from G-one: The wearer may need help."))
        assertTrue(SosMessage.compose("  ", listOf("x"), at, zone = utc).startsWith("SOS from G-one: The wearer may need help."))
    }

    @Test
    fun `a test says plainly that nothing has happened`() {
        val text = SosMessage.compose("Aditya", emptyList(), at, test = true, zone = utc)
        assertTrue(text.startsWith("TEST from G-one"))
        assertTrue(text.contains("Nothing has happened"))
    }
}

class SosNumbersTest {

    @Test
    fun `spaces, dashes and brackets are dropped, a leading plus kept`() {
        assertEquals("+919876543210", SosNumbers.normalize("+91 98765 43210"))
        assertEquals("09876543210", SosNumbers.normalize("098-765-43210"))
        assertEquals("02012345678", SosNumbers.normalize("(020) 1234.5678"))
    }

    @Test
    fun `what cannot be a phone number is refused`() {
        assertNull(SosNumbers.normalize(null))
        assertNull(SosNumbers.normalize(""))
        assertNull(SosNumbers.normalize("Mum"))
        assertNull(SosNumbers.normalize("98765abc"))
        assertNull(SosNumbers.normalize("12"))
        assertNull(SosNumbers.normalize("1234567890123456"))
    }

    @Test
    fun `short numbers are allowed`() {
        assertEquals("112", SosNumbers.normalize("112"))
        assertFalse(SosNumbers.normalize("112").isNullOrEmpty())
    }
}
