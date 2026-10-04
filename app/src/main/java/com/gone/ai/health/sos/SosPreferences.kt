package com.gone.ai.health.sos

import android.content.Context
import androidx.core.content.edit
import com.gone.ai.data.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The automatic SOS settings, and a record of what it sent.
 *
 * OFF until the wearer turns it on in Settings, which is also where Android asks, once, for
 * permission to send texts and make calls. The number is the emergency contact from the profile,
 * so there is one place to change it.
 */
object SosPreferences {

    private const val PREFS = "gone_sos"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_CALL_AFTER = "call_after"
    // A new key: countdown lengths saved by earlier versions applied to every alert and are not carried over.
    private const val KEY_FALL_CANCEL = "fall_cancel_seconds"
    private const val KEY_RECORDS = "records"
    private const val MAX_RECORDS = 20

    data class Settings(
        val enabled: Boolean = false,
        val callAfter: Boolean = false,
        /** The "I'm OK" window for a fall. Other alerts never wait. */
        val fallCancelSeconds: Int = SosCoordinator.DEFAULT_FALL_CANCEL_SECONDS
    )

    @Volatile private var settings: MutableStateFlow<Settings>? = null
    @Volatile private var records: MutableStateFlow<List<SosRecord>>? = null

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun settings(context: Context): StateFlow<Settings> = settingsFlow(context).asStateFlow()

    fun update(context: Context, change: (Settings) -> Settings) {
        val changed = change(settingsFlow(context).value)
        val next = changed.copy(fallCancelSeconds = cancelWindow(changed.fallCancelSeconds))
        prefs(context).edit {
            putBoolean(KEY_ENABLED, next.enabled)
            putBoolean(KEY_CALL_AFTER, next.callAfter)
            putInt(KEY_FALL_CANCEL, next.fallCancelSeconds)
        }
        settingsFlow(context).value = next
    }

    /** The emergency contact, fit to dial; null when unset or not a phone number. */
    fun number(context: Context): String? = SosNumbers.normalize(UserProfile.load(context).emergencyContact)

    /** What an automatic SOS would use now; null when it is off or has no usable number. */
    fun config(context: Context): SosConfig? {
        val s = settingsFlow(context).value
        if (!s.enabled) return null
        val number = number(context) ?: return null
        return SosConfig(number, UserProfile.load(context).name, s.fallCancelSeconds, s.callAfter)
    }

    /** Only the offered lengths are kept; anything else falls back to the default. */
    private fun cancelWindow(seconds: Int): Int =
        if (seconds in SosCoordinator.FALL_CANCEL_CHOICES) seconds else SosCoordinator.DEFAULT_FALL_CANCEL_SECONDS

    fun records(context: Context): StateFlow<List<SosRecord>> = recordsFlow(context).asStateFlow()

    fun addRecord(context: Context, record: SosRecord) {
        val next = (listOf(record) + recordsFlow(context).value).take(MAX_RECORDS)
        val json = JSONArray()
        next.forEach { r ->
            json.put(
                JSONObject()
                    .put("at", r.at)
                    .put("number", r.number)
                    .put("reasons", JSONArray(r.reasons))
                    .put("sms", r.sms.name)
                    .put("call", r.call?.name ?: JSONObject.NULL)
                    .put("test", r.test)
            )
        }
        prefs(context).edit { putString(KEY_RECORDS, json.toString()) }
        recordsFlow(context).value = next
    }

    private fun settingsFlow(context: Context) = settings ?: synchronized(this) {
        settings ?: MutableStateFlow(
            prefs(context).let {
                Settings(
                    enabled = it.getBoolean(KEY_ENABLED, false),
                    callAfter = it.getBoolean(KEY_CALL_AFTER, false),
                    fallCancelSeconds = cancelWindow(it.getInt(KEY_FALL_CANCEL, SosCoordinator.DEFAULT_FALL_CANCEL_SECONDS))
                )
            }
        ).also { settings = it }
    }

    private fun recordsFlow(context: Context) = records ?: synchronized(this) {
        records ?: MutableStateFlow(readRecords(context)).also { records = it }
    }

    private fun readRecords(context: Context): List<SosRecord> = runCatching {
        val json = JSONArray(prefs(context).getString(KEY_RECORDS, "[]"))
        (0 until json.length()).map { i ->
            val o = json.getJSONObject(i)
            val reasons = o.getJSONArray("reasons")
            SosRecord(
                at = o.getLong("at"),
                number = o.getString("number"),
                reasons = (0 until reasons.length()).map { reasons.getString(it) },
                sms = SosResult.valueOf(o.getString("sms")),
                call = if (o.isNull("call")) null else SosResult.valueOf(o.getString("call")),
                test = o.optBoolean("test", false)
            )
        }
    }.getOrDefault(emptyList())
}
