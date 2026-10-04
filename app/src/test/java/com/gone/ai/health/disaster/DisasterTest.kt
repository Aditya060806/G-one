package com.gone.ai.health.disaster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Real SACHET documents saved on 2 October 2026 (the feed marks itself public domain). */
private fun resource(name: String): String {
    val candidates = listOf(File("src/test/resources/disaster/$name"), File("app/src/test/resources/disaster/$name"))
    return (candidates.firstOrNull { it.isFile } ?: throw AssertionError("missing test file $name")).readText()
}

class CapParserTest {

    @Test
    fun `reads every item of a real state feed`() {
        val items = CapParser.feed(resource("rss_punjab_2026-10-02.xml"))
        assertEquals(10, items.size)
        items.forEach {
            assertTrue(it.identifier.isNotBlank())
            assertTrue(it.link.startsWith("https://sachet.ndma.gov.in/"))
            assertNotNull("pubDate parsed for ${it.identifier}", it.publishedAt)
        }
        assertTrue(items.any { it.title.contains("Punjab SDMA") })
    }

    @Test
    fun `reads a real alert's fields, times and district`() {
        val alert = CapParser.alert(resource("cap_tamilnadu_rain.xml"))!!
        val info = alert.english!!
        assertEquals("Actual", alert.status)
        assertEquals("Moderate Rain", info.event)
        assertEquals("Moderate", info.severity)
        assertEquals("Expected", info.urgency)
        assertTrue(info.areaDesc.contains("Madurai"))
        assertEquals(java.time.OffsetDateTime.parse("2026-10-03T01:00:00+05:30").toInstant().toEpochMilli(), alert.expires)
        assertTrue(alert.isActive(java.time.OffsetDateTime.parse("2026-10-02T23:00:00+05:30").toInstant().toEpochMilli()))
        assertFalse("expired", alert.isActive(java.time.OffsetDateTime.parse("2026-10-03T02:00:00+05:30").toInstant().toEpochMilli()))
    }

    @Test
    fun `keeps both languages of a bilingual alert`() {
        val alert = CapParser.alert(resource("cap_punjab_thunderstorm_bilingual.xml"))!!
        assertEquals(2, alert.infos.size)
        assertTrue(alert.english!!.language.startsWith("en"))
        assertNotNull("the Punjabi headline is kept", alert.localHeadline)
        assertEquals("Severe", alert.english!!.severity)
    }

    @Test
    fun `junk, a different document, or a document with a DTD gives nothing`() {
        assertNull(CapParser.alert("not xml"))
        assertNull(CapParser.alert("<rss><channel/></rss>"))
        assertTrue(CapParser.feed("<html/>").isEmpty())
        val withEntity = """<?xml version="1.0"?><!DOCTYPE a [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <alert xmlns="urn:oasis:names:tc:emergency:cap:1.2"><identifier>&x;</identifier></alert>"""
        assertNull("documents with a DTD are refused", CapParser.alert(withEntity))
    }

    @Test
    fun `a cancellation or an exercise is never active`() {
        fun doc(status: String, type: String) = """<alert xmlns="urn:oasis:names:tc:emergency:cap:1.2">
            <identifier>X1</identifier><status>$status</status><msgType>$type</msgType>
            <info><language>en-IN</language><event>Heat Wave</event></info></alert>"""
        assertTrue(CapParser.alert(doc("Actual", "Alert"))!!.isActive(0))
        assertFalse(CapParser.alert(doc("Exercise", "Alert"))!!.isActive(0))
        assertFalse(CapParser.alert(doc("Actual", "Cancel"))!!.isActive(0))
    }
}

class DisasterRulesTest {

    @Test
    fun `state names map to SACHET's feed names`() {
        assertEquals("https://sachet.ndma.gov.in/cap_public_website/rss/rss_punjab.xml", SachetFeeds.urlFor("Punjab"))
        assertEquals("https://sachet.ndma.gov.in/cap_public_website/rss/rss_tamil.xml", SachetFeeds.urlFor("Tamil Nadu"))
        assertEquals("https://sachet.ndma.gov.in/cap_public_website/rss/rss_uttar.xml", SachetFeeds.urlFor("Uttar Pradesh"))
        assertEquals("https://sachet.ndma.gov.in/cap_public_website/rss/rss_uttarakhand.xml", SachetFeeds.urlFor("Uttarakhand"))
        assertEquals("https://sachet.ndma.gov.in/cap_public_website/rss/rss_jammu.xml", SachetFeeds.urlFor("Jammu & Kashmir"))
        assertEquals("https://sachet.ndma.gov.in/cap_public_website/rss/rss_west.xml", SachetFeeds.urlFor("west bengal"))
        assertNull(SachetFeeds.urlFor("Bavaria"))
        assertNull(SachetFeeds.urlFor(null))
    }

    @Test
    fun `an alert is for the wearer when its area names their city or district as a word`() {
        assertTrue(AreaMatch.mentions("Dindigul, Madurai, Ramanathapuram, Theni districts of Tamil Nadu", listOf("Madurai", null)))
        assertTrue(AreaMatch.mentions("S.A.S Nagar district of Punjab", listOf("Mohali", "SAS Nagar")))
        assertFalse("part of a longer name is not a match", AreaMatch.mentions("Ludhiana East", listOf("Ana", null)))
        assertFalse(AreaMatch.mentions("Patiala, Sangrur districts of Punjab", listOf("Ludhiana", "Ludhiana")))
    }

    @Test
    fun `hazard kinds come from the event and headline`() {
        assertEquals(Hazard.FLOOD_RAIN, Hazard.of("Moderate Rain", "Light to Moderate Rain likely"))
        assertEquals(Hazard.THUNDERSTORM, Hazard.of("Moderate Thunderstorms with surface wind", ""))
        assertEquals(Hazard.HEAT, Hazard.of("Heat Wave", "Heat wave conditions likely"))
        assertEquals(Hazard.FLOOD_RAIN, Hazard.of("", "Due to continuous increase of water level of River Dikhow"))
        assertEquals(Hazard.CYCLONE, Hazard.of("Cyclone", ""))
        assertEquals(Hazard.OTHER, Hazard.of("Test", "Something"))
        Hazard.entries.forEach { assertTrue(DisasterGuidance.forHazard(it).isNotEmpty()) }
    }

    @Test
    fun `forecast hints use IMD's rain and heat terms`() {
        assertTrue(ForecastHazards.flags(listOf(38f, 40f), listOf(10f, 20f)).isEmpty())
        val flags = ForecastHazards.flags(listOf(45.2f, 47.0f), listOf(64.5f, 210f))
        assertEquals(4, flags.size)
        assertTrue(flags.any { it.text == "Heat-wave level heat forecast today" })
        assertTrue(flags.any { it.text == "Severe heat-wave level heat forecast tomorrow" })
        assertTrue(flags.any { it.text == "Heavy rain forecast today" })
        assertTrue(flags.any { it.text == "Extremely heavy rain forecast tomorrow" })
        assertEquals("Very heavy rain forecast today", ForecastHazards.flags(emptyList(), listOf(115.6f)).single().text)
    }
}
