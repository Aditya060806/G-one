package com.gone.ai.health.emergency

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.AtomicFile
import com.gone.ai.data.UserProfile
import com.gone.ai.data.library.GoneDatabase
import com.gone.ai.health.domain.EmergencyCapability
import com.gone.ai.health.service.HealthMonitoringService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class EmergencySharingState(
    val enabled: Boolean = false,
    val removing: Boolean = false,
    val url: String? = null,
    val syncedAt: Long? = null,
    val busy: Boolean = false,
    val message: String = "Online sharing is off. Your medical details stay on this phone."
)

/** Opt-in, latest-snapshot sharing. Inference and SOS remain entirely independent of this upload. */
object EmergencySync {
    const val WEB_BASE = "https://g--one.vercel.app/e/"
    private const val API_BASE = "https://g--one.vercel.app/api/emergency/"
    private const val PERIODIC_JOB = 7901
    private const val RETRY_JOB = 7902
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val state = MutableStateFlow(EmergencySharingState())
    private var loaded = false
    private var key: String? = null
    private var enabled = false
    private var removing = false
    private var syncedAt: Long? = null
    private var lastLiveAttempt = 0L

    // noBackupFilesDir excludes the owner credential from cloud backup and device transfer.
    private fun file(ctx: Context) = AtomicFile(File(ctx.noBackupFilesDir, "emergency-sharing-v1.json"))
    @Synchronized private fun load(ctx: Context) {
        if (loaded) return
        runCatching {
            val json = JSONObject(file(ctx).openRead().bufferedReader().use { it.readText() })
            key = json.optString("key").takeIf { it.matches(Regex("[a-f0-9]{64}")) }
            enabled = key != null && json.optBoolean("enabled")
            removing = key != null && json.optBoolean("removing")
            syncedAt = json.optLong("syncedAt").takeIf { it > 0 }
        }
        loaded = true
        publish()
    }
    private fun persist(ctx: Context) {
        val json = JSONObject().put("key", key).put("enabled", enabled).put("removing", removing).put("syncedAt", syncedAt)
        val target = file(ctx)
        val stream = target.startWrite()
        try { stream.write(json.toString().toByteArray()); target.finishWrite(stream) }
        catch (e: Exception) { target.failWrite(stream); throw e }
    }
    private fun publish(message: String? = null, busy: Boolean = false) {
        state.value = EmergencySharingState(enabled, removing, key?.let { WEB_BASE + EmergencyCapability.readId(it) }, syncedAt, busy,
            message ?: when {
                removing -> "Removal pending. The old online record can still be read until deletion succeeds."
                enabled && syncedAt != null -> "Online sharing is on. Saved changes sync automatically when connected."
                enabled -> "Waiting for the first upload. The link is not ready yet."
                else -> "Online sharing is off. Offline tags already written must be erased or rewritten physically."
            })
    }
    fun observe(ctx: Context): StateFlow<EmergencySharingState> { load(ctx); return state.asStateFlow() }
    fun current(ctx: Context): EmergencySharingState { load(ctx); return state.value }

    fun setEnabled(ctx: Context, value: Boolean) {
        val app = ctx.applicationContext
        scope.launch {
            mutex.withLock {
                load(app)
                if (value && removing) return@withLock
                if (value && key == null) key = EmergencyCapability.newWriteKey()
                enabled = value
                removing = !value && key != null
                persist(app); publish()
            }
            schedule(app)
            sync(app)
        }
    }
    /** Called on saved edits and app opening. A failed upload always leaves an explicit pending status. */
    fun request(ctx: Context) {
        val app = ctx.applicationContext
        load(app)
        if (!state.value.enabled && !state.value.removing) return
        schedule(app)
        scope.launch { sync(app) }
    }
    @Synchronized fun onLiveWearableReading(ctx: Context) {
        load(ctx)
        if (!state.value.enabled) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastLiveAttempt < 30_000) return
        lastLiveAttempt = now
        // Separate scope: network latency must never block sampling, anomaly detection or SOS.
        scope.launch { sync(ctx.applicationContext) }
    }
    fun schedule(ctx: Context) {
        load(ctx)
        val scheduler = ctx.getSystemService(JobScheduler::class.java)
        if (!state.value.enabled && !state.value.removing) {
            scheduler.cancel(PERIODIC_JOB); scheduler.cancel(RETRY_JOB); return
        }
        val component = ComponentName(ctx, EmergencySyncJob::class.java)
        if (scheduler.getPendingJob(PERIODIC_JOB) == null) scheduler.schedule(
            JobInfo.Builder(PERIODIC_JOB, component).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60_000L).setPersisted(true).build())
        scheduler.schedule(JobInfo.Builder(RETRY_JOB, component).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(1000).setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).setPersisted(true).build())
    }
    suspend fun sync(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            load(ctx)
            val writeKey = key ?: return@withLock true
            if (!enabled && !removing) return@withLock true
            publish(busy = true)
            try {
                val deleting = removing
                val body = if (deleting) null else snapshot(ctx)
                val connection = (URL(API_BASE + EmergencyCapability.readId(writeKey)).openConnection() as HttpURLConnection).apply {
                    requestMethod = if (deleting) "DELETE" else "PUT"
                    connectTimeout = 10_000; readTimeout = 12_000; instanceFollowRedirects = false
                    setRequestProperty("Authorization", "Bearer $writeKey")
                    setRequestProperty("Content-Type", "application/json")
                    useCaches = false
                }
                val response = try {
                    if (body != null) { connection.doOutput = true; connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) } }
                    when (connection.responseCode) {
                        in 200..299 -> JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                        400, 413 -> error("Not uploaded: check field lengths (name 120; medical fields 600; implants 300; phone 40).")
                        else -> error(if (deleting) "Removal pending. The previous record remains accessible; reconnect and retry." else "Update pending. The web link still shows the last successful upload. Retrying when connected.")
                    }
                } finally { connection.disconnect() }
                if (deleting) { check(response.optBoolean("deleted")); removing = false; syncedAt = null }
                else { syncedAt = response.getLong("updatedAt") }
                persist(ctx); publish()
                if (!enabled && !removing) schedule(ctx)
                true
            } catch (e: CancellationException) { publish(); throw e }
            catch (e: Exception) {
                publish(if (e is IllegalStateException) e.message else if (removing)
                    "Removal pending. Reconnect to remove the online record; the previous copy may still be accessible."
                else "Update pending. No connection or server unavailable. The online record has not been updated.")
                false
            }
        }
    }
    /** Requires completed server deletion first. It cannot erase already written offline tag contents. */
    suspend fun forgetRevokedLink(ctx: Context): Boolean = mutex.withLock {
        load(ctx)
        if (enabled || removing) return@withLock false
        key = null; syncedAt = null; persist(ctx); publish(); true
    }
    private suspend fun snapshot(ctx: Context): JSONObject {
        val db = GoneDatabase.getInstance(ctx)
        val id = HealthMonitoringService.DEFAULT_PATIENT_ID
        val p = db.emergencyDao().get(id) ?: error("Save your emergency profile first.")
        val up = UserProfile.load(ctx)
        val json = JSONObject()
        fun text(k: String, v: String?) { v?.trim()?.takeIf { it.isNotEmpty() }?.let { json.put(k,it) } }
        text("name",up.name); up.age?.takeIf { it in 1..130 }?.let { json.put("age",it) }
        text("bloodGroup",p.bloodGroup); text("implantedDevices",p.implantedDevices)
        text("emergencyContact",p.primaryEmergencyContact ?: up.emergencyContact)
        if (p.shareAllergies) text("allergies",p.allergies)
        if (p.shareConditions) text("chronicConditions",p.chronicConditions)
        if (p.shareMedications) text("medications",p.medications)
        if (p.shareLiveVitals) db.vitalsDao().latestWearable(id)?.let { r ->
            if (r.timestamp <= System.currentTimeMillis() + 30_000) {
                r.heartRate?.takeIf { it in 1..300 }?.let { json.put("heartRate",it) }
                r.spo2?.takeIf { it in 1..100 }?.let { json.put("spo2",it) }
                r.bodyTempC?.takeIf { it.isFinite() && it in 20f..50f }?.let { json.put("bodyTempC",it.toDouble()) }
                r.skinTempC?.takeIf { it.isFinite() && it in 0f..60f }?.let { json.put("skinTempC",it.toDouble()) }
                if (listOf("heartRate","spo2","bodyTempC","skinTempC").any(json::has)) json.put("readingTimestamp",r.timestamp)
            }
        }
        return json
    }
}
