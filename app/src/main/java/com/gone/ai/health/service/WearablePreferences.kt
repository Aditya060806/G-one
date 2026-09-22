package com.gone.ai.health.service

import android.content.Context
import androidx.core.content.edit
import com.gone.ai.health.domain.AnomalyThresholds
import com.gone.ai.health.domain.EmgCalibration
import com.gone.ai.health.source.VitalsScenario
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wearable settings that outlive the monitoring service.
 *
 *  - Whether monitoring is WANTED, so a service the system stopped (Samsung stops background
 *    apps aggressively) resumes by itself instead of silently ending.
 *  - Whether to sync automatically when the wearable comes into range.
 *  - The wearer's calibrated EMG levels, which replace the uncalibrated defaults.
 *  - When the live graphs last started over, so they begin from nothing on each run.
 */
object WearablePreferences {

    private const val PREFS = "gone_wearable"
    private const val KEY_WANTED_SCENARIO = "monitoring_wanted_scenario"
    private const val KEY_AUTO_SYNC = "auto_sync_nearby"
    private const val KEY_EMG_RELAXED = "emg_relaxed"
    private const val KEY_EMG_CLENCH = "emg_clench"
    private const val KEY_EMG_ACTIVE = "emg_active"
    private const val KEY_EMG_HIGH = "emg_high"
    private const val KEY_EMG_VERY_HIGH = "emg_very_high"
    private const val KEY_CHARTS_FROM = "charts_from"

    @Volatile private var autoSync: MutableStateFlow<Boolean>? = null
    @Volatile private var emg: MutableStateFlow<EmgCalibration.Result.Levels?>? = null
    @Volatile private var chartsFrom: MutableStateFlow<Long?>? = null

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Monitoring wanted ────────────────────────────────────────────────────

    /** Monitoring was started with [scenario] and has not been stopped by the person. */
    fun setMonitoringWanted(context: Context, scenario: VitalsScenario?) {
        prefs(context).edit {
            if (scenario == null) remove(KEY_WANTED_SCENARIO) else putString(KEY_WANTED_SCENARIO, scenario.name)
        }
    }

    /** The scenario monitoring should resume with, or null when it should stay stopped. */
    fun wantedScenario(context: Context): VitalsScenario? =
        prefs(context).getString(KEY_WANTED_SCENARIO, null)?.let { runCatching { VitalsScenario.valueOf(it) }.getOrNull() }

    // ── Auto-sync ────────────────────────────────────────────────────────────

    fun autoSync(context: Context): StateFlow<Boolean> = autoSyncFlow(context).asStateFlow()

    fun setAutoSync(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_AUTO_SYNC, enabled) }
        autoSyncFlow(context).value = enabled
    }

    private fun autoSyncFlow(context: Context) = autoSync ?: synchronized(this) {
        autoSync ?: MutableStateFlow(prefs(context).getBoolean(KEY_AUTO_SYNC, false)).also { autoSync = it }
    }

    // ── Live graphs ──────────────────────────────────────────────────────────

    /** When the live graphs last started over; null before the first run on this install. */
    fun chartsFrom(context: Context): StateFlow<Long?> = chartsFromFlow(context).asStateFlow()

    /** Monitoring or a session started at [at]: the live graphs begin again from there. */
    fun restartCharts(context: Context, at: Long) {
        prefs(context).edit { putLong(KEY_CHARTS_FROM, at) }
        chartsFromFlow(context).value = at
    }

    private fun chartsFromFlow(context: Context) = chartsFrom ?: synchronized(this) {
        chartsFrom ?: MutableStateFlow(
            prefs(context).getLong(KEY_CHARTS_FROM, 0L).takeIf { it > 0 }
        ).also { chartsFrom = it }
    }

    // ── EMG calibration ──────────────────────────────────────────────────────

    fun emgLevels(context: Context): StateFlow<EmgCalibration.Result.Levels?> = emgFlow(context).asStateFlow()

    /** Saves the wearer's levels, or with null goes back to the defaults. */
    fun saveEmgLevels(context: Context, levels: EmgCalibration.Result.Levels?) {
        prefs(context).edit {
            if (levels == null) {
                listOf(KEY_EMG_RELAXED, KEY_EMG_CLENCH, KEY_EMG_ACTIVE, KEY_EMG_HIGH, KEY_EMG_VERY_HIGH).forEach(::remove)
            } else {
                putInt(KEY_EMG_RELAXED, levels.relaxed)
                putInt(KEY_EMG_CLENCH, levels.clench)
                putInt(KEY_EMG_ACTIVE, levels.active)
                putInt(KEY_EMG_HIGH, levels.high)
                putInt(KEY_EMG_VERY_HIGH, levels.veryHigh)
            }
        }
        emgFlow(context).value = levels
    }

    /** The detection thresholds with this wearer's EMG levels, when calibrated. */
    fun thresholds(context: Context): AnomalyThresholds {
        val levels = emgFlow(context).value ?: return AnomalyThresholds.DEFAULT
        return runCatching { levels.applyTo(AnomalyThresholds.DEFAULT) }.getOrDefault(AnomalyThresholds.DEFAULT)
    }

    private fun emgFlow(context: Context) = emg ?: synchronized(this) {
        emg ?: MutableStateFlow(readEmg(context)).also { emg = it }
    }

    private fun readEmg(context: Context): EmgCalibration.Result.Levels? {
        val p = prefs(context)
        if (!p.contains(KEY_EMG_HIGH)) return null
        return EmgCalibration.Result.Levels(
            relaxed = p.getInt(KEY_EMG_RELAXED, 0),
            clench = p.getInt(KEY_EMG_CLENCH, 0),
            active = p.getInt(KEY_EMG_ACTIVE, 0),
            high = p.getInt(KEY_EMG_HIGH, 0),
            veryHigh = p.getInt(KEY_EMG_VERY_HIGH, 0)
        )
    }
}
