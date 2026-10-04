package com.gone.ai.health.environment

import com.gone.ai.health.disaster.CapAlert
import com.gone.ai.health.disaster.CapInfo
import com.gone.ai.health.disaster.FeedItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IndiaAqiTest {

    @Test
    fun `sub-indices follow CPCB's bands`() {
        assertEquals(50, IndiaAqi.subIndex("PM2.5", 30f))
        assertEquals(100, IndiaAqi.subIndex("PM2.5", 60f))
        assertEquals(200, IndiaAqi.subIndex("PM2.5", 90f))
        assertEquals(300, IndiaAqi.subIndex("PM10", 350f))
        assertEquals(400, IndiaAqi.subIndex("PM10", 430f))
        assertEquals(25, IndiaAqi.subIndex("PM2.5", 15f))
        assertEquals(500, IndiaAqi.subIndex("PM2.5", 900f))
        assertNull(IndiaAqi.subIndex("PM2.5", Float.NaN))
    }

    @Test
    fun `the index is the worst sub-index, with the categories CPCB names`() {
        val r = IndiaAqi.compute(IndiaAqi.Averages(pm25Day = 75f, pm10Day = 120f, no2Day = 30f, so2Day = null, o3EightHours = null, coEightHours = null, observedAt = 0))!!
        assertEquals("PM2.5", r.dominant)
        // 101 + (75 - 60) x 99 / 30 = 150.5, rounded half up.
        assertEquals(151, r.value)
        assertEquals("Moderate", r.category)
        assertEquals("Good", IndiaAqi.category(50))
        assertEquals("Satisfactory", IndiaAqi.category(51))
        assertEquals("Severe", IndiaAqi.category(450))
    }

    @Test
    fun `fewer than three pollutants, or no particulate matter, gives no index`() {
        assertNull(IndiaAqi.compute(IndiaAqi.Averages(40f, 80f, null, null, null, null, 0)))
        assertNull(IndiaAqi.compute(IndiaAqi.Averages(null, null, 30f, 20f, 50f, 1f, 0)))
    }

    @Test
    fun `averages take 24 hours, or 8 for ozone and carbon monoxide, up to now`() {
        val hour = 3_600_000L
        val now = 48 * hour
        val times = LongArray(48) { (it + 1) * hour }
        val series = mapOf(
            "pm2_5" to FloatArray(48) { if (it < 24) 999f else 40f },     // only the last 24 hours count
            "ozone" to FloatArray(48) { if (it < 40) 0f else 80f },        // only the last 8
            "carbon_monoxide" to FloatArray(48) { 1500f },                 // µg/m³ to mg/m³
            "pm10" to FloatArray(48) { Float.NaN }                         // missing
        )
        val a = IndiaAqi.averages(times, series, now)
        assertEquals(40f, a.pm25Day!!, 1e-3f)
        assertEquals(80f, a.o3EightHours!!, 1e-3f)
        assertEquals(1.5f, a.coEightHours!!, 1e-4f)
        assertNull(a.pm10Day)
        assertEquals(now, a.observedAt)
    }
}

class AreaRepositoryTest {

    private val now = 1_790_960_000_000L
    private val ludhiana = AqiPlace(1, "Ludhiana, Punjab, India", 30.9, 75.85, state = "Punjab", district = "Ludhiana")

    private class MemoryStore(var value: AreaSaved = AreaSaved()) : AreaStore {
        override fun load() = value
        override fun save(value: AreaSaved) { this.value = value }
    }

    private inner class FakeClient : AreaClient {
        var feedCalls = mutableListOf<String>()
        var alertCalls = 0
        var failWeather = false
        val items = mutableListOf(
            FeedItem("A1", "Rain over Ludhiana", "https://sachet.ndma.gov.in/x?identifier=A1", now - 60_000, "IMD Chandigarh"),
            FeedItem("A2", "Rain over Patiala", "https://sachet.ndma.gov.in/x?identifier=A2", now - 60_000, "IMD Chandigarh")
        )
        override suspend fun weather(place: AqiPlace, now: Long): WeatherReading {
            check(!failWeather) { "offline" }
            return WeatherReading(31f, 60f, 35f, 4f, now - 300_000, now, listOf(33f, 46f), listOf(5f, 70f))
        }
        override suspend fun pollutants(place: AqiPlace, now: Long) = IndiaAqi.Averages(70f, 150f, 30f, null, null, null, now)
        override suspend fun feed(url: String): List<FeedItem> { feedCalls += url; return items }
        override suspend fun alert(link: String): CapAlert {
            alertCalls++
            val id = link.substringAfter("identifier=")
            val district = if (id == "A1") "Ludhiana" else "Patiala"
            return CapAlert(id, "IMD", now - 60_000, "Actual", "Alert", listOf(
                CapInfo("en-IN", "Met", "Heavy Rain", "Expected", "Severe", "Likely", "Heavy rain likely over $district",
                    "", "Stay indoors.", "$district district of Punjab", now, now + 3 * 3_600_000)
            ))
        }
    }

    @Test
    fun `off by default and does nothing while off`() = runTest {
        val client = FakeClient()
        val repo = AreaRepository(MemoryStore(), client, { ludhiana }, { now })
        assertTrue(repo.refresh(force = true).isEmpty())
        assertTrue(client.feedCalls.isEmpty())
    }

    @Test
    fun `reads the state's feed, keeps active alerts and announces only the wearer's district once`() = runTest {
        val store = MemoryStore()
        val client = FakeClient()
        val repo = AreaRepository(store, client, { ludhiana }, { now })
        repo.enable(true)

        val first = repo.refresh(force = true)
        assertEquals(listOf("https://sachet.ndma.gov.in/cap_public_website/rss/rss_punjab.xml"), client.feedCalls)
        assertEquals(listOf("A1"), first.map { it.identifier })
        val saved = repo.state.value.saved
        assertEquals(2, saved.alerts.size)
        assertTrue(saved.alerts.single { it.identifier == "A1" }.forYourArea)
        assertEquals(listOf("A1", "A2"), saved.activeAlerts(now).map { it.identifier })
        assertEquals(31f, saved.weather!!.temperatureC)
        assertEquals("PM2.5", saved.indiaAqi!!.dominant)
        assertEquals(2, saved.forecastFlags().size)
        assertEquals("saved for offline use", saved, store.value)

        // Next check, a minute later: the same alerts are not fetched or announced again.
        val repo2 = AreaRepository(store, client, { ludhiana }, { now + 61_000 })
        assertTrue(repo2.refresh(force = true).isEmpty())
        assertEquals(2, client.alertCalls)
    }

    @Test
    fun `expired alerts drop out`() = runTest {
        val store = MemoryStore()
        val client = FakeClient()
        AreaRepository(store, client, { ludhiana }, { now }).apply { enable(true); refresh(force = true) }
        val later = now + 4 * 3_600_000
        assertTrue(store.value.activeAlerts(later).isEmpty())
    }

    @Test
    fun `one part failing keeps the rest and says so`() = runTest {
        val client = FakeClient().apply { failWeather = true }
        val repo = AreaRepository(MemoryStore(), client, { ludhiana }, { now })
        repo.enable(true)
        repo.refresh(force = true)
        val state = repo.state.value
        assertNull(state.saved.weather)
        assertEquals(2, state.saved.alerts.size)
        assertTrue(state.problem!!.contains("weather"))
    }

    @Test
    fun `places outside India's states get no feed and a plain reason`() = runTest {
        val client = FakeClient()
        val repo = AreaRepository(MemoryStore(), client, { AqiPlace(2, "Munich, Bavaria, Germany", 48.1, 11.6, state = "Bavaria") }, { now })
        repo.enable(true)
        repo.refresh(force = true)
        assertTrue(client.feedCalls.isEmpty())
        assertTrue(repo.state.value.problem!!.contains("India"))
    }

    @Test
    fun `older saved cities without a state use the label`() {
        assertEquals("Punjab", AreaRepository.stateFromLabel("Ludhiana, Punjab, India"))
    }

    @Test
    fun `switching off forgets everything`() = runTest {
        val store = MemoryStore()
        val repo = AreaRepository(store, FakeClient(), { ludhiana }, { now })
        repo.enable(true)
        repo.refresh(force = true)
        repo.enable(false)
        assertEquals(AreaSaved(), store.value)
    }
}
