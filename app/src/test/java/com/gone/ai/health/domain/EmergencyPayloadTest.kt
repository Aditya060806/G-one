package com.gone.ai.health.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyPayloadTest {

    private val t0 = 1700000000000L

    private fun samplePayload(
        name: String? = "Jane Doe",
        age: Int? = 32,
        bloodGroup: String? = "O+",
        allergies: String? = "Penicillin",
        chronicConditions: String? = "Asthma",
        medications: String? = "Albuterol",
        implantedDevices: String? = "None",
        emergencyContact: String? = "+1234567890",
        heartRate: Int? = 72,
        spo2: Int? = 98,
        bodyTempC: Float? = 36.6f,
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
    fun `toCompactJson emits all non-null fields`() {
        val payload = samplePayload()
        val json = payload.toCompactJson()

        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
        assertTrue(json.contains("\"n\":\"Jane Doe\""))
        assertTrue(json.contains("\"ag\":32"))
        assertTrue(json.contains("\"bg\":\"O+\""))
        assertTrue(json.contains("\"al\":\"Penicillin\""))
        assertTrue(json.contains("\"cc\":\"Asthma\""))
        assertTrue(json.contains("\"md\":\"Albuterol\""))
        assertTrue(json.contains("\"im\":\"None\""))
        assertTrue(json.contains("\"ec\":\"+1234567890\""))
        assertTrue(json.contains("\"hr\":72"))
        assertTrue(json.contains("\"sp\":98"))
        assertTrue(json.contains("\"tm\":36.6"))
        assertTrue(json.contains("\"mo\":\"normal\""))
        assertTrue(json.contains("\"rs\":\"normal\""))
        assertTrue(json.contains("\"ts\":$t0"))
        assertTrue(json.contains("\"ss\":$t0"))
    }

    @Test
    fun `toCompactJson omits null fields completely`() {
        val payload = samplePayload(
            allergies = null,
            chronicConditions = null,
            medications = null,
            implantedDevices = null,
            heartRate = null,
            spo2 = null,
            bodyTempC = null,
            motionStatus = null,
            riskStatus = null,
            readingTimestamp = null
        )
        val json = payload.toCompactJson()

        assertFalse("null should never appear as a literal in JSON", json.contains("null"))
        assertFalse(json.contains("\"al\":"))
        assertFalse(json.contains("\"cc\":"))
        assertFalse(json.contains("\"md\":"))
        assertFalse(json.contains("\"im\":"))
        assertFalse(json.contains("\"hr\":"))
        assertFalse(json.contains("\"sp\":"))
        assertFalse(json.contains("\"tm\":"))
        assertFalse(json.contains("\"mo\":"))
        assertFalse(json.contains("\"rs\":"))
        assertFalse(json.contains("\"ts\":"))

        // Kept fields must still be present
        assertTrue(json.contains("\"n\":\"Jane Doe\""))
        assertTrue(json.contains("\"bg\":\"O+\""))
    }

    @Test
    fun `staleness returns NO_DATA when readingTimestamp is null`() {
        val payload = samplePayload(readingTimestamp = null)
        assertEquals(Staleness.NO_DATA, payload.staleness(t0 + 1000L))
    }

    @Test
    fun `staleness returns FRESH when age is under 1 hour`() {
        val payload = samplePayload(readingTimestamp = t0)
        // 59 minutes after reading
        val now = t0 + 59 * 60 * 1000L
        assertEquals(Staleness.FRESH, payload.staleness(now))
    }

    @Test
    fun `staleness returns STALE when age is between 1 and 24 hours`() {
        val payload = samplePayload(readingTimestamp = t0)
        // 2 hours after reading
        val now = t0 + 2 * 60 * 60 * 1000L
        assertEquals(Staleness.STALE, payload.staleness(now))

        // 23 hours 59 minutes after reading
        val almostDay = t0 + (23 * 60 + 59) * 60 * 1000L
        assertEquals(Staleness.STALE, payload.staleness(almostDay))
    }

    @Test
    fun `staleness returns VERY_STALE when age is 24 hours or older`() {
        val payload = samplePayload(readingTimestamp = t0)
        // Exactly 24 hours
        val dayLater = t0 + 24 * 60 * 60 * 1000L
        assertEquals(Staleness.VERY_STALE, payload.staleness(dayLater))

        // 3 days later
        val threeDays = t0 + 72 * 60 * 60 * 1000L
        assertEquals(Staleness.VERY_STALE, payload.staleness(threeDays))
    }

    @Test
    fun `toOfflineText formats readable emergency record`() {
        val payload = samplePayload(
            name = "Aditya Pandey",
            age = 24,
            bloodGroup = "B+",
            allergies = "Penicillin",
            chronicConditions = "Asthma",
            medications = "Albuterol",
            implantedDevices = "Pacemaker",
            emergencyContact = "+919876543210",
            heartRate = 74,
            spo2 = 98,
            bodyTempC = 36.8f,
            motionStatus = "normal"
        )
        val text = payload.toOfflineText()
        assertTrue(text.contains("🚨 EMERGENCY MEDICAL ID — G-ONE"))
        assertTrue(text.contains("Patient: Aditya Pandey, 24 yrs"))
        assertTrue(text.contains("Blood Group: B+"))
        assertTrue(text.contains("⚠️ Allergies: Penicillin"))
        assertTrue(text.contains("Conditions: Asthma"))
        assertTrue(text.contains("Medications: Albuterol"))
        assertTrue(text.contains("⚡ Implants: Pacemaker"))
        assertTrue(text.contains("📞 Emergency Contact: +919876543210"))
        assertTrue(text.contains("Last Vitals: 74 BPM | SpO2 98% | 98.2°F | normal"))
    }

    @Test
    fun `toCompactOfflineText produces concise format under 140 bytes`() {
        val payload = samplePayload(
            name = "Aditya",
            age = 24,
            bloodGroup = "B+",
            allergies = "Penicillin",
            chronicConditions = "Asthma",
            medications = "Albuterol",
            implantedDevices = "None",
            emergencyContact = "+919876543210",
            heartRate = 74,
            spo2 = 98
        )
        val compact = payload.toCompactOfflineText()
        assertTrue(compact.contains("🚨EMERGENCY ID: Aditya (24) [B+]"))
        assertTrue(compact.contains("Allergies: Penicillin"))
        assertTrue(compact.contains("ICE: +919876543210"))
        assertTrue("Compact text should be within NTAG213 limit (~144 bytes)", compact.toByteArray(Charsets.UTF_8).size <= 144)
    }

    @Test
    fun `toVCard formats valid RFC 6350 contact card`() {
        val payload = samplePayload(
            name = "Aditya Pandey",
            bloodGroup = "B+",
            allergies = "Penicillin",
            emergencyContact = "+919876543210",
            heartRate = 74,
            spo2 = 98
        )
        val vcard = payload.toVCard()
        assertTrue(vcard.startsWith("BEGIN:VCARD"))
        assertTrue(vcard.contains("FN:ICE - Aditya Pandey (B+)"))
        assertTrue(vcard.contains("TEL;TYPE=CELL,VOICE,PREF:+919876543210"))
        assertTrue(vcard.contains("NOTE:"))
        assertTrue(vcard.contains("ALLERGIES: Penicillin"))
        assertTrue(vcard.endsWith("END:VCARD"))
    }
}
