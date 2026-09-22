package com.gone.ai.health.ui

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gone.ai.health.data.AnomalyEventEntity
import com.gone.ai.health.data.EventStatus
import com.gone.ai.health.data.HealthSessionEntity
import com.gone.ai.health.data.RoomHealthRepository
import com.gone.ai.health.data.SessionReportEntity
import com.gone.ai.health.data.SessionRepository
import com.gone.ai.health.data.VitalsReadingEntity
import com.gone.ai.health.domain.AnomalyThresholds
import com.gone.ai.health.domain.EmgCalibration
import com.gone.ai.health.domain.Severity
import com.gone.ai.health.domain.VitalsSample
import com.gone.ai.health.service.HealthMonitoringService
import com.gone.ai.health.service.ReportArtifacts
import com.gone.ai.health.service.MonitoringDataSource
import com.gone.ai.health.service.MonitoringSnapshot
import com.gone.ai.health.service.MonitoringState
import com.gone.ai.health.service.SavedWearable
import com.gone.ai.health.service.WearableAutoSync
import com.gone.ai.health.service.WearableLiveState
import com.gone.ai.health.service.WearablePreferences
import com.gone.ai.health.source.ble.NearbyWearable
import com.gone.ai.health.source.VitalsScenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

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
    /** The engine's thresholds, with the wearer's calibrated EMG levels when there are any. */
    val thresholds: AnomalyThresholds get() = WearablePreferences.thresholds(getApplication())

    // ── Live service state ────────────────────────────────────────────────────

    val isMonitoring: StateFlow<Boolean> = HealthMonitoringService.isRunning
    val snapshot: StateFlow<MonitoringSnapshot> = HealthMonitoringService.snapshot

    /** True when monitoring streams simulated vitals; false means no source exists yet. */
    val simulationEnabled: StateFlow<Boolean> = MonitoringDataSource.simulationEnabled(app)

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

    /**
     * [history] since the live graphs last started over, when monitoring or a session began:
     * what the Live Monitor and Home's tiles show, so each run starts from nothing. Trails and
     * the trends keep reading [history].
     */
    val liveHistory: StateFlow<List<VitalsReadingEntity>> =
        combine(history, WearablePreferences.chartsFrom(app)) { readings, from -> liveReadings(readings, from) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Scenario, range and filter selection ──────────────────────────────────

    private val _scenario = MutableStateFlow(VitalsScenario.HEALTHY_BASELINE)
    val scenario: StateFlow<VitalsScenario> = _scenario.asStateFlow()

    private val _range = MutableStateFlow(HistoryRange.LAST_HOUR)
    val range: StateFlow<HistoryRange> = _range.asStateFlow()

    private val _statusFilter = MutableStateFlow<EventStatus?>(null)
    val statusFilter: StateFlow<EventStatus?> = _statusFilter.asStateFlow()

    fun setScenario(value: VitalsScenario) { _scenario.value = value }
    fun setRange(value: HistoryRange) { _range.value = value }
    fun setStatusFilter(value: EventStatus?) { _statusFilter.value = value }

    // ── User-facing notices ───────────────────────────────────────────────────

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** One-off messages, such as why monitoring could not start. Shown once each. */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    init {
        viewModelScope.launch {
            HealthMonitoringService.startProblem.filterNotNull().collect { problem ->
                _notices.emit(problem)
                HealthMonitoringService.consumeStartProblem()
            }
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    /**
     * Start monitoring, or explain why it cannot start.
     *
     * Call [HealthMonitoringService.permissionsToRequest] first and request whatever it
     * returns; this method re-checks rather than trusting that it happened.
     */
    fun startMonitoring(context: Context) {
        when (val result = HealthMonitoringService.start(context, _scenario.value, patientId)) {
            HealthMonitoringService.StartResult.Started -> Unit
            is HealthMonitoringService.StartResult.NotStarted -> _notices.tryEmit(result.reason)
        }
    }

    private val _stopPrompt = MutableStateFlow(false)
    /** True while asking whether stopping monitoring should also end the running session. */
    val stopPrompt: StateFlow<Boolean> = _stopPrompt.asStateFlow()

    /**
     * Stop monitoring. With a session running, ask first: a session left open while nothing
     * is recorded would later report a long gap the person did not intend.
     */
    fun stopMonitoring(context: Context) {
        if (activeSession.value != null) _stopPrompt.value = true
        else HealthMonitoringService.stop(context)
    }

    /** Answer to [stopPrompt]: [endSession] true ends it with a report, false leaves it open, null cancels. */
    fun answerStopPrompt(context: Context, endSession: Boolean?) {
        _stopPrompt.value = false
        when (endSession) {
            null -> Unit
            true -> {
                finishSession()
                HealthMonitoringService.stop(context)
            }
            false -> HealthMonitoringService.stop(context)
        }
    }

    /** Mark an alert as seen. Distinct from hiding its banner, which records nothing. */
    fun acknowledge(eventId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.acknowledge(eventId, System.currentTimeMillis()) }
        }
    }

    // ── Wearable ──────────────────────────────────────────────────────────────

    /** The link while monitoring streams from a wearable. */
    val wearable: StateFlow<WearableLiveState> = HealthMonitoringService.wearable

    val savedWearable: StateFlow<SavedWearable?> = MonitoringDataSource.wearable(app)

    /**
     * Choose a wearable. Monitoring that is already streaming from another one is stopped:
     * silently carrying on with the old device while the screen shows the new one would be
     * a lie about where the readings come from.
     */
    fun chooseWearable(context: Context, device: NearbyWearable) {
        val previous = savedWearable.value
        MonitoringDataSource.saveWearable(context, SavedWearable(device.address, device.name ?: device.address))
        if (previous?.address != device.address && isMonitoring.value && !simulationEnabled.value) {
            HealthMonitoringService.stop(context)
            _notices.tryEmit("Monitoring stopped so it can start again with ${device.name ?: device.address}.")
        }
    }

    fun forgetWearable(context: Context) {
        if (isMonitoring.value && !simulationEnabled.value) HealthMonitoringService.stop(context)
        WearableAutoSync.disable(context, savedWearable.value?.address)
        WearablePreferences.setAutoSync(context, false)
        MonitoringDataSource.forgetWearable(context)
    }

    // ── Wearable sync ─────────────────────────────────────────────────────────

    /** Connect now instead of waiting to retry, or start monitoring when it is off. */
    fun syncNow(context: Context) {
        when (val result = HealthMonitoringService.syncNow(context)) {
            HealthMonitoringService.StartResult.Started -> Unit
            is HealthMonitoringService.StartResult.NotStarted -> _notices.tryEmit(result.reason)
        }
    }

    val autoSync: StateFlow<Boolean> = WearablePreferences.autoSync(app)
    val autoSyncSupported: Boolean = WearableAutoSync.isSupported(app)

    /** Android's dialog approved the association: watch for the wearable from now on. */
    fun autoSyncApproved(context: Context) {
        val address = savedWearable.value?.address ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (WearableAutoSync.startObserving(context, address)) {
            WearablePreferences.setAutoSync(context, true)
            _notices.tryEmit("G-one will start monitoring when ${savedWearable.value?.name ?: "the wearable"} is nearby.")
        } else {
            _notices.tryEmit("Android did not agree to watch for the wearable. Try again, or start monitoring yourself.")
        }
    }

    fun autoSyncFailed(reason: String) {
        _notices.tryEmit("Automatic sync was not turned on: $reason")
    }

    fun disableAutoSync(context: Context) {
        WearableAutoSync.disable(context, savedWearable.value?.address)
        WearablePreferences.setAutoSync(context, false)
    }

    // ── EMG calibration ───────────────────────────────────────────────────────

    val emgLevels: StateFlow<EmgCalibration.Result.Levels?> = WearablePreferences.emgLevels(app)

    sealed interface Calibration {
        data object Idle : Calibration
        data class Relaxing(val secondsLeft: Int, val readings: Int) : Calibration
        data class GetReady(val secondsLeft: Int) : Calibration
        data class Clenching(val secondsLeft: Int, val readings: Int) : Calibration
        data class Finished(val result: EmgCalibration.Result) : Calibration
    }

    private val _calibration = MutableStateFlow<Calibration>(Calibration.Idle)
    val calibration: StateFlow<Calibration> = _calibration.asStateFlow()
    private var calibrationJob: Job? = null

    /** Relax, get ready, clench — reading live EMG levels from the wearable while monitoring runs. */
    fun startCalibration() {
        calibrationJob?.cancel()
        calibrationJob = viewModelScope.launch {
            val relaxed = readEmgFor(EmgCalibration.RELAX_SECONDS) { left, n -> Calibration.Relaxing(left, n) }
            for (left in EmgCalibration.GET_READY_SECONDS downTo 1) {
                _calibration.value = Calibration.GetReady(left)
                delay(1_000)
            }
            val clench = readEmgFor(EmgCalibration.CLENCH_SECONDS) { left, n -> Calibration.Clenching(left, n) }
            _calibration.value = Calibration.Finished(EmgCalibration.compute(relaxed, clench))
        }
    }

    private suspend fun readEmgFor(seconds: Int, state: (Int, Int) -> Calibration): List<Int> = coroutineScope {
        val values = mutableListOf<Int>()
        val reader = launch { HealthMonitoringService.emgLive.collect { values += it } }
        for (left in seconds downTo 1) {
            _calibration.value = state(left, values.size)
            delay(1_000)
        }
        reader.cancel()
        values.toList()
    }

    /** Keep the levels. Monitoring uses them from its next start. */
    fun saveCalibration(context: Context, levels: EmgCalibration.Result.Levels) {
        WearablePreferences.saveEmgLevels(context, levels)
        _calibration.value = Calibration.Idle
        if (isMonitoring.value) {
            _notices.tryEmit("Saved. Stop and start monitoring to use the new muscle levels.")
        }
    }

    fun useDefaultEmgLevels(context: Context) {
        WearablePreferences.saveEmgLevels(context, null)
    }

    fun closeCalibration() {
        calibrationJob?.cancel()
        _calibration.value = Calibration.Idle
    }

    // ── Sessions and reports ──────────────────────────────────────────────────

    private val sessions = SessionRepository.from(app)
    private val artifacts = ReportArtifacts.getInstance(app)

    // Eager: the stop prompt reads it from screens that do not otherwise collect it.
    val activeSession: StateFlow<HealthSessionEntity?> =
        sessions.observeActive(patientId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** PDFs and summaries in progress, per report id. */
    val reportFiles: StateFlow<Map<Long, ReportArtifacts.Status>> = artifacts.status

    val reports: StateFlow<List<SessionReportEntity>> =
        sessions.observeReports(patientId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _sessionBusy = MutableStateFlow(false)
    /** True while a report is being built. */
    val sessionBusy: StateFlow<Boolean> = _sessionBusy.asStateFlow()

    private val _openReport = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    /** A report that has just been built and should be shown. */
    val openReport: SharedFlow<Long> = _openReport.asSharedFlow()

    /** The report whose summary the model is writing, if any. */
    val summarising: StateFlow<Long?> =
        artifacts.status
            .map { all -> all.entries.firstOrNull { it.value.addingSummary }?.key }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The report's PDF, written first if it does not exist yet. */
    suspend fun reportPdf(reportId: Long): File? = artifacts.ensurePdf(reportId)

    /** Where a report's PDF is kept (it may not have been written yet). */
    fun reportPdfFile(report: SessionReportEntity): File = artifacts.pdfFile(report)

    fun observeReport(reportId: Long): Flow<SessionReportEntity?> = sessions.observeReport(reportId)

    fun startSession() {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            runCatching { sessions.start(patientId, now) }
                // The session's graphs start from nothing, as its report does.
                .onSuccess {
                    WearablePreferences.restartCharts(getApplication(), now)
                    HealthMonitoringService.clearLiveTrace()
                }
                .onFailure { _notices.tryEmit("The session could not start: ${it.message}") }
        }
    }

    fun cancelSession() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { sessions.cancel(patientId, System.currentTimeMillis()) }
            _notices.tryEmit("Session cancelled. Its readings stay in your history.")
        }
    }

    fun finishSession() {
        if (_sessionBusy.value) return
        _sessionBusy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val result = sessions.finish(patientId, System.currentTimeMillis(), thresholds)) {
                    is SessionRepository.FinishResult.Reported -> {
                        artifacts.onReportCreated(result.reportId)
                        _openReport.emit(result.reportId)
                    }
                    is SessionRepository.FinishResult.NoReport -> _notices.emit(result.reason)
                    SessionRepository.FinishResult.NotActive -> Unit
                }
            } catch (e: Exception) {
                _notices.emit("The report could not be built: ${e.message}")
            } finally {
                _sessionBusy.value = false
            }
        }
    }

    fun deleteReport(reportId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val report = sessions.reportById(reportId)
                sessions.deleteReport(reportId)
                report?.let { artifacts.onReportDeleted(it) }
            }
        }
    }

    /** Ask the on-device model to reword a report. The original observations always stay. */
    fun summariseReport(report: SessionReportEntity) {
        if (summarising.value != null) return
        viewModelScope.launch(Dispatchers.IO) {
            artifacts.summarizeNow(report.id)?.let { _notices.emit(it) }
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
 * How a motion reading reads to a person, using the engine's own thresholds.
 *
 * Motion is peak accelerometer magnitude in g: about 1.0 at rest (gravity alone), higher
 * while moving, and a spike at or above the fall-impact threshold on a hard knock. The
 * screens once showed a "muscle activity (EMG) µV" figure computed from this motion value
 * plus the clock; real EMG now comes only from the wearable's muscle sensor, see [EmgLevel].
 */
enum class MotionLevel(val label: String) {
    STILL("Still"),
    MOVING("Moving"),
    IMPACT("Impact");

    companion object {
        fun of(motionG: Float?, thresholds: AnomalyThresholds = AnomalyThresholds.DEFAULT): MotionLevel? = when {
            motionG == null -> null
            motionG >= thresholds.fallImpactG -> IMPACT
            motionG > VitalsSample.REST_MOTION_G -> MOVING
            else -> STILL
        }
    }
}

/**
 * How an EMG envelope reads to a person, using the engine's own (uncalibrated) levels.
 *
 * [SENSOR_PROBLEM] is kept apart from the activity levels on purpose: an input pinned at the
 * ADC rail is a detached pad, and showing it as "very high activity" would repeat the
 * mistake REFERENCE made when it called a strong contraction a fall.
 */
enum class EmgLevel(val label: String) {
    RELAXED("Relaxed"),
    ACTIVE("Active"),
    HIGH("High"),
    VERY_HIGH("Very high"),
    SENSOR_PROBLEM("Check pads");

    companion object {
        fun of(envelope: Int?, thresholds: AnomalyThresholds = AnomalyThresholds.DEFAULT): EmgLevel? = when {
            envelope == null -> null
            envelope >= VitalsSample.EMG_RAIL -> SENSOR_PROBLEM
            envelope >= thresholds.emgVeryHighLevel -> VERY_HIGH
            envelope >= thresholds.emgHighLevel -> HIGH
            envelope >= thresholds.emgActiveLevel -> ACTIVE
            else -> RELAXED
        }
    }
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
