package com.gone.ai.health.environment

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gone.ai.health.disaster.SachetFeeds
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real services, from a real phone: Open-Meteo's weather and pollutants and NDMA's SACHET feed.
 *
 * Uses a fixed public city (Ludhiana), never the wearer's saved place, and changes no setting.
 * Skipped when the phone has no internet. What it proves is that the client still understands
 * what the services send today; the logic around it is covered by the JVM tests.
 */
@RunWith(AndroidJUnit4::class)
class AreaLiveNetworkTest {

    private val ludhiana = AqiPlace(1264728, "Ludhiana, Punjab, India", 30.91204, 75.85379, state = "Punjab", district = "Ludhiana")
    private val now get() = System.currentTimeMillis()

    @Before
    fun needsInternet() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val online = manager.getNetworkCapabilities(manager.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        assumeTrue("no internet on this phone", online)
    }

    @Test
    fun weatherArrivesWithATemperatureAndAForecast() = runBlocking {
        val w = AreaHttpClient.weather(ludhiana, now)
        assertTrue("temperature ${w.temperatureC}", w.temperatureC in -10f..55f)
        assertNotNull(w.humidityPct)
        assertTrue("observed recently", now - w.observedAt < 3 * 3_600_000L)
        assertTrue("daily maxima", w.dailyMaxC.size >= 2)
        assertEquals(w.dailyMaxC.size, w.dailyRainMm.size)
    }

    @Test
    fun pollutantsGiveAnIndiaAqi() = runBlocking {
        val averages = AreaHttpClient.pollutants(ludhiana, now)
        val result = IndiaAqi.compute(averages)
        assertNotNull("at least three pollutants including particulates: $averages", result)
        assertTrue(result!!.value in 0..500)
    }

    @Test
    fun theStateFeedAndItsAlertsParse() = runBlocking {
        val url = SachetFeeds.urlFor(ludhiana.state)!!
        val items = AreaHttpClient.feed(url)
        assertTrue("the Punjab feed has items", items.isNotEmpty())
        val alert = AreaHttpClient.alert(items.first().link)
        assertNotNull("the first item's CAP message parses", alert)
        assertTrue(alert!!.infos.isNotEmpty())
        assertTrue(alert.english!!.event.isNotBlank() || alert.english!!.headline.isNotBlank())
    }

    /** The whole refresh, into memory only: nothing is saved on the phone and no notification is posted. */
    @Test
    fun aFullRefreshFillsWeatherAqiAndAlerts() = runBlocking {
        val store = object : AreaStore {
            var value = AreaSaved(enabled = true)
            override fun load() = value
            override fun save(value: AreaSaved) { this.value = value }
        }
        val repository = AreaRepository(store, AreaHttpClient, { ludhiana })
        repository.refresh(force = true)
        val saved = repository.state.value.saved
        assertNotNull("weather: ${repository.state.value.problem}", saved.weather)
        assertNotNull("India AQI: ${repository.state.value.problem}", saved.indiaAqi)
        assertNotNull("alerts checked: ${repository.state.value.problem}", saved.alertsCheckedAt)
    }
}
