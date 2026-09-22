package com.gone.ai.health.service

import android.Manifest
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.gone.ai.ai.repository.AIRepository
import com.gone.ai.ai.repository.ModelLeaseTracker
import com.gone.ai.data.UserProfile
import com.gone.ai.health.data.AnomalyEventEntity
import com.gone.ai.health.data.PatientEntity
import com.gone.ai.health.data.ReadingSource
import com.gone.ai.health.data.RoomHealthRepository
import com.gone.ai.health.detect.AnomalyDetector
import com.gone.ai.health.domain.AnomalyType
import com.gone.ai.health.domain.Severity
import com.gone.ai.health.domain.VitalsSample
import com.gone.ai.health.explain.Explanation
import com.gone.ai.health.sos.AndroidSosSender
import com.gone.ai.health.sos.SosCoordinator
import com.gone.ai.health.sos.SosPolicy
import com.gone.ai.health.sos.SosPreferences
import com.gone.ai.health.source.ParserStats
import com.gone.ai.health.source.SampleAggregator
import com.gone.ai.health.source.SimulatedVitalsSource
import com.gone.ai.health.source.SourceStatus
import com.gone.ai.health.source.VitalsScenario
import com.gone.ai.health.source.WearableStatus
import com.gone.ai.health.source.ble.AndroidBleConnector
import com.gone.ai.health.source.ble.BleUartVitalsSource
import com.gone.ai.health.source.ble.WearableLinkException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The foreground service that runs continuous health monitoring.
 *
 * TWO RULES THIS CLASS EXISTS TO KEEP
 *
 * 1. Once started with `startForegroundService`, it calls `startForeground` before
 *    anything else, on every path. Returning early without it — as the old "no data
 *    source" check did — is a guaranteed crash a few seconds later
 *    (ForegroundServiceDidNotStartInTimeException). [start] therefore refuses up front
 *    when monitoring cannot run, so the service is never launched just to give up.
 *
 * 2. It holds a model lease while monitoring, and nothing more. It used to unload the
 *    shared model in onDestroy, which broke chat and every document tool whenever
 *    monitoring stopped. See [com.gone.ai.ai.repository.ModelLeaseTracker].
 *
 * `foregroundServiceType=connectedDevice` matches the real purpose — streaming from a
 * wearable — and keeps working with the screen off, which BLE links need. On API 34+
 * that type requires a Bluetooth ("Nearby devices") permission at runtime.
 */
class HealthMonitoringService : Service() {

    companion object {
        private const val TAG = "HealthMonitoringSvc"

        const val ACTION_START = "com.gone.ai.health.START_MONITORING"
        const val ACTION_STOP = "com.gone.ai.health.STOP_MONITORING"
        /** "I'm OK" on the SOS countdown. */
        const val ACTION_CANCEL_SOS = "com.gone.ai.health.CANCEL_SOS"
        const val EXTRA_SCENARIO = "scenario"
        const val EXTRA_PATIENT_ID = "patient_id"

        const val DEFAULT_PATIENT_ID = "local-patient"
        /** 1 Hz would be 86,400 rows a day for no clinical gain; 5 s is ample. */
        const val DEFAULT_SAMPLE_INTERVAL_MILLIS = 5_000L
        /** Readings older than this are trimmed. */
        const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000
        private const val RETENTION_TRIM_INTERVAL_MILLIS = 6L * 60 * 60 * 1000

        const val NO_SOURCE_MESSAGE =
            "No data source chosen. Pick your wearable on the Device screen, or turn on " +
                "simulated vitals in Settings."
        const val PERMISSION_MESSAGE =
            "Allow the Nearby devices permission so G-one can keep monitoring in the background."

        private val _running = MutableStateFlow(false)
        /** Observable by the UI without binding to the service. */
        val isRunning: StateFlow<Boolean> = _running.asStateFlow()

        private val _snapshot = MutableStateFlow(MonitoringSnapshot())
        val snapshot: StateFlow<MonitoringSnapshot> = _snapshot.asStateFlow()

        private val _wearable = MutableStateFlow(WearableLiveState())
        /** The wearable link while streaming from one; the default state otherwise. */
        val wearable: StateFlow<WearableLiveState> = _wearable.asStateFlow()

        private val _pendingSos = MutableStateFlow<SosCoordinator.Pending?>(null)
        /** An SOS waiting out its countdown, for the in-app "I'm OK" card. */
        val pendingSos: StateFlow<SosCoordinator.Pending?> = _pendingSos.asStateFlow()

        @Volatile private var activeSos: SosCoordinator? = null
        /** Set when the wearer stops monitoring, which counts as "I'm OK" for a waiting SOS. */
        @Volatile private var stoppedByWearer = false

        /** "I'm OK" from inside the app. False when no SOS was waiting. */
        fun cancelSos(): Boolean = activeSos?.cancel() ?: false

        /** A session started mid-run: its live muscle trace begins from nothing, like its graphs. */
        fun clearLiveTrace() {
            _wearable.update { it.copy(emgTrace = emptyList()) }
        }

        /** The wearable link while one is open, so "Sync now" and Bluetooth coming back on can reach it. */
        @Volatile private var activeSource: BleUartVitalsSource? = null

        private val _emgLive = MutableSharedFlow<Int>(extraBufferCapacity = 64)
        /** Each live EMG level from the wearable as it arrives, for calibration. */
        val emgLive: SharedFlow<Int> = _emgLive.asSharedFlow()

        private val _startProblem = MutableStateFlow<String?>(null)
        /** Why the service stopped itself — at start, or when the wearable link failed for good. */
        val startProblem: StateFlow<String?> = _startProblem.asStateFlow()

        fun consumeStartProblem() {
            _startProblem.value = null
        }

        fun updateSnapshot(snapshot: MonitoringSnapshot) {
            _snapshot.value = snapshot
        }

        /**
         * Permissions worth requesting before [start]: notifications so alerts can be
         * shown, and the Bluetooth permission — on Android 12+ whenever a wearable is the
         * source, and on 14+ always, because the service type requires it.
         */
        fun permissionsToRequest(context: Context): List<String> = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !granted(context, Manifest.permission.POST_NOTIFICATIONS)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            bluetoothPermissionNeeded(context)?.takeIf { !granted(context, it) }?.let { add(it) }
        }

        fun start(
            context: Context,
            scenario: VitalsScenario = VitalsScenario.HEALTHY_BASELINE,
            patientId: String = DEFAULT_PATIENT_ID
        ): StartResult {
            if (!MonitoringDataSource.isSimulationEnabled(context) &&
                MonitoringDataSource.savedWearable(context) == null
            ) {
                return StartResult.NotStarted(NO_SOURCE_MESSAGE)
            }
            if (bluetoothPermissionNeeded(context)?.let { !granted(context, it) } == true) {
                return StartResult.NotStarted(PERMISSION_MESSAGE)
            }

            val intent = Intent(context, HealthMonitoringService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SCENARIO, scenario.name)
                putExtra(EXTRA_PATIENT_ID, patientId)
            }
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                WearablePreferences.setMonitoringWanted(context, scenario)
                StartResult.Started
            } catch (e: Exception) {
                // e.g. ForegroundServiceStartNotAllowedException when not in the foreground.
                Log.e(TAG, "Could not start monitoring service", e)
                StartResult.NotStarted("Monitoring could not start: ${e.message ?: "the system refused"}")
            }
        }

        /**
         * `stopService` rather than `startService(ACTION_STOP)`: the latter created a
         * fresh service instance just to destroy it when monitoring was not running.
         */
        fun stop(context: Context) {
            stoppedByWearer = true
            WearablePreferences.setMonitoringWanted(context, null)
            context.stopService(Intent(context, HealthMonitoringService::class.java))
        }

        /**
         * Sync with the wearable now: retry a waiting link at once, or start monitoring when it
         * is not running.
         */
        fun syncNow(context: Context): StartResult {
            val source = activeSource
            if (_running.value && source != null) {
                source.syncNow()
                return StartResult.Started
            }
            if (_running.value) return StartResult.Started
            return start(context, WearablePreferences.wantedScenario(context) ?: VitalsScenario.HEALTHY_BASELINE)
        }

        /**
         * The Bluetooth permission monitoring needs here, or null: API 34+ for the
         * connectedDevice service type, API 31+ to talk to a wearable at all.
         */
        private fun bluetoothPermissionNeeded(context: Context): String? = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> Manifest.permission.BLUETOOTH_CONNECT
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !MonitoringDataSource.isSimulationEnabled(context) ->
                Manifest.permission.BLUETOOTH_CONNECT
            else -> null
        }

        private fun granted(context: Context, permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /** Outcome of a [start] request. */
    sealed interface StartResult {
        data object Started : StartResult
        data class NotStarted(val reason: String) : StartResult
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var streamJob: Job? = null

    private lateinit var aiRepository: AIRepository
    private lateinit var healthRepository: RoomHealthRepository
    private lateinit var pipeline: MonitoringPipeline
    private var modelLease: ModelLeaseTracker.Lease? = null
    private var patientId: String = DEFAULT_PATIENT_ID
    private lateinit var sos: SosCoordinator
    /** Only a real wearable raises an SOS: simulated vitals are not a person. */
    @Volatile private var streamingFromWearable = false

    override fun onCreate() {
        super.onCreate()
        HealthNotifications.createChannels(this)

        healthRepository = RoomHealthRepository.from(this)
        aiRepository = AIRepository.getInstance(this)

        sos = SosCoordinator(
            scope = scope,
            config = { SosPreferences.config(this) },
            sender = AndroidSosSender(this),
            onRecord = { record ->
                SosPreferences.addRecord(this, record)
                HealthNotifications.notifySosResult(this, record)
            }
        )
        activeSos = sos
        scope.launch {
            sos.pending.collect { waiting ->
                _pendingSos.value = waiting?.takeIf { SosPreferences.config(this@HealthMonitoringService)?.countdownSeconds != 0 }
                if (waiting != null && SosPreferences.config(this@HealthMonitoringService)?.countdownSeconds != 0) HealthNotifications.showSosCountdown(this@HealthMonitoringService, waiting)
                else HealthNotifications.cancelSosCountdown(this@HealthMonitoringService)
            }
        }

        pipeline = MonitoringPipeline(
            repository = healthRepository,
            // The wearer's calibrated EMG levels when there are any.
            detector = AnomalyDetector(WearablePreferences.thresholds(this)),
            alerts = SosAwareAlertSink(NotificationAlertSink(this)),
            // The model is an optional enhancement. Monitoring is fully functional
            // without it, which is what keeps it off the critical path.
            explainer = LlamaAiExplainer(aiRepository)
        )

        scope.launch {
            pipeline.snapshot.collect { _snapshot.value = it }
        }

        ContextCompat.registerReceiver(
            this, bluetoothState, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** Reconnects as soon as Bluetooth is back on, instead of at the end of the current wait. */
    private val bluetoothState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_ON) {
                activeSource?.syncNow()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                patientId = intent.getStringExtra(EXTRA_PATIENT_ID) ?: DEFAULT_PATIENT_ID
                val scenario = intent.getStringExtra(EXTRA_SCENARIO)
                    ?.let { runCatching { VitalsScenario.valueOf(it) }.getOrNull() }
                    ?: VitalsScenario.HEALTHY_BASELINE
                if (!begin(scenario)) return START_NOT_STICKY
            }
            ACTION_CANCEL_SOS -> {
                sos.cancel()
                // The notification's button can reach a service that has since stopped; one
                // started just for this has nothing to do.
                if (!_running.value) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            ACTION_STOP -> {
                Log.i(TAG, "Stop requested")
                stoppedByWearer = true
                WearablePreferences.setMonitoringWanted(this, null)
                stopSelf()
                return START_NOT_STICKY
            }
            null -> {
                // Restarted by the system after it stopped the app. Resume only what the person
                // started and has not stopped, with the scenario they chose.
                val wanted = WearablePreferences.wantedScenario(this)
                if (!_running.value) {
                    if (wanted == null) {
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    Log.i(TAG, "Resuming monitoring after the system stopped it")
                    if (!begin(wanted)) return START_NOT_STICKY
                }
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent?.action}")
                if (!_running.value) stopSelf()
            }
        }
        // Sticky: if the system stops the app while monitoring, it restarts the service, which
        // resumes from the saved choice above.
        return if (_running.value) START_STICKY else START_NOT_STICKY
    }

    /** Enters the foreground and starts streaming. False when it could not, and the service is stopping. */
    private fun begin(scenario: VitalsScenario): Boolean {
        // [start] checks these too; read again because a setting may have changed
        // between the request and this command being delivered.
        val simulated = MonitoringDataSource.isSimulationEnabled(this)
        val device = MonitoringDataSource.savedWearable(this)

        // Rule 1: enter the foreground first, whatever happens next.
        val text = when {
            simulated -> "Watching your vitals · ${scenario.displayName}"
            device != null -> "Connecting to ${device.name}"
            else -> "Starting"
        }
        if (!enterForeground(text)) {
            stopSelf()
            return false
        }

        when {
            simulated -> startSimulated(scenario)
            device != null -> startWearable(device)
            else -> {
                _startProblem.value = NO_SOURCE_MESSAGE
                WearablePreferences.setMonitoringWanted(this, null)
                stopSelf()
                return false
            }
        }
        return true
    }

    private fun enterForeground(text: String): Boolean {
        val notification = HealthNotifications.buildMonitoringNotification(this, text)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    HealthNotifications.NOTIF_ID_MONITORING,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(HealthNotifications.NOTIF_ID_MONITORING, notification)
            }
            true
        } catch (e: Exception) {
            // API 34+ throws SecurityException when the connectedDevice type's permission
            // is missing. [start] checks for it; this covers a revoke in between.
            Log.e(TAG, "Could not enter the foreground", e)
            _startProblem.value =
                if (e is SecurityException) PERMISSION_MESSAGE
                else "Monitoring could not start: ${e.message ?: "the system refused"}"
            false
        }
    }

    private fun startSimulated(scenario: VitalsScenario) {
        streamingFromWearable = false
        val vitalsSource = SimulatedVitalsSource(
            scenario = scenario,
            intervalMillis = DEFAULT_SAMPLE_INTERVAL_MILLIS,
            startAt = System.currentTimeMillis()
        )
        launchMonitoring {
            vitalsSource.stream().collect { sample -> process(sample, ReadingSource.SIMULATED, replayed = false) }
        }
    }

    /**
     * Streams from the saved wearable.
     *
     * The wearable sends two lines a second; [SampleAggregator] turns them into one reading
     * per 5 seconds for the pipeline, while the raw EMG values feed the on-screen trace.
     * Live and SD-card backlog lines are aggregated separately — mixing them would average
     * a reading from three hours ago into one from now. The backlog comes first on every
     * reconnect, so the first live line closes whatever backlog bucket was still open.
     *
     * A link failure that retrying cannot fix ends monitoring and says why, rather than
     * leaving a foreground notification claiming to monitor while nothing arrives.
     */
    private fun startWearable(device: SavedWearable) {
        streamingFromWearable = true
        val source = BleUartVitalsSource(
            connector = AndroidBleConnector(this, device.address),
            address = device.address,
            displayName = device.name
        )
        _wearable.value = WearableLiveState(deviceName = device.name, address = device.address)
        activeSource = source

        launchMonitoring {
            launch { publishWearableState(source, device) }

            val live = SampleAggregator()
            val backlog = SampleAggregator()
            try {
                source.packets().collect { packet ->
                    val sample = packet.sample
                    if (packet.backlog) {
                        _wearable.update { it.copy(backlogLines = it.backlogLines + 1) }
                        if (sample != null) {
                            // A closed bucket that is stored releases its records for
                            // acknowledgement. One that failed to store keeps them held, so the
                            // wearable keeps them and sends them again.
                            backlog.add(sample)?.let { if (process(it, ReadingSource.BLE, replayed = true)) source.stored() }
                            packet.seq?.let(source::holding)
                        }
                        return@collect
                    }
                    if (packet.caughtUp || sample != null) {
                        // Nothing more is coming from the wearable's store: keep the last,
                        // partly filled bucket rather than waiting for more of it.
                        val stored = backlog.flush()?.let { process(it, ReadingSource.BLE, replayed = true) } ?: true
                        if (stored) source.stored()
                    }
                    if (sample == null) return@collect
                    sample.emgMean?.let { level ->
                        _emgLive.tryEmit(level)
                        _wearable.update {
                            it.copy(emgTrace = (it.emgTrace + level).takeLast(WearableLiveState.TRACE_LENGTH))
                        }
                    }
                    live.add(sample)?.let { process(it, ReadingSource.BLE, replayed = false) }
                }
            } catch (e: WearableLinkException) {
                Log.w(TAG, "Wearable link failed for good: ${e.message}")
                _startProblem.value = e.message
                // Retrying after a restart cannot fix this either.
                WearablePreferences.setMonitoringWanted(this@HealthMonitoringService, null)
                stopSelf()
            } finally {
                // Readings already received are not thrown away when monitoring stops.
                withContext(NonCancellable) {
                    backlog.flush()?.let { process(it, ReadingSource.BLE, replayed = true) }
                    live.flush()?.let { process(it, ReadingSource.BLE, replayed = false) }
                }
            }
        }
    }

    /** Mirrors the link into [wearable], keeps the notification honest, and records the device. */
    private suspend fun publishWearableState(source: BleUartVitalsSource, device: SavedWearable) = coroutineScope {
        launch {
            combine(
                source.status, source.deviceStatus, source.lastDataAt, source.parserStats, source.clockSyncFailed
            ) { link, sensors, lastDataAt, parser, syncFailed ->
                LinkFields(link, sensors, lastDataAt, parser, syncFailed)
            }.combine(source.sync) { fields, sync -> fields to sync }
                .collect { (f, sync) ->
                    // update, not value = copy: the stream collector updates the EMG trace and
                    // backlog count on another coroutine, and a plain copy would overwrite them.
                    _wearable.update {
                        it.copy(
                            link = f.link, sensors = f.sensors, lastDataAt = f.lastDataAt,
                            parser = f.parser, clockSyncFailed = f.clockSyncFailed, sync = sync
                        )
                    }
                }
        }
        source.status
            .map { it::class }
            .distinctUntilChanged()
            .collect {
                when (val status = source.status.value) {
                    is SourceStatus.Streaming -> {
                        enterForeground("Streaming from ${device.name}")
                        runCatching { healthRepository.rememberDevice(patientId, device.address, device.name, System.currentTimeMillis()) }
                            .onFailure { e -> Log.w(TAG, "Could not record the wearable", e) }
                    }
                    is SourceStatus.Recovering -> enterForeground("Reconnecting to ${device.name}…")
                    else -> Unit
                }
            }
    }

    /** Shared start-up for either source: model lease, patient row, retention, explanation worker. */
    private fun launchMonitoring(stream: suspend CoroutineScope.() -> Unit) {
        _running.value = true

        streamJob?.cancel()
        pipeline.reset()

        // A new run: the live graphs begin again from nothing rather than carrying on from the
        // last one. Trails keeps the full history.
        WearablePreferences.restartCharts(this, System.currentTimeMillis())

        // Rule 2: claim the model for as long as monitoring runs.
        if (modelLease == null) modelLease = aiRepository.acquire("health-monitoring")

        streamJob = scope.launch {
            // Ensure a patient row exists before any reading references it — without
            // overwriting a profile that is already there.
            runCatching { ensurePatientProfile() }
                .onFailure { Log.w(TAG, "Could not ensure patient profile", it) }

            // Warm the model. Monitoring starts regardless of the outcome — launched
            // separately so a slow model load never delays the first sample.
            launch {
                aiRepository.initialize()
                    .onFailure { Log.w(TAG, "Model init failed; template explanations only", it) }
            }

            launch { periodicRetentionTrim() }

            // Model rewrites run beside sampling, never in its way.
            launch { pipeline.runExplanationWorker() }

            stream()
        }
    }

    /** @return true when the reading is in the database. */
    private suspend fun process(sample: VitalsSample, source: ReadingSource, replayed: Boolean): Boolean =
        try {
            pipeline.onSample(patientId, sample, source, replayed)
            if (pipeline.lastReadingStored && source == ReadingSource.BLE && !replayed) {
                com.gone.ai.health.emergency.EmergencySync.onLiveWearableReading(applicationContext)
            }
            pipeline.lastReadingStored
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline error on sample", e)
            false
        }

    private suspend fun ensurePatientProfile() {
        val profile = UserProfile.load(this)
        healthRepository.ensurePatient(
            PatientEntity(
                id = patientId,
                name = profile.name ?: "Local user",
                age = profile.age ?: 0,
                emergencyContactPhone = profile.emergencyContact,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    /** Keeps the vitals table bounded over long monitoring sessions. */
    private suspend fun periodicRetentionTrim() {
        while (true) {
            delay(RETENTION_TRIM_INTERVAL_MILLIS)
            runCatching {
                healthRepository.trimReadingsOlderThan(patientId, System.currentTimeMillis() - RETENTION_MILLIS)
            }
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        // A waiting SOS: the wearer stopping monitoring is an "I'm OK". Android ending it is not,
        // and losing the SOS would be worse than sending it early, so it goes now, outside the
        // scope that is about to be cancelled.
        if (sos.pending.value != null) {
            if (stoppedByWearer) sos.cancel()
            else CoroutineScope(Dispatchers.IO).launch { sos.sendPendingNow() }
        }
        stoppedByWearer = false
        activeSos = null
        _pendingSos.value = null
        HealthNotifications.cancelSosCountdown(this)
        activeSource = null
        runCatching { unregisterReceiver(bluetoothState) }
        streamJob?.cancel()
        scope.cancel()
        _running.value = false
        _snapshot.update { it.copy(state = MonitoringState.IDLE, aiExplaining = false) }
        _wearable.value = WearableLiveState()

        // Releases monitoring's claim on the model; other holders keep it loaded.
        modelLease?.close()
        modelLease = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Posts each alert as before, then decides whether it warrants an SOS ([SosPolicy]). Only live
     * alerts from the wearable arrive here: past readings replayed from its card are stored and
     * checked but never alert, so they can never text anyone.
     */
    private inner class SosAwareAlertSink(private val delegate: AlertSink) : AlertSink {
        override suspend fun onAnomaly(eventId: Long, event: AnomalyEventEntity, explanation: Explanation) {
            // Notification failure must not prevent emergency SMS.
            runCatching { delegate.onAnomaly(eventId, event, explanation) }
            if (!streamingFromWearable) return
            val type = AnomalyType.fromWireName(event.eventType) ?: return
            val fallImpactG = if (type == AnomalyType.FALL_DETECTED) {
                runCatching { JSONObject(event.evidenceJson).optDouble("fall_impact_g").toFloat() }.getOrNull()
            } else null
            val reason = SosPolicy.reasonFor(
                type = type,
                severity = Severity.fromWireName(event.severity),
                latest = pipeline.snapshot.value.latest,
                fallImpactG = fallImpactG
            ) ?: return
            sos.raise(reason)
        }

        override suspend fun onExplanationUpgraded(eventId: Long, text: String) =
            delegate.onExplanationUpgraded(eventId, text)
    }

    /** The link's own flows, gathered so they are published together. */
    private data class LinkFields(
        val link: SourceStatus,
        val sensors: WearableStatus?,
        val lastDataAt: Long?,
        val parser: ParserStats,
        val clockSyncFailed: Boolean
    )
}
