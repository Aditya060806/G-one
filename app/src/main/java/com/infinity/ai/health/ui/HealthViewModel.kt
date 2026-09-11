package com.infinity.ai.health.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.infinity.ai.health.data.AnomalyEventEntity
import com.infinity.ai.health.data.EventStatus
import com.infinity.ai.health.data.RoomHealthRepository
import com.infinity.ai.health.data.VitalsReadingEntity
import com.infinity.ai.health.domain.AnomalyThresholds
import com.infinity.ai.health.domain.Severity
import com.infinity.ai.health.service.HealthMonitoringService
import com.infinity.ai.health.service.MonitoringSnapshot
import com.infinity.ai.health.service.MonitoringState
import com.infinity.ai.health.source.VitalsScenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The only bridge between the health spine and Compose.
 *
 * Note what this class does NOT do: it contains no detection logic, no thresholds, and no
 * explanation text. All of that lives in the pure-Kotlin engine, which is why the engine
 * is unit-testable and this class is thin.
 *
 * It also does not bind to the service. [HealthMonitoringService] publishes its state
 * through static `StateFlow`s, so the UI can observe live monitoring without a
 * ServiceConnection, without lifecycle juggling, and without the possibility of a leaked
 * binding.
 */
class HealthViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = RoomHealthRepository.from(app)
    private val patientId = HealthMonitoringService.DEFAULT_PATIENT_ID

    /** Thresholds are needed to colour readings; the engine owns the values. */
    val thresholds: AnomalyThresholds = AnomalyThresholds.DEFAULT

    // ── Live service state ────────────────────────────────────────────────────

    val isMonitoring: StateFlow<Boolean> = HealthMonitoringService.isRunning
    val snapshot: StateFlow<MonitoringSnapshot> = HealthMonitoringService.snapshot

    // ── Persisted state ───────────────────────────────────────────────────────

    val activeEvents: StateFlow<List<AnomalyEventEntity>> =
        repository.observeActiveEvents(patientId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allEvents: StateFlow<List<AnomalyEventEntity>> =
        repository.observeRecentEvents(patientId, EVENT_HISTORY_LIMIT)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Readings in CHRONOLOGICAL order.
     *
     * The DAO returns newest-first because that is what an index range scan serves
     * efficiently. Charts and trends need oldest-first, so the reversal happens here,
     * once, rather than at each call site — the same normalisation the detection engine
     * does with `VitalsWindow.of`.
     */
    val history: StateFlow<List<VitalsReadingEntity>> =
        repository.observeRecentReadings(patientId, READING_HISTORY_LIMIT)
            .map { it.reversed() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Demo scenario selection ───────────────────────────────────────────────

    private val _scenario = MutableStateFlow(VitalsScenario.HEALTHY_BASELINE)
    val scenario: StateFlow<VitalsScenario> = _scenario.asStateFlow()

    private val _range = MutableStateFlow(HistoryRange.LAST_HOUR)
    val range: StateFlow<HistoryRange> = _range.asStateFlow()

    private val _statusFilter = MutableStateFlow<EventStatus?>(null)
    val statusFilter: StateFlow<EventStatus?> = _statusFilter.asStateFlow()

    fun setScenario(value: VitalsScenario) { _scenario.value = value }
    fun setRange(value: HistoryRange) { _range.value = value }
    fun setStatusFilter(value: EventStatus?) { _statusFilter.value = value }

    // ── Actions ───────────────────────────────────────────────────────────────

    fun startMonitoring(context: Context) =
        HealthMonitoringService.start(context, _scenario.value, patientId)

    fun stopMonitoring(context: Context) =
        HealthMonitoringService.stop(context)

    fun acknowledge(eventId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.acknowledge(eventId, System.currentTimeMillis()) }
        }
    }

    // ── Derived helpers for the UI ────────────────────────────────────────────

    /**
     * Severity for a heart-rate reading, used only to colour it.
     *
     * Reads the same thresholds the engine uses so a tile cannot disagree with an alert.
     * Deliberately a presentation concern — it does NOT decide whether an event fires;
     * `AnomalyDetector` does, and it also weighs motion, duration and trend, which is why
     * a tile may show amber without an alert existing.
     */
    fun heartRateSeverity(hr: Int?): Severity? = when {
        hr == null -> null
        hr >= thresholds.hrCriticalHighBpm -> Severity.CRITICAL
        hr >= thresholds.hrHighBpm -> Severity.MODERATE
        hr <= thresholds.hrLowBpm -> Severity.MODERATE
        else -> null
    }

    fun spo2Severity(spo2: Int?): Severity? = when {
        spo2 == null -> null
        spo2 < thresholds.spo2CriticalBelow -> Severity.CRITICAL
        spo2 < thresholds.spo2SustainedBelow -> Severity.MODERATE
        else -> null
    }

    fun temperatureSeverity(temp: Float?): Severity? = when {
        temp == null -> null
        temp >= thresholds.tempCriticalFeverC -> Severity.CRITICAL
        temp >= thresholds.tempFeverC -> Severity.MODERATE
        else -> null
    }

    fun motionSeverity(motion: Float?): Severity? = when {
        motion == null -> null
        motion >= thresholds.fallImpactG -> Severity.CRITICAL
        else -> null
    }

    companion object {
        /** ~2.8 hours at the 5 s sample interval. Bounded to keep charts responsive. */
        const val READING_HISTORY_LIMIT = 2_000
        const val EVENT_HISTORY_LIMIT = 200
    }
}

/** Time windows offered on the History screen. */
enum class HistoryRange(val label: String, val millis: Long) {
    LAST_15_MIN("15m", 15L * 60 * 1000),
    LAST_HOUR("1h", 60L * 60 * 1000),
    LAST_3_HOURS("3h", 3L * 60 * 60 * 1000),
    ALL("All", Long.MAX_VALUE)
}

/**
 * Reduce a series to at most [target] points for rendering.
 *
 * At a 5 s interval an hour is 720 samples; a chart a few hundred pixels wide cannot show
 * them and asking Compose to path through all of them wastes frames. Stride sampling is
 * used rather than averaging so that spikes survive — smoothing away a transient
 * desaturation would defeat the purpose of the chart.
 *
 * The final point is always retained, so the chart's right edge is the newest reading.
 */
fun <T> List<T>.downsample(target: Int): List<T> {
    if (target <= 0 || size <= target) return this
    val stride = size.toFloat() / target
    val out = ArrayList<T>(target + 1)
    var i = 0f
    while (i < size) {
        out += this[i.toInt().coerceAtMost(size - 1)]
        i += stride
    }
    if (out.lastOrNull() !== last()) out += last()
    return out
}

/** True when the pipeline is in a state that should read as "live" in the UI. */
val MonitoringState.isActive: Boolean
    get() = this != MonitoringState.IDLE && this != MonitoringState.ERROR
