package com.gone.ai.health.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyHtmlRendererTest {

    private val t0 = 1700000000000L

    private fun samplePayload(
        name: String? = "Alice Smith",
        age: Int? = 29,
        bloodGroup: String? = "AB-",
        allergies: String? = "Latex, Sulfa",
        chronicConditions: String? = "Type 1 Diabetes",
        medications: String? = "Insulin glargine",
        implantedDevices: String? = "Insulin Pump",
        emergencyContact: String? = "+919876543210",
        heartRate: Int? = 84,
        spo2: Int? = 97,
        bodyTempC: Float? = 37.1f,
        motionStatus: String? = "normal",
        riskStatus: String? = "normal",
        readingTimestamp: Long? = t0,
        snapshotTimestamp: Long = t0
    ) = EmergencyPayload(
        name = name,
        age = age,
        bloodGroup = bloodGroup,
        allergies = allergies,
        chronicConditions = chronicConditions,
        medications = medications,
        implantedDevices = implantedDevices,
        emergencyContact = emergencyContact,
        heartRate = heartRate,
        spo2 = spo2,
        bodyTempC = bodyTempC,
        motionStatus = motionStatus,
        riskStatus = riskStatus,
        readingTimestamp = readingTimestamp,
        snapshotTimestamp = snapshotTimestamp
    )

    @Test
    fun `renders full HTML with all patient and clinical fields`() {
        val payload = samplePayload()
        val html = EmergencyHtmlRenderer.render(payload, nowMillis = t0 + 10 * 60 * 1000L) // 10m later = FRESH

        assertTrue(html.contains("Alice Smith"))
        assertTrue(html.contains("Age: 29"))
        assertTrue(html.contains("AB-"))
        assertTrue(html.contains("Latex, Sulfa"))
        assertTrue(html.contains("Type 1 Diabetes"))
        assertTrue(html.contains("Insulin glargine"))
        assertTrue(html.contains("Insulin Pump"))
        assertTrue(html.contains("href=\"tel:+919876543210\""))
        assertTrue(html.contains("href=\"tel:112\""))
        assertTrue(html.contains("84 BPM"))
        assertTrue(html.contains("97%"))
        assertTrue(html.contains("98.8 °F"))
    }

    @Test
    fun `enforces offline contract with zero external HTTP or HTTPS loads`() {
        val payload = samplePayload()
        val html = EmergencyHtmlRenderer.render(payload, nowMillis = t0)

        // No external stylesheets, scripts, or images
        assertFalse("Must not load external scripts", html.contains("src=\"http"))
        assertFalse("Must not load external stylesheets", html.contains("href=\"http"))
        assertFalse("Must not load external images", html.contains("<img"))
    }

    @Test
    fun `omits opted out fields cleanly`() {
        val payload = samplePayload(
            allergies = null,
            medications = null,
            chronicConditions = null,
            implantedDevices = null,
            heartRate = null,
            spo2 = null,
            bodyTempC = null,
            motionStatus = null,
            riskStatus = null,
            readingTimestamp = null
        )
        val html = EmergencyHtmlRenderer.render(payload, nowMillis = t0)

        assertFalse(html.contains("Allergies"))
        assertFalse(html.contains("Current Medications"))
        assertFalse(html.contains("Chronic Conditions"))
        assertFalse(html.contains("Implanted Devices"))
        assertFalse(html.contains("Last Known Vitals"))
        // Blood group and name should still be present
        assertTrue(html.contains("Alice Smith"))
        assertTrue(html.contains("AB-"))
    }

    @Test
    fun `fresh payload displays no staleness banner`() {
        val payload = samplePayload(readingTimestamp = t0)
        val html = EmergencyHtmlRenderer.render(payload, nowMillis = t0 + 30 * 60 * 1000L) // 30m = FRESH

        assertFalse(html.contains("banner amber"))
        assertFalse(html.contains("banner red"))
    }

    @Test
    fun `stale payload displays amber warning banner`() {
        val payload = samplePayload(readingTimestamp = t0)
        val html = EmergencyHtmlRenderer.render(payload, nowMillis = t0 + 2 * 60 * 60 * 1000L) // 2h = STALE

        assertTrue(html.contains("banner amber"))
        assertTrue(html.contains("Vitals may be outdated"))
        assertFalse(html.contains("banner red"))
    }

    @Test
    fun `very stale payload displays red alert banner`() {
        val payload = samplePayload(readingTimestamp = t0)
        val html = EmergencyHtmlRenderer.render(payload, nowMillis = t0 + 48 * 60 * 60 * 1000L) // 48h = VERY_STALE

        assertTrue(html.contains("banner red"))
        assertTrue(html.contains("Snapshot is old"))
        assertFalse(html.contains("banner amber"))
    }

    @Test
    fun `fall detected status displays prominent warning in vitals`() {
        val payload = samplePayload(motionStatus = "fall_detected")
        val html = EmergencyHtmlRenderer.render(payload, nowMillis = t0)

        assertTrue(html.contains("Fall detected"))
        assertTrue(html.contains("vital-value critical"))
    }
}
