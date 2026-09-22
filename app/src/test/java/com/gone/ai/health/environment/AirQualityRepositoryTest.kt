package com.gone.ai.health.environment

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AirQualityRepositoryTest {
    private val city = AqiPlace(1, "Example", 12.0, 77.0)
    private val time = 1_800_000_000_000L
    private class Store(var saved: AqiSaved = AqiSaved()) : AqiStore {
        override fun load() = saved
        override fun save(value: AqiSaved) { saved = value }
    }
    private class Client(var result: suspend () -> AqiReading) : AqiClient {
        var calls = 0
        override suspend fun search(query: String) = emptyList<AqiPlace>()
        override suspend fun fetch(place: AqiPlace, now: Long): AqiReading { calls++; return result() }
    }
    @Test fun `disabled by default makes no network requests`() = runTest {
        val client = Client { error("must not fetch") }
        val repo = AirQualityRepository(Store(), client) { time }
        repo.refresh(true)
        assertEquals(0, client.calls)
    }
    @Test fun `failed refresh keeps cached value and original timestamp`() = runTest {
        val old = AqiReading(120, time - 4 * 3600_000, time - 2 * 3600_000)
        val store = Store(AqiSaved(true, city, old))
        val repo = AirQualityRepository(store, Client { throw java.io.IOException() }) { time }
        repo.refresh()
        assertEquals(old, repo.state.value.saved.reading)
        assertEquals(old, store.saved.reading)
        assertNotNull(repo.state.value.problem)
        assertTrue(old.isStale(time))
    }
    @Test fun `successful value survives recreation and fresh cache avoids requests`() = runTest {
        val store = Store(AqiSaved(true, city))
        val client = Client { AqiReading(42, time, time) }
        AirQualityRepository(store, client) { time }.refresh()
        val reopened = AirQualityRepository(store, client) { time + 60_000 }
        reopened.refresh()
        assertEquals(42, reopened.state.value.saved.reading?.value)
        assertEquals(1, client.calls)
    }
    @Test fun `old city cannot overwrite new city when a request finishes late`() = runTest {
        val response = CompletableDeferred<AqiReading>()
        val repo = AirQualityRepository(Store(AqiSaved(true, city)), Client { response.await() }) { time }
        val request = async { repo.refresh() }
        testScheduler.runCurrent()
        val other = city.copy(id = 2, label = "Other", latitude = 20.0)
        repo.select(other)
        response.complete(AqiReading(90, time, time))
        request.await()
        assertEquals(other, repo.state.value.saved.place)
        assertNull(repo.state.value.saved.reading)
    }
    @Test fun `disabling during request prevents a late cache write`() = runTest {
        val response = CompletableDeferred<AqiReading>()
        val repo = AirQualityRepository(Store(AqiSaved(true, city)), Client { response.await() }) { time }
        val request = async { repo.refresh() }
        testScheduler.runCurrent()
        repo.enable(false)
        response.complete(AqiReading(90, time, time))
        request.await()
        assertFalse(repo.state.value.saved.enabled)
        assertNull(repo.state.value.saved.reading)
    }
    @Test fun `timeout stops loading while retaining last successful reading`() = runTest {
        val old = AqiReading(60, time - 3600_000, time - 3600_000)
        val repo = AirQualityRepository(Store(AqiSaved(true, city, old)), Client { CompletableDeferred<AqiReading>().await() }) { time }
        repo.refresh(true)
        assertEquals(old, repo.state.value.saved.reading)
        assertFalse(repo.state.value.loading)
        assertNotNull(repo.state.value.problem)
    }
    @Test fun `repeated failures are throttled and forgetting removes city and cache`() = runTest {
        val client = Client { error("offline") }
        val store = Store(AqiSaved(true, city))
        val repo = AirQualityRepository(store, client) { time }
        repo.refresh(true); repo.refresh(true)
        assertEquals(1, client.calls)
        repo.forget()
        assertEquals(AqiSaved(), store.saved)
    }
    @Test fun `bad provider values never replace a good cache`() = runTest {
        val old = AqiReading(60, time - 3600_000, time - 3600_000)
        val repo = AirQualityRepository(Store(AqiSaved(true, city, old)), Client { AqiReading(-1, time, time) }) { time }
        repo.refresh(true)
        assertEquals(old, repo.state.value.saved.reading)
    }
    @Test fun `US AQI category boundaries and staleness`() {
        assertEquals("Good", aqiCategory(50))
        assertEquals("Moderate", aqiCategory(51))
        assertEquals("Unhealthy for sensitive groups", aqiCategory(101))
        assertEquals("Unhealthy", aqiCategory(151))
        assertEquals("Very unhealthy", aqiCategory(201))
        assertEquals("Hazardous", aqiCategory(301))
        assertFalse(AqiReading(10, time, time).isStale(time))
        assertTrue(AqiReading(10, time - 4 * 3600_000, time).isStale(time))
    }
}
