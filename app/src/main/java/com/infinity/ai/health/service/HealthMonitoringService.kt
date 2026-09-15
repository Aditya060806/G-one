package com.infinity.ai.health.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.infinity.ai.ai.repository.AIRepository
import com.infinity.ai.health.data.PatientEntity
import com.infinity.ai.health.data.ReadingSource
import com.infinity.ai.health.data.RoomHealthRepository
import com.infinity.ai.health.detect.AnomalyDetector
import com.infinity.ai.health.domain.AnomalyThresholds
import com.infinity.ai.health.source.SimulatedVitalsSource
import com.infinity.ai.health.source.VitalsScenario
import com.infinity.ai.health.source.VitalsSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The foreground service that runs continuous health monitoring.
 *
 * IT OWNS THE MODEL'S LIFECYCLE. THAT IS ITS MOST IMPORTANT RESPONSIBILITY.
 *
 * [AIRepository] is a process-wide singleton shared with every screen. Before this
 * service existed, `ChatViewModel.onCleared()` called unload on it, and because
 * `SettingsScreen` created its own route-scoped ChatViewModel, simply closing the
 * Settings screen freed the model. Wiring background monitoring on top of that would
 * mean a user navigating away could silently disable their own health monitoring.
 *
 * So ownership is explicit and lives here: this service calls `initialize()` on start
 * and is the ONLY component permitted to call `shutdown()`. Screen-level ViewModels
 * may stop their own generation and nothing more. See the ownership contract on
 * [AIRepository.shutdown].
 *
 * `foregroundServiceType=connectedDevice` matches the real purpose — streaming from a
 * wearable — and is the type that keeps working while the screen is off, which BLE
 * links need since Android aggressively power-manages them.
 */
class HealthMonitoringService : Service() {

    companion object {
        private const val TAG = "HealthMonitoringSvc"

        const val ACTION_START = "com.infinity.ai.health.START_MONITORING"
        const val ACTION_STOP = "com.infinity.ai.health.STOP_MONITORING"
        const val EXTRA_SCENARIO = "scenario"
        const val EXTRA_PATIENT_ID = "patient_id"

        const val DEFAULT_PATIENT_ID = "local-patient"
        /** 1 Hz would be 86,400 rows a day for no clinical gain; 5 s is ample. */
        const val DEFAULT_SAMPLE_INTERVAL_MILLIS = 5_000L
        /** Readings older than this are trimmed. */
        const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000

        private val _running = MutableStateFlow(false)
        /** Observable by the UI without binding to the service. */
        val isRunning: StateFlow<Boolean> = _running.asStateFlow()

        private val _snapshot = MutableStateFlow(MonitoringSnapshot())
        val snapshot: StateFlow<MonitoringSnapshot> = _snapshot.asStateFlow()

        fun updateSnapshot(snapshot: MonitoringSnapshot) {
            _snapshot.value = snapshot
        }

        fun start(
            context: Context,
            scenario: VitalsScenario = VitalsScenario.HEALTHY_BASELINE,
            patientId: String = DEFAULT_PATIENT_ID
        ) {
            val intent = Intent(context, HealthMonitoringService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SCENARIO, scenario.name)
                putExtra(EXTRA_PATIENT_ID, patientId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, HealthMonitoringService::class.java).apply { action = ACTION_STOP }
            )
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var streamJob: Job? = null

    private lateinit var aiRepository: AIRepository
    private lateinit var pipeline: MonitoringPipeline
    private var source: VitalsSource? = null
    private var patientId: String = DEFAULT_PATIENT_ID

    override fun onCreate() {
        super.onCreate()
        HealthNotifications.createChannels(this)

        val healthRepo = RoomHealthRepository.from(this)
        aiRepository = AIRepository.getInstance(this)

        pipeline = MonitoringPipeline(
            repository = healthRepo,
            detector = AnomalyDetector(AnomalyThresholds.DEFAULT),
            alerts = NotificationAlertSink(this),
            // The model is an optional enhancement. Monitoring is fully functional
            // without it, which is what keeps it off the critical path.
            explainer = LlamaAiExplainer(aiRepository)
        )

        scope.launch {
            pipeline.snapshot.collect { _snapshot.value = it }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                patientId = intent.getStringExtra(EXTRA_PATIENT_ID) ?: DEFAULT_PATIENT_ID
                val scenario = intent.getStringExtra(EXTRA_SCENARIO)
                    ?.let { runCatching { VitalsScenario.valueOf(it) }.getOrNull() }
                    ?: VitalsScenario.HEALTHY_BASELINE
                startMonitoring(scenario)
            }
            ACTION_STOP -> {
                Log.i(TAG, "Stop requested")
                stopSelf()
            }
            else -> Log.w(TAG, "Unknown action: ${intent?.action}")
        }
        // Do NOT auto-restart with a null intent: a restarted service with no scenario
        // would silently monitor with defaults the user never chose.
        return START_NOT_STICKY
    }

    private fun startMonitoring(scenario: VitalsScenario) {
        val prefs = getSharedPreferences("gone_preferences", Context.MODE_PRIVATE)
        val isMockMode = prefs.getBoolean("is_mock_mode", false)
        if (!isMockMode) {
            Log.w(TAG, "Monitoring start ignored because no real Bluetooth vitals source is configured")
            _running.value = false
            _snapshot.value = MonitoringSnapshot()
            return
        }

        val notification = HealthNotifications.buildMonitoringNotification(
            this,
            "Watching your vitals · ${scenario.displayName}"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                HealthNotifications.NOTIF_ID_MONITORING,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(
                HealthNotifications.NOTIF_ID_MONITORING,
                notification
            )
        }
        _running.value = true

        streamJob?.cancel()
        pipeline.reset()

        val vitalsSource = SimulatedVitalsSource(
            scenario = scenario,
            intervalMillis = DEFAULT_SAMPLE_INTERVAL_MILLIS,
            startAt = System.currentTimeMillis()
        )
        source = vitalsSource

        streamJob = scope.launch {
            // Ensure a patient row exists before any reading references it.
            runCatching {
                RoomHealthRepository.from(this@HealthMonitoringService).ensurePatient(
                    PatientEntity(
                        id = patientId,
                        name = "Local user",
                        age = 35,
                        createdAt = System.currentTimeMillis()
                    )
                )
            }

            // Warm the model. Monitoring starts regardless of the outcome — this is
            // launched separately so a slow model load never delays the first sample.
            launch {
                runCatching { aiRepository.initialize() }
                    .onFailure { Log.w(TAG, "Model init failed; template explanations only", it) }
            }

            launch { periodicRetentionTrim() }

            vitalsSource.stream().collect { sample ->
                runCatching {
                    pipeline.onSample(patientId, sample, ReadingSource.SIMULATED)
                }.onFailure { Log.e(TAG, "Pipeline error on sample", it) }
            }
        }
    }

    /** Keeps the vitals table bounded over long monitoring sessions. */
    private suspend fun periodicRetentionTrim() {
        val repo = RoomHealthRepository.from(this)
        while (true) {
            kotlinx.coroutines.delay(6L * 60 * 60 * 1000)
            runCatching {
                repo.trimReadingsOlderThan(patientId, System.currentTimeMillis() - RETENTION_MILLIS)
            }
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed — releasing model (sole owner)")
        streamJob?.cancel()
        _running.value = false

        // The ONLY sanctioned call site for shutdown() in the whole app.
        runCatching { aiRepository.shutdown() }

        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
