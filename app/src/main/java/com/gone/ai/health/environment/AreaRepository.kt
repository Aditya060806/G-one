package com.gone.ai.health.environment

import com.gone.ai.health.disaster.AreaMatch
import com.gone.ai.health.disaster.CapAlert
import com.gone.ai.health.disaster.FeedItem
import com.gone.ai.health.disaster.ForecastHazards
import com.gone.ai.health.disaster.Hazard
import com.gone.ai.health.disaster.SachetFeeds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout

/**
 * "Your area": the city's weather, India's air quality index, and official disaster alerts.
 *
 * OPT-IN and separate from health monitoring, which works without any of it. PURE KOTLIN: the
 * network is behind [AreaClient], storage behind [AreaStore], so the refresh logic is tested on
 * the JVM, as [AirQualityRepository] is.
 *
 * What leaves the phone: the chosen city's coordinates (to Open-Meteo, as for AQI) and the name
 * of the state's public alert feed (to SACHET). The phone downloads the whole state's feed and
 * decides itself which alerts name the wearer's district, so no location is sent to SACHET.
 */

data class WeatherReading(
    val temperatureC: Float,
    val humidityPct: Float?,
    val apparentC: Float?,
    val uvIndex: Float?,
    val observedAt: Long,
    val fetchedAt: Long,
    /** Forecast daily maxima and rain totals, today first. */
    val dailyMaxC: List<Float> = emptyList(),
    val dailyRainMm: List<Float> = emptyList()
) {
    fun isStale(now: Long) = now - observedAt > 3 * 60 * 60_000L || observedAt > now + 60_000L
}

/** An official alert as kept on the phone. */
data class AreaAlert(
    val identifier: String,
    val event: String,
    val severity: String,
    val urgency: String,
    val certainty: String,
    val headline: String,
    val localHeadline: String?,
    val instruction: String,
    val areaDesc: String,
    val issuer: String,
    val sent: Long?,
    val expires: Long?,
    val hazard: Hazard,
    /** The alert's area names the wearer's city or district. */
    val forYourArea: Boolean
) {
    /** Worth a notification: for the wearer's district and at least Moderate. */
    val notifiable: Boolean
        get() = forYourArea && severity.lowercase() in setOf("extreme", "severe", "moderate")
}

data class AreaSaved(
    val enabled: Boolean = false,
    val weather: WeatherReading? = null,
    val indiaAqi: IndiaAqi.Result? = null,
    val indiaAqiObservedAt: Long? = null,
    val alerts: List<AreaAlert> = emptyList(),
    val alertsCheckedAt: Long? = null,
    /** Alert identifiers already notified, so none is announced twice. */
    val notified: List<String> = emptyList()
) {
    fun activeAlerts(now: Long): List<AreaAlert> =
        alerts.filter { a -> a.expires?.let { it > now } ?: true }
            .sortedWith(compareByDescending<AreaAlert> { it.forYourArea }.thenByDescending { it.sent ?: 0L })

    fun forecastFlags(): List<ForecastHazards.Flag> =
        weather?.let { ForecastHazards.flags(it.dailyMaxC, it.dailyRainMm) }.orEmpty()
}

data class AreaState(val saved: AreaSaved, val loading: Boolean = false, val problem: String? = null)

interface AreaStore { fun load(): AreaSaved; fun save(value: AreaSaved) }

interface AreaClient {
    suspend fun weather(place: AqiPlace, now: Long): WeatherReading
    suspend fun pollutants(place: AqiPlace, now: Long): IndiaAqi.Averages
    suspend fun feed(url: String): List<FeedItem>
    suspend fun alert(link: String): CapAlert?
}

class AreaRepository(
    private val store: AreaStore,
    private val client: AreaClient,
    /** The city chosen for air quality; the same one is used here. */
    private val place: () -> AqiPlace?,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val mutable = MutableStateFlow(AreaState(store.load()))
    val state = mutable.asStateFlow()
    private val gate = Mutex()
    private var attemptedAt: Long? = null

    fun enable(enabled: Boolean) {
        attemptedAt = null
        persist(if (enabled) mutable.value.saved.copy(enabled = true) else AreaSaved())
    }

    private fun persist(saved: AreaSaved) {
        store.save(saved)
        mutable.value = AreaState(saved)
    }

    /**
     * Brings weather, India AQI and alerts up to date, each on its own; one failing keeps the
     * others and the saved values.
     *
     * @return alerts to announce now: new, for the wearer's district, at least Moderate.
     */
    suspend fun refresh(force: Boolean = false): List<AreaAlert> {
        if (!gate.tryLock()) return emptyList()
        try {
            var saved = state.value.saved
            if (!saved.enabled) return emptyList()
            val where = place() ?: run {
                mutable.value = AreaState(saved, problem = "Choose your city in the Air quality card first.")
                return emptyList()
            }
            val time = now()
            if (attemptedAt?.let { time - it in 0 until 60_000L } == true) return emptyList()
            val recent = saved.alertsCheckedAt?.let { time - it in 0 until REFRESH_MILLIS } == true &&
                saved.weather?.let { time - it.fetchedAt in 0 until REFRESH_MILLIS } == true
            if (!force && recent) return emptyList()
            attemptedAt = time
            mutable.value = AreaState(saved, loading = true)
            val problems = mutableListOf<String>()

            attempt("weather", problems) {
                saved = saved.copy(weather = client.weather(where, time))
            }
            attempt("India AQI", problems) {
                val averages = client.pollutants(where, time)
                val result = IndiaAqi.compute(averages)
                saved = saved.copy(indiaAqi = result, indiaAqiObservedAt = averages.observedAt.takeIf { result != null })
            }
            var announce = emptyList<AreaAlert>()
            val feed = SachetFeeds.urlFor(where.state ?: stateFromLabel(where.label))
            if (feed == null) {
                saved = saved.copy(alertsCheckedAt = time)
                problems += "Official alerts cover places in India; this city's state was not recognised."
            } else attempt("official alerts", problems) {
                val (alerts, fresh) = updateAlerts(feed, where, saved.alerts, time)
                val new = fresh.filter { it.notifiable && it.identifier !in saved.notified }
                announce = new
                saved = saved.copy(
                    alerts = alerts, alertsCheckedAt = time,
                    notified = (saved.notified + new.map { it.identifier }).takeLast(MAX_NOTIFIED)
                )
            }
            if (state.value.saved.enabled) {
                store.save(saved)
                mutable.value = AreaState(saved, problem = problems.takeIf { it.isNotEmpty() }?.joinToString(" "))
            }
            return announce
        } finally {
            gate.unlock()
        }
    }

    /** The active alerts after reading the feed, and those among them that were not known before. */
    private suspend fun updateAlerts(feed: String, where: AqiPlace, known: List<AreaAlert>, time: Long): Pair<List<AreaAlert>, List<AreaAlert>> {
        val items = withTimeout(TIMEOUT) { client.feed(feed) }
            .filter { it.publishedAt?.let { at -> time - at < FEED_AGE_MILLIS } ?: true }
            .take(MAX_FEED_ITEMS)
        val byId = known.associateBy { it.identifier }
        val kept = mutableListOf<AreaAlert>()
        val fresh = mutableListOf<AreaAlert>()
        var fetched = 0
        for (item in items) {
            val existing = byId[item.identifier]
            if (existing != null) { kept += existing; continue }
            if (fetched >= MAX_FETCH_PER_REFRESH) break
            fetched++
            val cap = runCatching { withTimeout(TIMEOUT) { client.alert(item.link) } }.getOrNull() ?: continue
            if (!cap.isActive(time)) continue
            val alert = toAreaAlert(cap, item, where)
            kept += alert
            fresh += alert
        }
        return kept.filter { a -> a.expires?.let { it > time } ?: true } to fresh
    }

    private suspend fun attempt(what: String, problems: MutableList<String>, block: suspend () -> Unit) {
        try {
            withTimeout(TIMEOUT * 3) { block() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            problems += "Could not update $what (timed out); the saved value is kept."
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            problems += "Could not update $what; the saved value is kept."
        }
    }

    companion object {
        const val REFRESH_MILLIS = 15 * 60_000L
        const val TIMEOUT = 10_000L
        const val FEED_AGE_MILLIS = 48 * 60 * 60_000L
        const val MAX_FEED_ITEMS = 30
        const val MAX_FETCH_PER_REFRESH = 15
        const val MAX_NOTIFIED = 200

        /** "Ludhiana, Punjab, India" → "Punjab", for places saved before the state was stored. */
        fun stateFromLabel(label: String): String? = label.split(',').map { it.trim() }.getOrNull(1)

        fun toAreaAlert(cap: CapAlert, item: FeedItem, where: AqiPlace): AreaAlert {
            val info = cap.english
            val event = info?.event.orEmpty()
            val headline = info?.headline?.ifBlank { item.title } ?: item.title
            val area = cap.infos.joinToString("; ") { it.areaDesc }.ifBlank { headline }
            return AreaAlert(
                identifier = cap.identifier,
                event = event.ifBlank { "Alert" },
                severity = info?.severity.orEmpty(),
                urgency = info?.urgency.orEmpty(),
                certainty = info?.certainty.orEmpty(),
                headline = headline,
                localHeadline = cap.localHeadline?.takeIf { it != headline },
                instruction = info?.instruction.orEmpty(),
                areaDesc = info?.areaDesc?.ifBlank { area } ?: area,
                issuer = item.issuer.ifBlank { cap.sender },
                sent = cap.sent ?: item.publishedAt,
                expires = cap.expires,
                hazard = Hazard.of(event, headline),
                forYourArea = AreaMatch.mentions("$area $headline", listOf(where.name, where.district))
            )
        }
    }
}
