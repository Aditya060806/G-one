package com.gone.ai.health.environment

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.gone.ai.health.detect.AreaWeather
import com.gone.ai.health.disaster.CapParser
import com.gone.ai.health.disaster.FeedItem
import com.gone.ai.health.disaster.Hazard
import com.gone.ai.health.service.HealthNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The phone's one "Your area" service: the repository, its storage, the background check and the
 * hand-off of city weather to the heat rules.
 */
object AreaAwareness {

    private const val JOB_ID = 7911
    @Volatile private var repository: AreaRepository? = null

    fun repository(context: Context): AreaRepository = repository ?: synchronized(this) {
        repository ?: AreaRepository(
            store = AndroidAreaStore(context),
            client = AreaHttpClient,
            place = { AndroidAqiStore(context).load().place }
        ).also { repository = it }
    }

    /**
     * City weather for the heat rules, or null when the wearer has not switched it on. Freshness
     * is judged by the rules ([AreaWeather.isFresh]).
     */
    fun weatherForRules(context: Context): AreaWeather? {
        val saved = repository(context).state.value.saved
        if (!saved.enabled) return null
        val w = saved.weather ?: return null
        val place = AndroidAqiStore(context).load().place?.label.orEmpty()
        return AreaWeather(w.temperatureC, w.humidityPct, w.observedAt, place)
    }

    /** Refreshes, and posts a notification for each new alert for the wearer's district. */
    suspend fun refreshAndNotify(context: Context, force: Boolean = false) {
        val announce = repository(context).refresh(force)
        announce.forEach { HealthNotifications.notifyAreaAlert(context, it) }
    }

    fun enable(context: Context, enabled: Boolean) {
        repository(context).enable(enabled)
        schedule(context)
    }

    /** A check every 30 minutes with any network while switched on; nothing while off. */
    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (!repository(context).state.value.saved.enabled) {
            scheduler.cancel(JOB_ID); return
        }
        if (scheduler.getPendingJob(JOB_ID) == null) scheduler.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(context, AreaAlertJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(30 * 60_000L)
                .setPersisted(true)
                .build()
        )
    }
}

class AreaAlertJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running = scope.launch {
            runCatching { AreaAwareness.refreshAndNotify(applicationContext) }
            withContext(Dispatchers.Main) { jobFinished(params, false) }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean { running?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

/** Open-Meteo for weather and pollutants, SACHET for alerts. */
object AreaHttpClient : AreaClient {

    private const val SACHET = "https://sachet.ndma.gov.in/"

    override suspend fun weather(place: AqiPlace, now: Long): WeatherReading {
        val json = JSONObject(HttpText.get(
            "https://api.open-meteo.com/v1/forecast?latitude=${place.latitude}&longitude=${place.longitude}" +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature,uv_index" +
                "&daily=temperature_2m_max,precipitation_sum&forecast_days=3&timeformat=unixtime&timezone=auto",
            "application/json"
        ))
        val current = json.getJSONObject("current")
        val daily = json.optJSONObject("daily")
        val temperature = current.getDouble("temperature_2m").toFloat()
        require(temperature.isFinite() && temperature in -60f..60f)
        return WeatherReading(
            temperatureC = temperature,
            humidityPct = current.optDouble("relative_humidity_2m").toFloat().takeIf { it.isFinite() && it in 0f..100f },
            apparentC = current.optDouble("apparent_temperature").toFloat().takeIf { it.isFinite() },
            uvIndex = current.optDouble("uv_index").toFloat().takeIf { it.isFinite() && it >= 0f },
            observedAt = current.getLong("time") * 1000L,
            fetchedAt = now,
            dailyMaxC = floats(daily?.optJSONArray("temperature_2m_max")),
            dailyRainMm = floats(daily?.optJSONArray("precipitation_sum"))
        )
    }

    override suspend fun pollutants(place: AqiPlace, now: Long): IndiaAqi.Averages {
        val json = JSONObject(HttpText.get(
            "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=${place.latitude}&longitude=${place.longitude}" +
                "&hourly=pm10,pm2_5,carbon_monoxide,nitrogen_dioxide,sulphur_dioxide,ozone" +
                "&past_days=1&forecast_days=1&timeformat=unixtime&timezone=GMT",
            "application/json"
        ))
        val hourly = json.getJSONObject("hourly")
        val times = hourly.getJSONArray("time").let { a -> LongArray(a.length()) { a.getLong(it) * 1000L } }
        val series = listOf("pm2_5", "pm10", "nitrogen_dioxide", "sulphur_dioxide", "ozone", "carbon_monoxide")
            .associateWith { floats(hourly.optJSONArray(it), keepMissing = true).toFloatArray() }
        return IndiaAqi.averages(times, series, now)
    }

    override suspend fun feed(url: String): List<FeedItem> {
        require(url.startsWith(SACHET))
        return CapParser.feed(HttpText.get(url, "application/rss+xml, application/xml", maxChars = 400_000))
    }

    override suspend fun alert(link: String) =
        // Only SACHET's own documents are fetched, whatever a feed item links to.
        if (!link.startsWith(SACHET)) null
        else CapParser.alert(HttpText.get(link, "application/xml, text/xml", maxChars = 200_000))

    private fun floats(array: JSONArray?, keepMissing: Boolean = false): List<Float> =
        (0 until (array?.length() ?: 0)).mapNotNull { i ->
            val v = if (array!!.isNull(i)) Float.NaN else array.optDouble(i).toFloat()
            if (v.isFinite() || keepMissing) v else null
        }
}

class AndroidAreaStore(context: Context) : AreaStore {
    private val preferences = context.applicationContext.getSharedPreferences("optional_area", Context.MODE_PRIVATE)

    override fun load(): AreaSaved = runCatching {
        val json = JSONObject(preferences.getString("saved", "{}") ?: "{}")
        AreaSaved(
            enabled = json.optBoolean("enabled", false),
            weather = json.optJSONObject("weather")?.let { w ->
                WeatherReading(
                    temperatureC = w.getDouble("t").toFloat(),
                    humidityPct = w.optDouble("h").toFloat().takeIf { it.isFinite() },
                    apparentC = w.optDouble("a").toFloat().takeIf { it.isFinite() },
                    uvIndex = w.optDouble("uv").toFloat().takeIf { it.isFinite() },
                    observedAt = w.getLong("observed"),
                    fetchedAt = w.getLong("fetched"),
                    dailyMaxC = w.optJSONArray("max").floatList(),
                    dailyRainMm = w.optJSONArray("rain").floatList()
                )
            },
            indiaAqi = json.optJSONObject("india_aqi")?.let { a ->
                val subs = a.getJSONObject("subs")
                IndiaAqi.Result(a.getInt("value"), a.getString("category"), a.getString("dominant"),
                    subs.keys().asSequence().associateWith { subs.getInt(it) })
            },
            indiaAqiObservedAt = json.optLong("india_aqi_observed").takeIf { it > 0 },
            alerts = json.optJSONArray("alerts")?.let { arr -> (0 until arr.length()).map { alert(arr.getJSONObject(it)) } }.orEmpty(),
            alertsCheckedAt = json.optLong("alerts_checked").takeIf { it > 0 },
            notified = json.optJSONArray("notified")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }.orEmpty()
        )
    }.getOrDefault(AreaSaved())

    override fun save(value: AreaSaved) {
        val json = JSONObject().put("enabled", value.enabled)
        value.weather?.let { w ->
            json.put("weather", JSONObject().put("t", w.temperatureC.toDouble()).put("h", w.humidityPct?.toDouble() ?: JSONObject.NULL)
                .put("a", w.apparentC?.toDouble() ?: JSONObject.NULL).put("uv", w.uvIndex?.toDouble() ?: JSONObject.NULL)
                .put("observed", w.observedAt).put("fetched", w.fetchedAt)
                .put("max", JSONArray(w.dailyMaxC.map { it.toDouble() })).put("rain", JSONArray(w.dailyRainMm.map { it.toDouble() })))
        }
        value.indiaAqi?.let { a ->
            json.put("india_aqi", JSONObject().put("value", a.value).put("category", a.category).put("dominant", a.dominant)
                .put("subs", JSONObject(a.subIndices as Map<*, *>)))
        }
        value.indiaAqiObservedAt?.let { json.put("india_aqi_observed", it) }
        json.put("alerts", JSONArray(value.alerts.map { alertJson(it) }))
        value.alertsCheckedAt?.let { json.put("alerts_checked", it) }
        json.put("notified", JSONArray(value.notified))
        preferences.edit().putString("saved", json.toString()).apply()
    }

    private fun JSONArray?.floatList(): List<Float> =
        (0 until (this?.length() ?: 0)).map { this!!.optDouble(it).toFloat() }.filter { it.isFinite() }

    private fun alertJson(a: AreaAlert) = JSONObject()
        .put("id", a.identifier).put("event", a.event).put("severity", a.severity).put("urgency", a.urgency)
        .put("certainty", a.certainty).put("headline", a.headline).put("local", a.localHeadline ?: "")
        .put("instruction", a.instruction).put("area", a.areaDesc).put("issuer", a.issuer)
        .put("sent", a.sent ?: 0L).put("expires", a.expires ?: 0L).put("hazard", a.hazard.name).put("yours", a.forYourArea)

    private fun alert(o: JSONObject) = AreaAlert(
        identifier = o.getString("id"), event = o.optString("event"), severity = o.optString("severity"),
        urgency = o.optString("urgency"), certainty = o.optString("certainty"), headline = o.optString("headline"),
        localHeadline = o.optString("local").takeIf { it.isNotBlank() }, instruction = o.optString("instruction"),
        areaDesc = o.optString("area"), issuer = o.optString("issuer"),
        sent = o.optLong("sent").takeIf { it > 0 }, expires = o.optLong("expires").takeIf { it > 0 },
        hazard = runCatching { Hazard.valueOf(o.optString("hazard")) }.getOrDefault(Hazard.OTHER),
        forYourArea = o.optBoolean("yours")
    )
}
