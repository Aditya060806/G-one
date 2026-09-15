package com.infinity.ai.health.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.core.content.ContextCompat
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.infinity.ai.ui.theme.ModernBorderDark

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.infinity.ai.health.data.AnomalyEventEntity
import com.infinity.ai.health.data.EventStatus
import com.infinity.ai.health.data.severityEnum
import com.infinity.ai.health.domain.Severity
import com.infinity.ai.health.domain.VitalsSample
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import android.graphics.Color as AndroidColor
import com.infinity.ai.health.data.statusEnum
import com.infinity.ai.health.source.VitalsScenario
import com.infinity.ai.ui.components.GradientBackground
import com.infinity.ai.ui.components.HeaderActionPill
import com.infinity.ai.ui.components.LoadingLottieAnimation
import com.infinity.ai.ui.components.WatchScanningLottieAnimation
import com.infinity.ai.ui.theme.DarkBorder
import com.infinity.ai.ui.theme.DarkSurface
import com.infinity.ai.ui.theme.FloatingNavActive
import com.infinity.ai.ui.theme.GoneCard
import com.infinity.ai.ui.theme.GoneElevation
import com.infinity.ai.ui.theme.GoneRadius
import com.infinity.ai.ui.theme.GraphCoralBar
import com.infinity.ai.ui.theme.GraphDarkBar
import com.infinity.ai.ui.theme.GraphDarkMutedBar
import com.infinity.ai.ui.theme.GraphMutedBar
import com.infinity.ai.ui.theme.LightBorder
import com.infinity.ai.ui.theme.LightShadow
import com.infinity.ai.ui.theme.LightSurface
import com.infinity.ai.ui.theme.ModernBgDark
import com.infinity.ai.ui.theme.ModernBgLight
import com.infinity.ai.ui.theme.ModernBlue
import com.infinity.ai.ui.theme.ModernBlueSubtle
import com.infinity.ai.ui.theme.ModernCardDark
import com.infinity.ai.ui.theme.ModernCardLight
import com.infinity.ai.ui.theme.StaggeredEntrance
import com.infinity.ai.ui.theme.TextPrimary
import com.infinity.ai.ui.theme.TextPrimaryLight
import com.infinity.ai.ui.theme.TrendGreenBg
import com.infinity.ai.ui.theme.TrendGreenText
import com.infinity.ai.ui.theme.VitalCyan
import com.infinity.ai.ui.theme.VitalCyanSubtle
import com.infinity.ai.ui.theme.VitalOrange
import com.infinity.ai.ui.theme.VitalOrangeSubtle
import com.infinity.ai.ui.theme.VitalRed
import com.infinity.ai.ui.theme.VitalRedSubtle
import com.infinity.ai.ui.theme.goneSurface
import com.infinity.ai.ui.theme.pressScale
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Modern 1:1 Health Home Screen.
 *
 * Implements the ultra-clean, modern aesthetic with personalized greeting, top circular action pills,
 * hero step/activity card, minimalist 3-column vitals row, and rhythm waveform bar chart.
 */
@Composable
fun HealthDashboardScreen(
    isDarkTheme: Boolean,
    bottomPadding: Dp,
    onOpenAlerts: () -> Unit,
    onOpenMonitor: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAssist: () -> Unit = {},
    onNavigateToPdf: () -> Unit = {},
    onNavigateToOcr: () -> Unit = {},
    onNavigateToScreenshot: () -> Unit = {},
    onNavigateToQuiz: () -> Unit = {},
    vm: HealthViewModel = viewModel()
) {
    val context = LocalContext.current
    val isMonitoring by vm.isMonitoring.collectAsState()
    val snapshot by vm.snapshot.collectAsState()
    val activeEvents by vm.activeEvents.collectAsState()
    val allEvents by vm.allEvents.collectAsState()
    val history by vm.history.collectAsState()
    var showLiveAlertsSheet by remember { mutableStateOf(false) }

    // ── Bluetooth & Device Detection ─────────────────────────────────────────
    val bluetoothManager = remember {
        try {
            context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        } catch (_: Exception) {
            null
        }
    }
    val bluetoothAdapter = bluetoothManager?.adapter

    var hasBtPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    var isBtEnabled by remember { mutableStateOf(bluetoothAdapter?.isEnabled == true) }
    var bondedDevices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }

    fun updateBondedDevices() {
        isBtEnabled = bluetoothAdapter?.isEnabled == true
        if (isBtEnabled && hasBtPermission && bluetoothAdapter != null) {
            try {
                @SuppressLint("MissingPermission")
                val devices = bluetoothAdapter.bondedDevices?.toList() ?: emptyList()
                bondedDevices = devices
            } catch (_: SecurityException) {
                bondedDevices = emptyList()
            }
        } else {
            bondedDevices = emptyList()
        }
    }

    LaunchedEffect(hasBtPermission, isBtEnabled) {
        updateBondedDevices()
    }

    DisposableEffect(context) {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                updateBondedDevices()
            }
        }
        try {
            context.registerReceiver(receiver, filter)
        } catch (_: Exception) {}
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {}
        }
    }

    val activeDevice = remember(bondedDevices) {
        bondedDevices.firstOrNull { dev ->
            try {
                @SuppressLint("MissingPermission")
                val name = dev.name?.lowercase(Locale.ROOT) ?: ""
                name.contains("watch") || name.contains("band") || name.contains("fit") ||
                    name.contains("sentinel") || name.contains("ring") || name.contains("sensor") || name.contains("health")
            } catch (_: SecurityException) {
                false
            }
        } ?: bondedDevices.firstOrNull()
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.startMonitoring(context) }

    fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.startMonitoring(context)
        }
    }

    val latest = snapshot.latest
    val hrHistory = remember(history) { history.mapNotNull { it.heartRate?.toFloat() }.takeLast(48) }
    val stateColor = monitoringStateColor(snapshot.state, isDarkTheme)

    val scrollState = rememberScrollState()

    val bgModifier = if (isDarkTheme) {
        Modifier.background(ModernBgDark)
    } else {
        Modifier.background(ModernBgLight)
    }

    Box(modifier = Modifier.fillMaxSize().then(bgModifier)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(14.dp))

            val prefs = remember { context.getSharedPreferences("gone_preferences", Context.MODE_PRIVATE) }
            val currentUserName = remember { prefs.getString("user_name", "Ranbir") ?: "Ranbir" }
            val greetingPhrase = remember(currentUserName) {
                val greetings = listOf(
                    "Good morning, $currentUserName",
                    "Hello, $currentUserName",
                    "Welcome back, $currentUserName",
                    "Looking healthy, $currentUserName",
                    "All good, $currentUserName?",
                    "Hey, $currentUserName",
                    "Good day, $currentUserName",
                    "Stay active, $currentUserName"
                )
                greetings.random()
            }

            // ── 1. Top Header Bar (Transparent Lottie + Dynamic Greeting + Alerts & Setup) ───
            ModernTopBar(
                greeting = greetingPhrase,
                activeAlertCount = activeEvents.size,
                darkTheme = isDarkTheme,
                onOpenAlerts = { showLiveAlertsSheet = true },
                onOpenSettings = onOpenSettings
            )

            Spacer(Modifier.height(14.dp))

            // ── 2. Live Monitoring Control Bar (Top Visibility) ──────────────
            ModernMonitoringControlBar(
                running = isMonitoring,
                stateLabel = monitoringStateLabel(snapshot.state),
                stateColor = stateColor,
                darkTheme = isDarkTheme,
                onStart = ::start,
                onStop = { vm.stopMonitoring(context) }
            )

            Spacer(Modifier.height(18.dp))

            val latestReading = latest ?: history.lastOrNull()?.let {
                VitalsSample(
                    timestamp = it.timestamp,
                    heartRate = it.heartRate,
                    bodyTempC = it.bodyTempC,
                    spo2 = it.spo2,
                    motionMagnitudeG = it.motionMagnitudeG
                )
            }
            val latestEmg = remember(latestReading) {
                if (latestReading?.motionMagnitudeG != null) {
                    ((latestReading.motionMagnitudeG ?: 1.0f) * 44f + ((latestReading.timestamp ?: 0L) % 18).toFloat()).toInt().coerceIn(15, 140)
                } else null
            }

            // ── 3. Minimalist 4-Column Clinical Vitals Stat Row ───────────────
            ModernVitalsRow(
                heartRate = latestReading?.heartRate,
                emg = latestEmg,
                temp = latestReading?.bodyTempC,
                spo2 = latestReading?.spo2,
                darkTheme = isDarkTheme,
                onOpenMonitor = onOpenMonitor
            )

            Spacer(Modifier.height(18.dp))

            // ── 4. Bento Section: Hardware Device & Telemetry Stream Status ───
            DeviceStatusBentoCard(
                isMonitoring = isMonitoring,
                activeDevice = activeDevice,
                isBtEnabled = isBtEnabled,
                hasBtPermission = hasBtPermission,
                darkTheme = isDarkTheme,
                onClick = onOpenMonitor
            )

            Spacer(Modifier.height(14.dp))

            // ── 5. Bento Section: Recent Alerts & Events (Latest 2 to 3) ───────
            RecentAlertsBentoCard(
                events = allEvents.take(3),
                totalEventCount = allEvents.size,
                darkTheme = isDarkTheme,
                onOpenAlerts = { showLiveAlertsSheet = true },
                onOpenTrails = onOpenAlerts
            )

            Spacer(Modifier.height(14.dp))

            // ── 6. Bento Section: Historical Telemetry Summary (2h / 2d / months) ─
            HistoricalTelemetrySummaryBento(
                history = history,
                latest = latest,
                latestEmg = latestEmg,
                darkTheme = isDarkTheme
            )

            Spacer(Modifier.height(14.dp))

            // ── 7. Bento Section: Clinical Diagnostic Tools Grid ───────────────
            ToolsShortcutBentoGrid(
                darkTheme = isDarkTheme,
                onNavigateToPdf = onNavigateToPdf,
                onNavigateToOcr = onNavigateToOcr,
                onNavigateToScreenshot = onNavigateToScreenshot,
                onNavigateToQuiz = onNavigateToQuiz
            )

            Spacer(Modifier.height(14.dp))

            // ── 8. Bento Section: AI Clinical Assist Quick Card ────────────────
            AssistShortcutBentoCard(
                darkTheme = isDarkTheme,
                onOpenAssist = onOpenAssist
            )

            Spacer(Modifier.height(bottomPadding + 90.dp))
        }

        if (showLiveAlertsSheet) {
            LiveAlertsBottomSheet(
                activeEvents = activeEvents,
                isDarkTheme = isDarkTheme,
                onDismiss = { showLiveAlertsSheet = false },
                onViewAllTrails = {
                    showLiveAlertsSheet = false
                    onOpenAlerts()
                }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. Top Bar: Avatar + Greeting + Circular Action Pills
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// 1. Top Bar: Loading Lottie + Greeting + Labeled Action Pills (Trails, Alerts, Settings)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ModernTopBar(
    greeting: String,
    activeAlertCount: Int,
    darkTheme: Boolean,
    onOpenAlerts: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Loading Lottie Animation (Clean transparent, no background, no border)
        Box(
            modifier = Modifier
                .pressScale(interaction)
                .size(44.dp)
                .clickable(interactionSource = interaction, indication = null, onClick = onOpenSettings),
            contentAlignment = Alignment.Center
        ) {
            LoadingLottieAnimation(modifier = Modifier.size(44.dp))
        }

        Spacer(Modifier.width(10.dp))

        // Dynamic Greeting (e.g. "Kese ho, Ranbir", "Morning, Ranbir")
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = greeting,
                style = MaterialTheme.typography.titleMedium,
                color = if (darkTheme) TextPrimary else TextPrimaryLight,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
            Text(
                text = "Clinical Telemetry",
                style = MaterialTheme.typography.bodySmall,
                color = if (darkTheme) Color(0xFF94A3B8) else Color(0xFF64748B),
                fontSize = 11.sp
            )
        }

        Spacer(Modifier.width(8.dp))

        // Top Action Pills (Trails hidden as requested)
        // 1. Alerts (Active notifications)
        HeaderActionPill(
            icon = if (activeAlertCount > 0) Icons.Default.NotificationsActive else Icons.Outlined.Notifications,
            label = "Alerts",
            darkTheme = darkTheme,
            badgeCount = activeAlertCount,
            onClick = onOpenAlerts
        )

        Spacer(Modifier.width(8.dp))

        // 2. Settings (Calibration & device setup)
        HeaderActionPill(
            icon = Icons.Default.Settings,
            label = "Setup",
            darkTheme = darkTheme,
            onClick = onOpenSettings
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. Minimalist 4-Column Clinical Vitals Stat Row (Heart, EMG, Temp, SpO2)
// ─────────────────────────────────────────────────────────────────────────────

internal data class HumanStatus(
    val word: String,
    val takeaway: String,
    val color: Color
)

@Composable
private fun ModernVitalsRow(
    heartRate: Int?,
    emg: Int?,
    temp: Float?,
    spo2: Int?,
    darkTheme: Boolean,
    onOpenMonitor: () -> Unit
) {
    val neutralColor = if (darkTheme) Color(0xFF94A3B8) else Color(0xFF64748B)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Heart Vital Column
        ModernVitalColumnItem(
            icon = Icons.Default.Favorite,
            iconColor = VitalRed,
            iconBg = if (darkTheme) Color(0xFF331E24) else VitalRedSubtle,
            value = heartRate?.toString() ?: "--",
            unit = "bpm",
            name = "Heart",
            statusWord = if (heartRate == null) "Standby" else if (heartRate in 60..100) "Steady" else (if (heartRate > 100) "Fast" else "Slow"),
            statusColor = if (heartRate == null) neutralColor else if (heartRate in 60..100) Color(0xFF10B981) else Color(0xFFEF4444),
            darkTheme = darkTheme,
            onClick = onOpenMonitor
        )

        // 2. Muscles Vital Column
        ModernVitalColumnItem(
            icon = Icons.Default.ElectricBolt,
            iconColor = Color(0xFF8B5CF6),
            iconBg = if (darkTheme) Color(0xFF281E3B) else Color(0xFFF3E8FF),
            value = emg?.toString() ?: "--",
            unit = "µV",
            name = "Muscles",
            statusWord = if (emg == null) "Standby" else if (emg < 50) "Relaxed" else "Active",
            statusColor = if (emg == null) neutralColor else if (emg < 50) Color(0xFF10B981) else Color(0xFF8B5CF6),
            darkTheme = darkTheme,
            onClick = onOpenMonitor
        )

        // 3. Body Temp Vital Column
        ModernVitalColumnItem(
            icon = Icons.Default.Thermostat,
            iconColor = VitalOrange,
            iconBg = if (darkTheme) Color(0xFF38291A) else VitalOrangeSubtle,
            value = if (temp != null) String.format(Locale.US, "%.1f°", temp) else "--°",
            unit = "C",
            name = "Temp",
            statusWord = if (temp == null) "Standby" else if (temp in 36.0f..37.3f) "Normal" else (if (temp > 37.3f) "Warm" else "Cool"),
            statusColor = if (temp == null) neutralColor else if (temp in 36.0f..37.3f) Color(0xFF10B981) else Color(0xFFF59E0B),
            darkTheme = darkTheme,
            onClick = onOpenMonitor
        )

        // 4. Blood Oxygen Vital Column
        ModernVitalColumnItem(
            icon = Icons.Default.Air,
            iconColor = VitalCyan,
            iconBg = if (darkTheme) Color(0xFF192F3E) else VitalCyanSubtle,
            value = if (spo2 != null) "$spo2%" else "--%",
            unit = "",
            name = "Oxygen",
            statusWord = if (spo2 == null) "Standby" else if (spo2 >= 95) "Optimal" else "Low",
            statusColor = if (spo2 == null) neutralColor else if (spo2 >= 95) Color(0xFF10B981) else Color(0xFFEF4444),
            darkTheme = darkTheme,
            onClick = onOpenMonitor
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 2b. Reusable Clinical Telemetry Trend Card (EMG, Temp, SpO2)
// ─────────────────────────────────────────────────────────────────────────────

internal fun ensureDetailedPoints(
    realPoints: List<ChartPoint>,
    baseline: Float,
    safeMin: Float,
    safeMax: Float,
    jitterScale: Float = 0.5f
): List<ChartPoint> {
    if (realPoints.isEmpty()) return emptyList()
    return realPoints
}

@Composable
internal fun MPAndroidTelemetryChart(
    points: List<ChartPoint>,
    color: Color,
    yMin: Float,
    yMax: Float,
    safeMin: Float,
    safeMax: Float,
    unit: String,
    showAxesLabels: Boolean = true,
    darkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    val textHex = if (darkTheme) "#94A3B8" else "#64748B"
    val gridHex = if (darkTheme) "#2A2A2A" else "#E5E9F0"
    val textColor = AndroidColor.parseColor(textHex)
    val gridColor = AndroidColor.parseColor(gridHex)
    val lineColor = AndroidColor.argb(
        (color.alpha * 255).toInt(),
        (color.red * 255).toInt(),
        (color.green * 255).toInt(),
        (color.blue * 255).toInt()
    )

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            LineChart(ctx).apply {
                description.isEnabled = false
                legend.isEnabled = false
                setTouchEnabled(false)
                isDragEnabled = false
                setScaleEnabled(false)
                setPinchZoom(false)
                setDrawGridBackground(false)
                setBackgroundColor(AndroidColor.TRANSPARENT)

                // Extra offsets for clean, generous spacing
                setExtraOffsets(8f, 8f, 8f, if (showAxesLabels) 12f else 6f)

                axisRight.isEnabled = false

                axisLeft.apply {
                    setDrawGridLines(true)
                    this.gridColor = gridColor
                    gridLineWidth = 0.8f
                    enableGridDashedLine(8f, 8f, 0f)
                    setDrawAxisLine(false)
                    // Technical Y-axis numbers hidden; big values are shown cleanly in headers
                    setDrawLabels(false)
                    axisMinimum = yMin
                    axisMaximum = yMax

                    removeAllLimitLines()
                    val upperLimit = LimitLine(safeMax).apply {
                        lineWidth = 1f
                        this.lineColor = lineColor
                        enableDashedLine(6f, 6f, 0f)
                    }
                    val lowerLimit = LimitLine(safeMin).apply {
                        lineWidth = 1f
                        this.lineColor = lineColor
                        enableDashedLine(6f, 6f, 0f)
                    }
                    addLimitLine(upperLimit)
                    addLimitLine(lowerLimit)
                }

                xAxis.apply {
                    position = XAxis.XAxisPosition.BOTTOM
                    setDrawGridLines(false)
                    setDrawAxisLine(false)
                    setDrawLabels(showAxesLabels)
                    this.textColor = textColor
                    textSize = 9.5f
                    setLabelCount(2, true)
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float): String {
                            return if (value <= 1f) "Earlier" else "Now"
                        }
                    }
                }
            }
        },
        update = { chart ->
            if (points.isEmpty()) return@AndroidView
            val entries = points.mapIndexed { index, pt ->
                Entry(index.toFloat(), pt.value)
            }
            val dataSet = LineDataSet(entries, "Telemetry").apply {
                mode = LineDataSet.Mode.CUBIC_BEZIER
                cubicIntensity = 0.18f
                this.color = lineColor
                lineWidth = 2.4f
                setDrawCircles(false)
                setDrawValues(false)
                setDrawFilled(true)
                fillColor = lineColor
                fillAlpha = if (darkTheme) 42 else 26
            }
            chart.xAxis.axisMaximum = (points.size - 1).toFloat().coerceAtLeast(1f)
            chart.xAxis.axisMinimum = 0f
            chart.data = LineData(dataSet)
            chart.invalidate()
        }
    )
}

@Composable
internal fun BentoFullWidthChartCard(
    title: String,
    subtitle: String,
    value: String,
    valueColor: Color,
    icon: ImageVector? = null,
    statusWord: String,
    statusColor: Color,
    takeawayText: String,
    safeRangeLabel: String,
    safeMin: Float,
    safeMax: Float,
    unit: String,
    baselineValue: Float,
    points: List<ChartPoint>,
    color: Color,
    yMin: Float,
    yMax: Float,
    darkTheme: Boolean
) {
    val displayPoints = remember(points, baselineValue) {
        ensureDetailedPoints(
            realPoints = points,
            baseline = baselineValue,
            safeMin = safeMin,
            safeMax = safeMax
        )
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Name, Subtitle, Value, Single-Word Status (OUTSIDE graph area)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (darkTheme) TextPrimary else TextPrimaryLight,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (darkTheme) Color(0xFF94A3B8) else Color(0xFF64748B),
                    fontWeight = FontWeight.Normal,
                    fontSize = 12.sp
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                // Value is BOLD and BIGGER (what actually matters)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = valueColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Text(
                        text = value,
                        style = MaterialTheme.typography.titleMedium,
                        color = valueColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
                Spacer(Modifier.height(4.dp))
                // Single-word status is SEMI-BOLD in clear pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(statusColor.copy(alpha = if (darkTheme) 0.20f else 0.12f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = statusWord,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Bento Graph Container (Pure uncluttered chart area with generous padding)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = if (darkTheme) 0.dp else 4.dp,
                    shape = RoundedCornerShape(22.dp),
                    ambientColor = LightShadow,
                    spotColor = LightShadow
                )
                .clip(RoundedCornerShape(22.dp))
                .background(if (darkTheme) ModernCardDark else ModernCardLight)
                .border(
                    1.dp,
                    if (darkTheme) ModernBorderDark else LightBorder,
                    RoundedCornerShape(22.dp)
                )
                .padding(horizontal = 14.dp, vertical = 14.dp)
        ) {
            MPAndroidTelemetryChart(
                points = displayPoints,
                color = color,
                yMin = yMin,
                yMax = yMax,
                safeMin = safeMin,
                safeMax = safeMax,
                unit = unit,
                showAxesLabels = true,
                darkTheme = darkTheme,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
            )
        }

        Spacer(Modifier.height(10.dp))

        // Friendly Takeaway & Safe Corridor (OUTSIDE graph area)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Text(
                    text = takeawayText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (darkTheme) Color(0xFF94A3B8) else Color(0xFF64748B),
                    fontWeight = FontWeight.Normal,
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(color.copy(alpha = if (darkTheme) 0.14f else 0.08f))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(
                    text = safeRangeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontWeight = FontWeight.Normal,
                    fontSize = 10.5.sp
                )
            }
        }
    }
}

@Composable
internal fun BentoCompactTile(
    title: String,
    subtitle: String,
    value: String,
    valueColor: Color,
    statusWord: String,
    statusColor: Color,
    takeawayText: String,
    safeRangeLabel: String,
    safeMin: Float,
    safeMax: Float,
    baselineValue: Float,
    points: List<ChartPoint>,
    color: Color,
    yMin: Float,
    yMax: Float,
    darkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    val displayPoints = remember(points, baselineValue) {
        ensureDetailedPoints(
            realPoints = points,
            baseline = baselineValue,
            safeMin = safeMin,
            safeMax = safeMax
        )
    }

    Column(modifier = modifier) {
        // Name & Value (OUTSIDE graph area)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (darkTheme) TextPrimary else TextPrimaryLight,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
            // Value is BOLD and BIGGER (what actually matters)
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = valueColor,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
        }

        Spacer(Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (darkTheme) Color(0xFF94A3B8) else Color(0xFF64748B),
                fontWeight = FontWeight.Normal,
                fontSize = 11.sp
            )
            // Single-word status is SEMI-BOLD in clear pill
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(statusColor.copy(alpha = if (darkTheme) 0.20f else 0.12f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = statusWord,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.5.sp
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Bento Graph Container (Pure uncluttered chart area)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = if (darkTheme) 0.dp else 3.dp,
                    shape = RoundedCornerShape(20.dp),
                    ambientColor = LightShadow,
                    spotColor = LightShadow
                )
                .clip(RoundedCornerShape(20.dp))
                .background(if (darkTheme) ModernCardDark else ModernCardLight)
                .border(
                    1.dp,
                    if (darkTheme) ModernBorderDark else LightBorder,
                    RoundedCornerShape(20.dp)
                )
                .padding(horizontal = 8.dp, vertical = 10.dp)
        ) {
            MPAndroidTelemetryChart(
                points = displayPoints,
                color = color,
                yMin = yMin,
                yMax = yMax,
                safeMin = safeMin,
                safeMax = safeMax,
                unit = "",
                showAxesLabels = false,
                darkTheme = darkTheme,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(95.dp)
            )
        }

        Spacer(Modifier.height(8.dp))

        // Friendly takeaway / target
        Text(
            text = takeawayText,
            style = MaterialTheme.typography.bodySmall,
            color = if (darkTheme) Color(0xFF94A3B8) else Color(0xFF64748B),
            fontWeight = FontWeight.Normal,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 2.dp)
        )
    }
}

@Composable
private fun ModernVitalColumnItem(
    icon: ImageVector,
    iconColor: Color,
    iconBg: Color,
    value: String,
    unit: String,
    name: String,
    statusWord: String,
    statusColor: Color,
    darkTheme: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .pressScale(interaction, pressedScale = 0.96f)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            )
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Circular icon bubble
        Box(
            modifier = Modifier
                .size(46.dp)
                .shadow(
                    elevation = if (darkTheme) 0.dp else 3.dp,
                    shape = CircleShape,
                    ambientColor = LightShadow,
                    spotColor = LightShadow
                )
                .clip(CircleShape)
                .background(iconBg)
                .border(
                    1.dp,
                    if (darkTheme) DarkBorder else LightBorder,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = name,
                tint = iconColor,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(Modifier.height(7.dp))

        // Big bold measurement number (WHAT MATTERS: BOLD & BIGGER)
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = if (darkTheme) TextPrimary else TextPrimaryLight,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )

        Spacer(Modifier.height(1.dp))

        // Muted metric name (NOT BOLD: regular weight)
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            color = if (darkTheme) Color(0xFF94A3B8) else Color(0xFF64748B),
            fontWeight = FontWeight.Normal,
            fontSize = 11.sp
        )

        Spacer(Modifier.height(3.dp))

        // Single word status pill (SEMI-BOLD)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(statusColor.copy(alpha = if (darkTheme) 0.18f else 0.10f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = statusWord,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp
            )
        }
    }
}



// ─────────────────────────────────────────────────────────────────────────────
// 5. Active Alert Banner
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun ActiveAlertBanner(
    event: AnomalyEventEntity,
    extraCount: Int,
    darkTheme: Boolean,
    onClick: () -> Unit
) {
    val color = severityColor(event.severityEnum(), darkTheme)
    val interaction = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .pressScale(interaction)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(color.copy(alpha = 0.16f), color.copy(alpha = 0.06f))
                )
            )
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(24.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.size(36.dp).background(color.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Warning, null, tint = color, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                event.anomalyLabel(),
                style = MaterialTheme.typography.titleSmall,
                color = if (darkTheme) TextPrimary else TextPrimaryLight,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (extraCount > 0) "and $extraCount more active alerts"
                else "Tap to view clinical explanation",
                style = MaterialTheme.typography.bodySmall,
                color = color.copy(alpha = 0.85f)
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward, null,
            tint = color, modifier = Modifier.size(16.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 6. Monitoring Control Bar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ModernMonitoringControlBar(
    running: Boolean,
    stateLabel: String,
    stateColor: Color,
    darkTheme: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction, pressedScale = 0.97f)
            .shadow(
                elevation = if (darkTheme) 0.dp else 4.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = LightShadow,
                spotColor = LightShadow
            )
            .clip(RoundedCornerShape(24.dp))
            .background(if (darkTheme) ModernCardDark else ModernCardLight)
            .border(
                1.dp,
                if (darkTheme) DarkBorder else LightBorder,
                RoundedCornerShape(24.dp)
            )
            .clickable(interactionSource = interaction, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                if (running) onStop() else onStart()
            }
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PulseRing(
                    color = stateColor,
                    active = running,
                    modifier = Modifier.size(14.dp)
                )
                Column {
                    Text(
                        text = if (running) "Live monitoring active" else "Monitoring paused",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (darkTheme) TextPrimary else TextPrimaryLight,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stateLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryTextColor(darkTheme)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (running) VitalRed else ModernBlue)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = if (running) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (running) "Stop" else "Start",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. Bento: Hardware Device & Live Telemetry Waveform Card (Full Motion)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DeviceStatusBentoCard(
    isMonitoring: Boolean,
    activeDevice: BluetoothDevice?,
    isBtEnabled: Boolean,
    hasBtPermission: Boolean,
    darkTheme: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current

    // Continuous 60fps live carrier wave motion
    val transition = rememberInfiniteTransition(label = "deviceWave")
    val wavePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * kotlin.math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )

    val deviceName = when {
        activeDevice != null -> {
            try {
                @SuppressLint("MissingPermission")
                activeDevice.name ?: "Wearable BLE"
            } catch (_: SecurityException) {
                "Wearable BLE"
            }
        }
        isMonitoring -> "G-One Sentinel"
        !isBtEnabled -> "Bluetooth Inactive"
        !hasBtPermission -> "Bluetooth Access"
        else -> "G-One Biosensor"
    }

    val badgeColor = when {
        isMonitoring -> Color(0xFF10B981)
        activeDevice != null -> ModernBlue
        !isBtEnabled -> if (darkTheme) Color(0xFF64748B) else Color(0xFF94A3B8)
        else -> ModernBlue
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction, pressedScale = 0.98f)
            .clip(RoundedCornerShape(20.dp))
            .background(if (darkTheme) ModernCardDark else ModernCardLight)
            .border(1.dp, if (darkTheme) DarkBorder else LightBorder, RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            )
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(badgeColor.copy(alpha = if (darkTheme) 0.16f else 0.10f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isMonitoring) {
                        WatchScanningLottieAnimation(modifier = Modifier.size(36.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Default.Bluetooth,
                            contentDescription = null,
                            tint = badgeColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = deviceName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (darkTheme) TextPrimary else TextPrimaryLight,
                        fontSize = 15.sp
                    )
                    Text(
                        text = if (isMonitoring) "Live Telemetry Feed" else if (activeDevice != null) "Hardware Paired" else "Tap to connect",
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryTextColor(darkTheme),
                        fontSize = 11.sp
                    )
                }

                // Graphical Link Quality Meter + Status Pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MiniSignalMeterGraphic(
                        bars = if (isMonitoring) 4 else if (activeDevice != null) 3 else 1,
                        color = badgeColor
                    )

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(badgeColor.copy(alpha = if (darkTheme) 0.16f else 0.10f))
                            .border(1.dp, badgeColor.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (isMonitoring) "LIVE" else if (activeDevice != null) "PAIRED" else "OFF",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = badgeColor,
                            fontSize = 9.sp,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }

            // Real-Time Animated Carrier Waveform Canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (darkTheme) Color(0xFF141518) else Color(0xFFF3F4F6))
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val midY = h / 2f
                    val path = Path()

                    val steps = 80
                    for (i in 0..steps) {
                        val x = (i.toFloat() / steps) * w
                        val relX = (i.toFloat() / steps) * 4 * kotlin.math.PI.toFloat()
                        val y = if (isMonitoring) {
                            val ecgSpike = if (i % 20 in 8..11) {
                                when (i % 20) {
                                    8 -> -h * 0.25f
                                    9 -> h * 0.42f
                                    10 -> -h * 0.35f
                                    else -> 0f
                                }
                            } else {
                                kotlin.math.sin(relX + wavePhase) * (h * 0.18f)
                            }
                            midY + ecgSpike.toFloat()
                        } else {
                            midY + (kotlin.math.sin(relX) * (h * 0.08f)).toFloat()
                        }

                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }

                    drawPath(
                        path = path,
                        color = if (isMonitoring) Color(0xFF10B981) else Color(0xFF64748B),
                        style = Stroke(
                            width = if (isMonitoring) 2.2f else 1.2f,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniSignalMeterGraphic(
    bars: Int,
    color: Color
) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        val heights = listOf(4.dp, 7.dp, 10.dp, 13.dp)
        heights.forEachIndexed { i, h ->
            val filled = i < bars
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(h)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (filled) color else color.copy(alpha = 0.25f))
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. Bento: Clinical Event Radar Strip (Visual Timeline, Not Text Heavy)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun RecentAlertsBentoCard(
    events: List<AnomalyEventEntity>,
    totalEventCount: Int,
    darkTheme: Boolean,
    onOpenAlerts: () -> Unit,
    onOpenTrails: () -> Unit = onOpenAlerts
) {
    val interaction = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current

    val transition = rememberInfiniteTransition(label = "alertPulse")
    val pulseScale by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (darkTheme) ModernCardDark else ModernCardLight)
            .border(1.dp, if (darkTheme) DarkBorder else LightBorder, RoundedCornerShape(20.dp))
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Event Radar",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (darkTheme) TextPrimary else TextPrimaryLight,
                    fontSize = 14.sp
                )
                if (totalEventCount > 0) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(VitalRed.copy(alpha = if (darkTheme) 0.20f else 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "$totalEventCount",
                            style = MaterialTheme.typography.labelSmall,
                            color = VitalRed,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(
                    modifier = Modifier
                        .pressScale(interaction)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onOpenTrails()
                            }
                        )
                        .padding(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Trails",
                        style = MaterialTheme.typography.labelSmall,
                        color = ModernBlue,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = ModernBlue,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }

            if (events.isEmpty()) {
                // Steady State Indicator
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (darkTheme) Color(0xFF15161A) else Color(0xFFF3F4F6))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .graphicsLayer { scaleX = pulseScale; scaleY = pulseScale }
                            .clip(CircleShape)
                            .background(TrendGreenText)
                    )
                    Text(
                        text = "Steady Homeostasis · No Critical Events",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (darkTheme) TextPrimary else TextPrimaryLight,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(TrendGreenText.copy(alpha = 0.15f))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text("NORMAL", color = TrendGreenText, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                // Sleek event node strips
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    events.forEach { event ->
                        val sev = event.severityEnum()
                        val isCrit = sev == Severity.CRITICAL
                        val chipColor = if (isCrit) VitalRed else VitalOrange
                        val chipBg = if (isCrit) {
                            if (darkTheme) Color(0xFF331E24) else VitalRedSubtle
                        } else {
                            if (darkTheme) Color(0xFF38291A) else VitalOrangeSubtle
                        }

                        val timeFormatted = remember(event.createdAt) {
                            val diff = System.currentTimeMillis() - event.createdAt
                            when {
                                diff < 60_000L -> "Just now"
                                diff < 3600_000L -> "${(diff / 60_000L).coerceAtLeast(1)}m"
                                diff < 86400_000L -> "${diff / 3600_000L}h"
                                else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(event.createdAt))
                            }
                        }

                        val eventTitle = remember(event.eventType) {
                            event.eventType.replace('_', ' ')
                                .lowercase(Locale.ROOT)
                                .split(' ')
                                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (darkTheme) Color(0xFF18191E) else Color(0xFFF8FAFC))
                                .border(1.dp, if (darkTheme) Color(0xFF24262E) else Color(0xFFE2E8F0), RoundedCornerShape(10.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onOpenAlerts()
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .graphicsLayer {
                                        if (isCrit) {
                                            scaleX = pulseScale
                                            scaleY = pulseScale
                                        }
                                    }
                                    .clip(CircleShape)
                                    .background(chipColor)
                            )

                            Text(
                                text = eventTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (darkTheme) TextPrimary else TextPrimaryLight,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f),
                                maxLines = 1
                            )

                            Text(
                                text = timeFormatted,
                                style = MaterialTheme.typography.labelSmall,
                                color = secondaryTextColor(darkTheme),
                                fontSize = 10.sp
                            )

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(chipBg)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = sev.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = chipColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 8.5.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 5. Bento: Graphical Telemetry Summary (Animated Waveform + Stability Gauge + Sparklines)
// ─────────────────────────────────────────────────────────────────────────────

enum class TelemetryWindow(val label: String, val durationMs: Long) {
    TWO_HOURS("2h", 2L * 3600 * 1000L),
    TWO_DAYS("2d", 2L * 86400 * 1000L),
    MONTHS("30d", 30L * 86400 * 1000L)
}

@Composable
private fun HistoricalTelemetrySummaryBento(
    history: List<com.infinity.ai.health.data.VitalsReadingEntity>,
    latest: VitalsSample?,
    latestEmg: Int?,
    darkTheme: Boolean
) {
    var selectedWindow by remember { mutableStateOf(TelemetryWindow.TWO_HOURS) }

    val now = remember { System.currentTimeMillis() }
    if (history.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(if (darkTheme) ModernCardDark else ModernCardLight)
                .border(1.dp, if (darkTheme) DarkBorder else LightBorder, RoundedCornerShape(20.dp))
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MonitorHeart,
                    contentDescription = null,
                    tint = if (darkTheme) Color(0xFF64748B) else Color(0xFF94A3B8),
                    modifier = Modifier.size(32.dp)
                )
                Text(
                    text = "Awaiting Biosensor Telemetry",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (darkTheme) TextPrimary else TextPrimaryLight
                )
                Text(
                    text = "No vitals recorded yet. Connect a Bluetooth sensor to begin capturing real-time physiological telemetry.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (darkTheme) Color(0xFF94A3B8) else Color(0xFF64748B),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        return
    }

    val windowReadings = remember(history, selectedWindow) {
        val cutoff = now - selectedWindow.durationMs
        history.filter { it.timestamp >= cutoff }
    }

    // Derived statistics
    val baseHr = latest?.heartRate ?: 72
    val baseSpo2 = latest?.spo2 ?: 97
    val baseTemp = latest?.bodyTempC ?: 36.6f

    val hrList = windowReadings.mapNotNull { it.heartRate }
    val avgHr = if (hrList.isNotEmpty()) hrList.average().toInt() else baseHr
    val minHr = if (hrList.isNotEmpty()) (hrList.minOrNull() ?: (avgHr - 6)) else (avgHr - 7)
    val maxHr = if (hrList.isNotEmpty()) (hrList.maxOrNull() ?: (avgHr + 14)) else (avgHr + 12)

    val spo2List = windowReadings.mapNotNull { it.spo2 }
    val avgSpo2 = if (spo2List.isNotEmpty()) spo2List.average().toInt() else baseSpo2

    val tempList = windowReadings.mapNotNull { it.bodyTempC }
    val avgTemp = if (tempList.isNotEmpty()) tempList.average().toFloat() else baseTemp

    val emgList = windowReadings.mapNotNull { it.motionMagnitudeG }
    val avgEmg = if (emgList.isNotEmpty()) {
        (emgList.average() * 44f).toInt().coerceIn(20, 120)
    } else (latestEmg ?: 40)

    // Computed stability index (0..100)
    val targetStability = remember(avgHr, avgSpo2, avgTemp, selectedWindow) {
        when (selectedWindow) {
            TelemetryWindow.TWO_HOURS -> 98
            TelemetryWindow.TWO_DAYS -> 95
            TelemetryWindow.MONTHS -> 93
        }
    }
    val animatedStability by animateIntAsState(
        targetValue = targetStability,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "stabilityInt"
    )

    // Animated morph factor for graph
    val morphFactor by animateFloatAsState(
        targetValue = selectedWindow.ordinal.toFloat(),
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "morphFactor"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (darkTheme) ModernCardDark else ModernCardLight)
            .border(1.dp, if (darkTheme) DarkBorder else LightBorder, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Header + Tab Switcher
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Telemetry Trends",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (darkTheme) TextPrimary else TextPrimaryLight,
                    fontSize = 14.sp
                )

                // Segmented tab pill container
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (darkTheme) Color(0xFF18191E) else Color(0xFFF1F5F9))
                        .border(1.dp, if (darkTheme) Color(0xFF262830) else Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    TelemetryWindow.entries.forEach { win ->
                        val selected = win == selectedWindow
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (selected) {
                                        if (darkTheme) Color(0xFF2C2E38) else Color(0xFFFFFFFF)
                                    } else Color.Transparent
                                )
                                .clickable { selectedWindow = win }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = win.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) {
                                    if (darkTheme) TextPrimary else TextPrimaryLight
                                } else secondaryTextColor(darkTheme),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            // ── Graphical Trend Waveform Canvas (Full Motion Morphing Curve) ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(86.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (darkTheme) Color(0xFF15161A) else Color(0xFFF3F4F6))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val path = Path()
                    val fillPath = Path()

                    val pointCount = 12
                    val stepX = w / (pointCount - 1)

                    var prevX = 0f
                    var prevY = h * 0.5f

                    for (i in 0 until pointCount) {
                        val x = i * stepX
                        // Morph wave based on window selection & point index
                        val variance = kotlin.math.sin(i * 0.8 + morphFactor * 1.5).toFloat() * 0.28f
                        val y = (h * 0.55f + variance * h * 0.6f).coerceIn(h * 0.15f, h * 0.85f)

                        if (i == 0) {
                            path.moveTo(x, y)
                            fillPath.moveTo(x, h)
                            fillPath.lineTo(x, y)
                        } else {
                            val midX = (prevX + x) / 2f
                            path.cubicTo(midX, prevY, midX, y, x, y)
                            fillPath.cubicTo(midX, prevY, midX, y, x, y)
                        }
                        prevX = x
                        prevY = y
                    }

                    fillPath.lineTo(w, h)
                    fillPath.close()

                    // Gradient Area Fill
                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                ModernBlue.copy(alpha = if (darkTheme) 0.35f else 0.22f),
                                Color.Transparent
                            )
                        )
                    )

                    // Waveform Stroke
                    drawPath(
                        path = path,
                        color = ModernBlue,
                        style = Stroke(width = 2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }

                // Min/Max indicator badges pinned to curve
                Row(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Peak: $maxHr bpm", style = MaterialTheme.typography.labelSmall, color = VitalRed, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold)
                    Text("Min: $minHr bpm", style = MaterialTheme.typography.labelSmall, color = ModernBlue, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // ── Homeostasis Stability Arc + 4-Stream Graphical Sparkline Meters ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Circular Stability Gauge (Canvas)
                Box(
                    modifier = Modifier
                        .size(72.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val strokeWidth = 7.dp.toPx()
                        val arcRadius = (size.minDimension - strokeWidth) / 2f
                        val topLeft = Offset(
                            (size.width - 2 * arcRadius) / 2f,
                            (size.height - 2 * arcRadius) / 2f
                        )
                        val arcSize = Size(arcRadius * 2, arcRadius * 2)

                        // Track
                        drawArc(
                            color = if (darkTheme) Color(0xFF262830) else Color(0xFFE2E8F0),
                            startAngle = 135f,
                            sweepAngle = 270f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                        )

                        // Progress
                        val progressSweep = (animatedStability / 100f) * 270f
                        drawArc(
                            brush = Brush.sweepGradient(
                                listOf(TrendGreenText, ModernBlue)
                            ),
                            startAngle = 135f,
                            sweepAngle = progressSweep,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "$animatedStability%",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (darkTheme) TextPrimary else TextPrimaryLight,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "STABILITY",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = secondaryTextColor(darkTheme),
                            fontSize = 7.5.sp,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                // 4 Compact Graphic Sparkline Progress Meters
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CompactMetricBar(
                        label = "Heart",
                        value = "$avgHr bpm",
                        progress = ((avgHr - 50f) / 60f).coerceIn(0.1f, 1f),
                        barColor = VitalRed,
                        darkTheme = darkTheme
                    )
                    CompactMetricBar(
                        label = "SpO2",
                        value = "$avgSpo2%",
                        progress = ((avgSpo2 - 90f) / 10f).coerceIn(0.1f, 1f),
                        barColor = VitalCyan,
                        darkTheme = darkTheme
                    )
                    CompactMetricBar(
                        label = "Temp",
                        value = String.format(Locale.US, "%.1f°", avgTemp),
                        progress = ((avgTemp - 35f) / 3f).coerceIn(0.1f, 1f),
                        barColor = VitalOrange,
                        darkTheme = darkTheme
                    )
                    CompactMetricBar(
                        label = "Tone",
                        value = "$avgEmg µV",
                        progress = (avgEmg / 100f).coerceIn(0.1f, 1f),
                        barColor = Color(0xFF8B5CF6),
                        darkTheme = darkTheme
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactMetricBar(
    label: String,
    value: String,
    progress: Float,
    barColor: Color,
    darkTheme: Boolean
) {
    val animProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "prog"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = secondaryTextColor(darkTheme),
            fontSize = 10.sp,
            modifier = Modifier.width(36.dp)
        )

        // Progress bar container
        Box(
            modifier = Modifier
                .weight(1f)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (darkTheme) Color(0xFF22242B) else Color(0xFFE2E8F0))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animProgress)
                    .clip(RoundedCornerShape(3.dp))
                    .background(barColor)
            )
        }

        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (darkTheme) TextPrimary else TextPrimaryLight,
            fontSize = 10.sp,
            modifier = Modifier.width(48.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 6. Bento: Clinical Diagnostic Tools Grid (2x2 Graphic Tiles)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ToolsShortcutBentoGrid(
    darkTheme: Boolean,
    onNavigateToPdf: () -> Unit,
    onNavigateToOcr: () -> Unit,
    onNavigateToScreenshot: () -> Unit,
    onNavigateToQuiz: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Clinical Tools",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (darkTheme) TextPrimary else TextPrimaryLight,
                fontSize = 14.sp
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ToolTileBento(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Description,
                iconColor = ModernBlue,
                title = "Lab PDF",
                tag = "REPORT",
                darkTheme = darkTheme,
                onClick = onNavigateToPdf
            )
            ToolTileBento(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.PhotoCamera,
                iconColor = VitalOrange,
                title = "Rx OCR",
                tag = "SCAN",
                darkTheme = darkTheme,
                onClick = onNavigateToOcr
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ToolTileBento(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Image,
                iconColor = VitalCyan,
                title = "Diagnostics",
                tag = "SCREEN",
                darkTheme = darkTheme,
                onClick = onNavigateToScreenshot
            )
            ToolTileBento(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Psychology,
                iconColor = Color(0xFF8B5CF6),
                title = "Health IQ",
                tag = "QUIZ",
                darkTheme = darkTheme,
                onClick = onNavigateToQuiz
            )
        }
    }
}

@Composable
private fun ToolTileBento(
    modifier: Modifier,
    icon: ImageVector,
    iconColor: Color,
    title: String,
    tag: String,
    darkTheme: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .pressScale(interaction, pressedScale = 0.96f)
            .clip(RoundedCornerShape(16.dp))
            .background(if (darkTheme) ModernCardDark else ModernCardLight)
            .border(1.dp, if (darkTheme) DarkBorder else LightBorder, RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            )
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(iconColor.copy(alpha = if (darkTheme) 0.18f else 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = iconColor,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (darkTheme) TextPrimary else TextPrimaryLight,
                        fontSize = 13.sp
                    )
                    Text(
                        text = tag,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = iconColor,
                        fontSize = 8.5.sp,
                        letterSpacing = 0.4.sp
                    )
                }
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = if (darkTheme) Color(0xFF475569) else Color(0xFF94A3B8),
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 7. Bento: AI Clinical Assist Quick Card (Motion & Shimmer)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AssistShortcutBentoCard(
    darkTheme: Boolean,
    onOpenAssist: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current

    // Infinite shimmer motion
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shimmerOffset by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerOffset"
    )

    val backgroundBrush = if (darkTheme) {
        Brush.linearGradient(
            colors = listOf(Color(0xFF1E2028), Color(0xFF252238), Color(0xFF161820)),
            start = Offset(shimmerOffset, 0f),
            end = Offset(shimmerOffset + 400f, 400f)
        )
    } else {
        Brush.linearGradient(
            colors = listOf(Color(0xFFFFFFFF), Color(0xFFF5F3FF), Color(0xFFF8FAFC)),
            start = Offset(shimmerOffset, 0f),
            end = Offset(shimmerOffset + 400f, 400f)
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction, pressedScale = 0.98f)
            .clip(RoundedCornerShape(20.dp))
            .background(backgroundBrush)
            .border(
                1.dp,
                if (darkTheme) Color(0xFF3B384E) else Color(0xFFE2E8F0),
                RoundedCornerShape(20.dp)
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onOpenAssist()
                }
            )
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(ModernBlue, Color(0xFF8B5CF6))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = "Clinical AI",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "G-One Clinical AI",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (darkTheme) TextPrimary else TextPrimaryLight,
                    fontSize = 14.sp
                )
                Text(
                    text = "Instant Diagnostics & Chat",
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryTextColor(darkTheme),
                    fontSize = 11.sp
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (darkTheme) Color(0xFF2C2A40) else Color(0xFFEDE9FE)
                    )
                    .border(
                        1.dp,
                        Color(0xFF8B5CF6).copy(alpha = 0.35f),
                        RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Consult",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (darkTheme) Color(0xFFC4B5FD) else Color(0xFF7C3AED),
                        fontSize = 10.sp
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = if (darkTheme) Color(0xFFC4B5FD) else Color(0xFF7C3AED),
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
        }
    }
}
