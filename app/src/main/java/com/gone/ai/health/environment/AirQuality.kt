package com.gone.ai.health.environment

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout

data class AqiPlace(val id: Long, val label: String, val latitude: Double, val longitude: Double)
data class AqiReading(val value: Int, val observedAt: Long, val fetchedAt: Long) {
    fun isStale(now: Long) = now - observedAt > 3 * 60 * 60_000L || observedAt > now + 60_000L
}
data class AqiSaved(val enabled: Boolean = false, val place: AqiPlace? = null, val reading: AqiReading? = null)
data class AqiState(val saved: AqiSaved, val loading: Boolean = false, val problem: String? = null)

interface AqiStore { fun load(): AqiSaved; fun save(value: AqiSaved) }
interface AqiClient {
    suspend fun search(query: String): List<AqiPlace>
    suspend fun fetch(place: AqiPlace, now: Long): AqiReading
}

/** Optional environmental context. Never writes into physiological samples or triggers SOS. */
class AirQualityRepository(
    private val store: AqiStore,
    private val client: AqiClient,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val mutable = MutableStateFlow(AqiState(store.load()))
    val state = mutable.asStateFlow()
    private val gate = Mutex()
    private var revision = 0
    private var attemptedAt: Long? = null

    fun enable(enabled: Boolean) {
        revision++
        attemptedAt = null
        persist(mutable.value.saved.copy(enabled = enabled))
    }

    fun select(place: AqiPlace) {
        require(place.latitude.isFinite() && place.latitude in -90.0..90.0)
        require(place.longitude.isFinite() && place.longitude in -180.0..180.0)
        revision++
        attemptedAt = null
        persist(AqiSaved(enabled = true, place = place))
    }

    fun forget() {
        revision++
        attemptedAt = null
        persist(AqiSaved())
    }

    private fun persist(saved: AqiSaved) {
        store.save(saved)
        mutable.value = AqiState(saved)
    }

    suspend fun search(query: String): List<AqiPlace> {
        if (!state.value.saved.enabled || query.trim().length < 2) return emptyList()
        return withTimeout(10_000) { client.search(query.trim().take(100)) }
    }

    suspend fun refresh(force: Boolean = false) {
        if (!gate.tryLock()) return
        val version = revision
        try {
            val saved = state.value.saved
            val place = saved.place ?: return
            if (!saved.enabled) return
            val time = now()
            // Also throttle failed attempts so a weak connection cannot create a request loop.
            if (attemptedAt?.let { time - it in 0 until 60_000L } == true) return
            if (!force && saved.reading?.let { time - it.fetchedAt in 0 until 15 * 60_000L } == true) return
            attemptedAt = time
            mutable.value = AqiState(saved, loading = true)
            try {
                val reading = withTimeout(10_000) { client.fetch(place, time) }
                require(reading.value in 0..1000 && reading.observedAt > 0 && reading.observedAt <= now() + 60_000L)
                if (version == revision) persist(saved.copy(reading = reading))
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                if (version == revision) mutable.value = AqiState(saved, problem = "Connection timed out. Last saved reading is kept.")
            } catch (e: CancellationException) {
                if (version == revision) mutable.value = AqiState(saved)
                throw e
            } catch (_: Exception) {
                if (version == revision) mutable.value = AqiState(saved, problem = "Could not update AQI. Check your connection; any saved reading is kept.")
            }
        } finally { gate.unlock() }
    }
}

/** US EPA category names, not India's National AQI. */
fun aqiCategory(value: Int): String = when {
    value <= 50 -> "Good"
    value <= 100 -> "Moderate"
    value <= 150 -> "Unhealthy for sensitive groups"
    value <= 200 -> "Unhealthy"
    value <= 300 -> "Very unhealthy"
    else -> "Hazardous"
}
