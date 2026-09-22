package com.gone.ai.health.environment

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/** Only city searches and city-centre coordinates leave the phone. No device ID or health data. */
class OpenMeteoClient : AqiClient {
    override suspend fun search(query: String): List<AqiPlace> {
        val result = get("https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(query, "UTF-8")}&count=5&language=en&format=json")
            .optJSONArray("results") ?: return emptyList()
        return (0 until result.length()).mapNotNull { index ->
            runCatching {
                val item = result.getJSONObject(index)
                AqiPlace(item.getLong("id"), listOf(item.getString("name"), item.optString("admin1"), item.optString("country"))
                    .filter { it.isNotBlank() }.distinct().joinToString(", "), item.getDouble("latitude"), item.getDouble("longitude"))
                    .takeIf { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }
            }.getOrNull()
        }
    }

    override suspend fun fetch(place: AqiPlace, now: Long): AqiReading {
        val current = get("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=${place.latitude}&longitude=${place.longitude}&current=us_aqi&timeformat=unixtime&timezone=GMT")
            .getJSONObject("current")
        val value = current.getDouble("us_aqi")
        require(value.isFinite() && value in 0.0..1000.0)
        return AqiReading(value.roundToInt(), current.getLong("time") * 1000L, now)
    }

    private suspend fun get(address: String): JSONObject = withContext(Dispatchers.IO) {
        // Cancellation disconnects the socket, so slow/trickling responses cannot block the UI timeout.
        suspendCancellableCoroutine { continuation ->
            val connection = URL(address).openConnection() as HttpURLConnection
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            val future = executor.submit {
                try {
                    check(connection.responseCode == 200)
                    val text = connection.inputStream.bufferedReader().use { reader ->
                        val chars = CharArray(65_537)
                        var count = 0
                        while (count < chars.size) {
                            val read = reader.read(chars, count, chars.size - count)
                            if (read < 0) break
                            count += read
                        }
                        require(count <= 65_536)
                        String(chars, 0, count)
                    }
                    val result = JSONObject(text)
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                } finally { connection.disconnect() }
            }
            continuation.invokeOnCancellation { connection.disconnect(); future.cancel(true) }
        }
    }

    companion object { private val executor = Executors.newFixedThreadPool(2) }
}

class AndroidAqiStore(context: Context) : AqiStore {
    private val preferences = context.applicationContext.getSharedPreferences("optional_air_quality", Context.MODE_PRIVATE)
    override fun load(): AqiSaved = runCatching {
        val json = JSONObject(preferences.getString("saved", "{}") ?: "{}")
        val place = json.optJSONObject("place")?.let {
            AqiPlace(it.getLong("id"), it.getString("label"), it.getDouble("latitude"), it.getDouble("longitude"))
        }
        val reading = if (place != null) json.optJSONObject("reading")?.let {
            AqiReading(it.getInt("value"), it.getLong("observed"), it.getLong("fetched"))
        }?.takeIf { it.value in 0..1000 && it.observedAt > 0 } else null
        AqiSaved(json.optBoolean("enabled", false), place, reading)
    }.getOrDefault(AqiSaved())

    override fun save(value: AqiSaved) {
        val json = JSONObject().put("enabled", value.enabled)
        value.place?.let { json.put("place", JSONObject().put("id", it.id).put("label", it.label).put("latitude", it.latitude).put("longitude", it.longitude)) }
        value.reading?.let { json.put("reading", JSONObject().put("value", it.value).put("observed", it.observedAt).put("fetched", it.fetchedAt)) }
        preferences.edit().putString("saved", json.toString()).apply()
    }
}
