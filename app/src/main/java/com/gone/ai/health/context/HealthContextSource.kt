package com.gone.ai.health.context

import android.content.Context
import androidx.core.content.edit
import com.gone.ai.data.UserProfile
import com.gone.ai.data.library.GoneDatabase
import com.gone.ai.health.service.HealthMonitoringService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Reads what [HealthContextBuilder] needs from the database, and holds the person's choice
 * of whether the chat may use it at all.
 */
class HealthContextSource private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val db = GoneDatabase.getInstance(appContext)
    private val prefs = appContext.getSharedPreferences(UserProfile.PREFS_NAME, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    /** "Use my health data" in chat. On unless the person switches it off. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_ENABLED, value) }
        _enabled.value = value
    }

    /** What is stored, for choosing chat suggestions. */
    data class Availability(val hasReports: Boolean, val hasRecentAlerts: Boolean, val hasRecords: Boolean)

    suspend fun availability(now: Long = System.currentTimeMillis()): Availability = withContext(Dispatchers.IO) {
        val patient = HealthMonitoringService.DEFAULT_PATIENT_ID
        Availability(
            hasReports = db.sessionReportDao().recent(patient, 1).isNotEmpty(),
            hasRecentAlerts = db.anomalyDao().since(patient, now - HealthContextBuilder.ALERT_WINDOW_MILLIS).isNotEmpty(),
            hasRecords = db.libraryDao().sharedWithAi().isNotEmpty()
        )
    }

    /** The context text as it would be sent now. */
    suspend fun build(now: Long = System.currentTimeMillis()): String = withContext(Dispatchers.IO) {
        val patient = HealthMonitoringService.DEFAULT_PATIENT_ID
        HealthContextBuilder.build(
            HealthContextBuilder.Input(
                now = now,
                age = UserProfile.load(appContext).age,
                latestReading = db.vitalsDao().latest(patient, 1).firstOrNull(),
                reports = db.sessionReportDao().recent(patient, HealthContextBuilder.MAX_REPORTS),
                alerts = db.anomalyDao().since(patient, now - HealthContextBuilder.ALERT_WINDOW_MILLIS),
                records = db.libraryDao().sharedWithAi()
            )
        )
    }

    companion object {
        private const val KEY_ENABLED = "chat_use_health_data"

        @Volatile private var INSTANCE: HealthContextSource? = null

        fun getInstance(context: Context): HealthContextSource =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: HealthContextSource(context).also { INSTANCE = it }
            }
    }
}
