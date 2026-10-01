package com.gone.ai.health.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.gone.ai.health.service.HealthMonitoringService
import com.gone.ai.health.service.WearableLiveState
import com.gone.ai.health.source.SourceStatus
import com.gone.ai.health.source.VitalsScenario
import com.gone.ai.health.domain.VitalsSample
import com.gone.ai.health.domain.Temperature
import com.gone.ai.health.data.AnomalyEventEntity
import com.gone.ai.health.data.severityEnum
import com.gone.ai.health.domain.Severity
import com.gone.ai.ui.theme.SuccessGreen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gone.ai.ui.components.BreadcrumbItem
import com.gone.ai.ui.components.HeaderActionPill
import com.gone.ai.ui.components.PureBreadcrumbText
import com.gone.ai.ui.components.WatchScanningLottieAnimation
import com.gone.ai.ui.theme.LightBorder
import com.gone.ai.ui.theme.LightShadow
import com.gone.ai.ui.theme.ModernBgDark
import com.gone.ai.ui.theme.ModernBgLight
import com.gone.ai.ui.theme.ModernBlue
import com.gone.ai.ui.theme.ModernBorderDark
import com.gone.ai.ui.theme.ModernBorderLight
import com.gone.ai.ui.theme.ModernCardDark
import com.gone.ai.ui.theme.ModernCardLight
import com.gone.ai.ui.theme.TextPrimary
import com.gone.ai.ui.theme.TextPrimaryLight
import com.gone.ai.ui.theme.VitalCyan
import com.gone.ai.ui.theme.VitalOrange
import com.gone.ai.ui.theme.VitalRed
import com.gone.ai.ui.theme.pressScale
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * The usual range for skin temperature at the wrist. Skin follows the room, so readings
 * outside it are "cool" or "warm", never a fever. Shared with the Home screen.
 */
internal object SkinTempBand {
    const val TYPICAL_MIN_C = 30f
    const val TYPICAL_MAX_C = 35.5f
}

/**
 * Live Monitor surface.
 *
 * 1. Breadcrumb header ("Home / Live Monitor") with Alerts and Trails actions.
 * 2. Start / stop monitoring, with the permission requests monitoring needs, and the
 *    simulator scenario picker while stopped.
 * 3. Session controls: start a session, then end it for a report.
 * 4. Wearable card: the link as the monitoring service sees it, and any sensor problem the
 *    wearable reports. Battery is never shown: the hardware cannot measure it.
 * 5. Charts of recorded readings only — heart rate, motion, core temperature, SpO₂, muscle
 *    activity (EMG, with the raw live trace while streaming) and skin temperature.
 */
@Composable
fun LiveMonitorScreen(
    isDarkTheme: Boolean,
    bottomPadding: Dp,
    onNavigateHome: () -> Unit = {},
    onOpenAlerts: () -> Unit = {},
    onNavigateToTrails: () -> Unit = {},
    onOpenDevice: () -> Unit = {},
    onOpenReports: () -> Unit = {},
    vm: HealthViewModel = viewModel()
) {
    val context = LocalContext.current
    val snapshot by vm.snapshot.collectAsState()
    val isMonitoring by vm.isMonitoring.collectAsState()
    // Since monitoring or the session started, so every graph and tile here begins from nothing.
    val history by vm.liveHistory.collectAsState()
    val activeEvents by vm.activeEvents.collectAsState()
    val simulated by vm.simulationEnabled.collectAsState()
    val scenario by vm.scenario.collectAsState()
    val savedWearable by vm.savedWearable.collectAsState()
    val wearableLink by vm.wearable.collectAsState()
    val activeSession by vm.activeSession.collectAsState()
    val sessionBusy by vm.sessionBusy.collectAsState()
    var showLiveAlertsSheet by remember { mutableStateOf(false) }
    val usingWearable = isMonitoring && !simulated && savedWearable != null

    // Notifications for alerts and the Bluetooth permission (Android 12+ for the wearable,
    // 14+ for the foreground-service type). startMonitoring re-checks and explains a refusal.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.startMonitoring(context) }

    fun start() {
        val needed = HealthMonitoringService.permissionsToRequest(context)
        if (needed.isEmpty()) vm.startMonitoring(context) else permissionLauncher.launch(needed.toTypedArray())
    }

    val latest = snapshot.latest
    // Recorded readings with their real timestamps — nothing synthesized.
    val hrPoints = remember(history) {
        history.mapNotNull { r -> r.heartRate?.let { ChartPoint(r.timestamp, it.toFloat()) } }.takeLast(48)
    }
    val motionHistory = remember(history) {
        history.mapNotNull { r -> r.motionMagnitudeG?.let { ChartPoint(r.timestamp, it) } }.takeLast(40)
    }
    val tempHistory = remember(history) {
        history.mapNotNull { r -> r.bodyTempC?.let { ChartPoint(r.timestamp, Temperature.fahrenheit(it)) } }.takeLast(40)
    }
    val spo2History = remember(history) {
        history.mapNotNull { r -> r.spo2?.let { ChartPoint(r.timestamp, it.toFloat()) } }.takeLast(40)
    }
    val emgHistory = remember(history) {
        history.mapNotNull { r -> r.emgMean?.let { ChartPoint(r.timestamp, it.toFloat()) } }.takeLast(48)
    }
    val skinHistory = remember(history) {
        history.mapNotNull { r -> r.skinTempC?.let { ChartPoint(r.timestamp, Temperature.fahrenheit(it)) } }.takeLast(40)
    }

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
        ) {
            PureBreadcrumbText(
                items = listOf(BreadcrumbItem("Home", onNavigateHome), BreadcrumbItem("Live Monitor")),
                isDarkTheme = isDarkTheme,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
            )
            Column(
                Modifier.weight(1f).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
            ) {

            // ── 1. Top Bar: Action Buttons (Non-sticky, scroll away with content) ───
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                HeaderActionPill(
                    icon = if (activeEvents.isNotEmpty()) Icons.Default.NotificationsActive else Icons.Default.Notifications,
                    label = "Alerts",
                    darkTheme = isDarkTheme,
                    badgeCount = activeEvents.size,
                    onClick = { showLiveAlertsSheet = true }
                )
                Spacer(Modifier.width(8.dp))
                HeaderActionPill(
                    icon = Icons.AutoMirrored.Filled.ShowChart,
                    label = "Trails",
                    darkTheme = isDarkTheme,
                    onClick = onNavigateToTrails
                )
            }

            Spacer(Modifier.height(18.dp))

            // ── 2. Live Monitoring Control Banner (Single Non-Duplicate Sentinel) ─
            MonitorStatusControlCard(
                isMonitoring = isMonitoring,
                simulated = simulated,
                scenario = scenario,
                wearableName = savedWearable?.name,
                link = wearableLink.link,
                isDarkTheme = isDarkTheme,
                onScenarioChange = vm::setScenario,
                onStart = ::start,
                onStop = { vm.stopMonitoring(context) }
            )

            Spacer(Modifier.height(24.dp))

            // ── 3. Session controls ───────────────────────────────────────────
            SessionControlsCard(
                activeSessionStartedAt = activeSession?.startedAt,
                isMonitoring = isMonitoring,
                busy = sessionBusy,
                isDarkTheme = isDarkTheme,
                onStart = vm::startSession,
                onFinish = vm::finishSession,
                onCancel = vm::cancelSession,
                onOpenReports = onOpenReports
            )

            Spacer(Modifier.height(24.dp))

            // ── 4. Wearable link ──────────────────────────────────────────────
            WearableLinkCard(
                simulated = simulated,
                wearableName = savedWearable?.name,
                usingWearable = usingWearable,
                live = wearableLink,
                isDarkTheme = isDarkTheme,
                onClick = onOpenDevice,
                onSyncNow = { vm.syncNow(context) }
            )

            Spacer(Modifier.height(28.dp))

            // ── 5. Charts of recorded readings ────────────────────────────────
            // 4a. Hero Full-Width Card: Heart rate
            val currentHr = latest?.heartRate ?: hrPoints.lastOrNull()?.value?.toInt()
            val hrStatus = when {
                currentHr == null -> HumanStatus("Standby", "No heart-rate readings yet", if (isDarkTheme) Color(0xFF94A3B8) else Color(0xFF64748B))
                currentHr < 60 -> HumanStatus("Slow", "Heart rate is slow. Normal during rest", Color(0xFFF59E0B))
                currentHr <= 100 -> HumanStatus("Steady", "Heart rate is in the usual range", Color(0xFF10B981))
                else -> HumanStatus("Fast", "Heart rate is elevated. Take a moment to rest", Color(0xFFEF4444))
            }

            BentoFullWidthChartCard(
                title = "Heart Rate",
                subtitle = when {
                    isMonitoring && simulated -> "Live · simulated readings"
                    usingWearable -> "Live · from ${savedWearable?.name ?: "the wearable"}"
                    currentHr != null -> "Most recent readings"
                    else -> "No readings yet"
                },
                value = if (currentHr != null) "$currentHr BPM" else "-- BPM",
                valueColor = VitalRed,
                icon = Icons.Default.Favorite,
                statusWord = hrStatus.word,
                statusColor = hrStatus.color,
                takeawayText = hrStatus.takeaway,
                safeRangeLabel = "Safe: 60–100 BPM",
                safeMin = 60f,
                safeMax = 100f,
                points = hrPoints,
                color = VitalRed,
                yMin = 40f,
                yMax = 140f,
                darkTheme = isDarkTheme
            )

            Spacer(Modifier.height(28.dp))

            // Motion and actual skin temperature use full-width cards for legible labels.
            val hasData = latest != null || history.isNotEmpty()
            val currentMotion = latest?.motionMagnitudeG ?: motionHistory.lastOrNull()?.value
            val motionStatus = when (MotionLevel.of(currentMotion)) {
                null -> HumanStatus("Standby", "No motion readings yet", if (isDarkTheme) Color(0xFF94A3B8) else Color(0xFF64748B))
                MotionLevel.STILL -> HumanStatus("Still", "At rest", Color(0xFF10B981))
                MotionLevel.MOVING -> HumanStatus("Moving", "Movement detected", Color(0xFF8B5CF6))
                MotionLevel.IMPACT -> HumanStatus("Impact", "Hard jolt detected", Color(0xFFEF4444))
            }

            val currentTemp = latest?.bodyTempC ?: history.lastOrNull { it.bodyTempC != null }?.bodyTempC
            val tempStatus = when {
                // The wearable measures skin, not core, temperature; its absence here is expected.
                currentTemp == null && usingWearable -> HumanStatus("None", "The wearable has no core temperature sensor", if (isDarkTheme) Color(0xFF94A3B8) else Color(0xFF64748B))
                currentTemp == null -> HumanStatus("Standby", "Awaiting temperature packet", if (isDarkTheme) Color(0xFF94A3B8) else Color(0xFF64748B))
                currentTemp < 36.0f -> HumanStatus("Cool", "Slightly low body temperature", Color(0xFF38BDF8))
                currentTemp <= 37.3f -> HumanStatus("Normal", "Optimal core temperature", Color(0xFF10B981))
                currentTemp <= 38.0f -> HumanStatus("Warm", "Slightly warm body temperature", Color(0xFFF59E0B))
                else -> HumanStatus("Fever", "Elevated temperature (Fever)", Color(0xFFEF4444))
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                // Motion (accelerometer magnitude, ~1 g at rest)
                BentoCompactTile(
                    title = "Motion",
                    subtitle = "Movement",
                    value = currentMotion?.let { String.format(Locale.US, "%.2f g", it) } ?: "-- g",
                    valueColor = Color(0xFF8B5CF6),
                    statusWord = motionStatus.word,
                    statusColor = motionStatus.color,
                    takeawayText = motionStatus.takeaway,
                    safeRangeLabel = "Rest: ≤ 1.15 g",
                    safeMin = 0.9f,
                    safeMax = VitalsSample.REST_MOTION_G,
                    points = if (hasData) motionHistory else emptyList(),
                    color = Color(0xFF8B5CF6),
                    yMin = 0.8f,
                    yMax = 3.5f,
                    darkTheme = isDarkTheme,
                    modifier = Modifier.fillMaxWidth()
                )

            // 4e. Skin temperature: shown and trended, never read as a fever
            val currentSkin = latest?.skinTempC ?: history.lastOrNull { it.skinTempC != null }?.skinTempC
            val skinStatus = when {
                currentSkin == null -> HumanStatus("Standby", "No skin temperature readings yet", if (isDarkTheme) Color(0xFF94A3B8) else Color(0xFF64748B))
                currentSkin < SkinTempBand.TYPICAL_MIN_C -> HumanStatus("Cool", "Often the room or a loose fit", Color(0xFF38BDF8))
                currentSkin <= SkinTempBand.TYPICAL_MAX_C -> HumanStatus("Typical", "Usual range for skin", Color(0xFF10B981))
                else -> HumanStatus("Warm", "Skin follows the room; not a fever reading", Color(0xFFF59E0B))
            }
            BentoCompactTile(
                title = "Skin Temp",
                subtitle = "Skin, not core temperature",
                value = currentSkin?.let { Temperature.fahrenheitText(it).replace(" ", "") } ?: "--°F",
                valueColor = VitalOrange,
                statusWord = skinStatus.word,
                statusColor = skinStatus.color,
                takeawayText = skinStatus.takeaway,
                safeRangeLabel = "Typical skin: 86.0–95.9°F",
                safeMin = Temperature.fahrenheit(SkinTempBand.TYPICAL_MIN_C),
                safeMax = Temperature.fahrenheit(SkinTempBand.TYPICAL_MAX_C),
                points = if (hasData) skinHistory else emptyList(),
                color = VitalOrange,
                yMin = Temperature.fahrenheit(26f),
                yMax = Temperature.fahrenheit(42f),
                darkTheme = isDarkTheme,
                modifier = Modifier.fillMaxWidth()
            )

            }

            if (simulated || currentTemp != null) {
                Spacer(Modifier.height(28.dp))
                // Core temperature is only shown for sources that provide it.
                BentoCompactTile(
                    title = "Body Temp",
                    subtitle = "Core temperature",
                    value = currentTemp?.let { Temperature.fahrenheitText(it).replace(" ", "") } ?: "--°F",
                    valueColor = VitalOrange,
                    statusWord = tempStatus.word,
                    statusColor = tempStatus.color,
                    takeawayText = tempStatus.takeaway,
                    safeRangeLabel = "Safe: 97.7–99.0°F",
                    safeMin = Temperature.fahrenheit(36.5f),
                    safeMax = Temperature.fahrenheit(37.2f),
                    points = if (hasData) tempHistory else emptyList(),
                    color = VitalOrange,
                    yMin = Temperature.fahrenheit(35.0f),
                    yMax = Temperature.fahrenheit(39.5f),
                    darkTheme = isDarkTheme,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(28.dp))

            // 4c. Wide Full-Width Bento Card: Blood Oxygen (SpO2) Saturation
            val currentSpo2 = latest?.spo2 ?: spo2History.lastOrNull()?.value?.toInt()
            val spo2Status = when {
                currentSpo2 == null -> HumanStatus("Standby", "Awaiting SpO2 sensor packet", if (isDarkTheme) Color(0xFF94A3B8) else Color(0xFF64748B))
                currentSpo2 >= 95 -> HumanStatus("Optimal", "Lungs absorbing healthy oxygen", Color(0xFF10B981))
                currentSpo2 >= 90 -> HumanStatus("Monitor", "Oxygen slightly low. Deep breathe", Color(0xFFF59E0B))
                else -> HumanStatus("Low", "Oxygen level is low. Check sensor", Color(0xFFEF4444))
            }

            BentoFullWidthChartCard(
                title = "Blood Oxygen",
                subtitle = "Arterial oxygen saturation",
                value = if (currentSpo2 != null) "$currentSpo2%" else "--%",
                valueColor = VitalCyan,
                icon = Icons.Default.Air,
                statusWord = spo2Status.word,
                statusColor = spo2Status.color,
                takeawayText = spo2Status.takeaway,
                safeRangeLabel = "Safe: 95%–100%",
                safeMin = 95f,
                safeMax = 100f,
                points = if (hasData) spo2History else emptyList(),
                color = VitalCyan,
                yMin = 85f,
                yMax = 100f,
                darkTheme = isDarkTheme
            )

            Spacer(Modifier.height(28.dp))

            // 4d. Muscle activity (EMG): stored 5-second readings, plus the raw live trace
            val latestEmg = latest?.emgMean ?: emgHistory.lastOrNull()?.value?.toInt()
            val emgLevel = EmgLevel.of(latestEmg, vm.thresholds)
            val mutedStatus = if (isDarkTheme) Color(0xFF94A3B8) else Color(0xFF64748B)
            val emgStatus = when (emgLevel) {
                null -> HumanStatus("Standby", "No muscle sensor readings yet", mutedStatus)
                EmgLevel.RELAXED -> HumanStatus(emgLevel.label, "Muscle at rest", Color(0xFF10B981))
                EmgLevel.ACTIVE -> HumanStatus(emgLevel.label, "Muscle in use", Color(0xFF8B5CF6))
                EmgLevel.HIGH -> HumanStatus(emgLevel.label, "Held high while still, this is flagged", Color(0xFFF59E0B))
                EmgLevel.VERY_HIGH -> HumanStatus(emgLevel.label, "Very high activity", Color(0xFFF59E0B))
                EmgLevel.SENSOR_PROBLEM -> HumanStatus(emgLevel.label, "Input pinned at the top: a pad may be loose", Color(0xFFEF4444))
            }
            BentoFullWidthChartCard(
                title = "Muscle Activity",
                subtitle = "EMG · uncalibrated 0–4095 scale",
                value = latestEmg?.toString() ?: "--",
                valueColor = Color(0xFF8B5CF6),
                icon = Icons.Default.Sensors,
                statusWord = emgStatus.word,
                statusColor = emgStatus.color,
                takeawayText = emgStatus.takeaway,
                safeRangeLabel = "Relaxed: below ${vm.thresholds.emgActiveLevel}",
                safeMin = 0f,
                safeMax = vm.thresholds.emgActiveLevel.toFloat(),
                points = if (hasData) emgHistory else emptyList(),
                color = Color(0xFF8B5CF6),
                yMin = 0f,
                yMax = VitalsSample.EMG_ADC_MAX.toFloat(),
                darkTheme = isDarkTheme
            )
            if (usingWearable && wearableLink.emgTrace.size >= 2) {
                Spacer(Modifier.height(12.dp))
                LiveEmgTrace(
                    trace = wearableLink.emgTrace,
                    activeLevel = vm.thresholds.emgActiveLevel,
                    highLevel = vm.thresholds.emgHighLevel,
                    veryHighLevel = vm.thresholds.emgVeryHighLevel,
                    isDarkTheme = isDarkTheme
                )
            }

            Spacer(Modifier.height(28.dp))

            // ── 5. Active Alert Banner (If Any Events Present) ────────────────
            AnimatedVisibility(
                visible = activeEvents.isNotEmpty(),
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                activeEvents.firstOrNull()?.let { event ->
                    Column {
                        Spacer(Modifier.height(26.dp))
                        ActiveAlertBanner(
                            event = event,
                            extraCount = activeEvents.size - 1,
                            darkTheme = isDarkTheme,
                            onClick = onOpenAlerts
                        )
                    }
                }
            }

            Spacer(Modifier.height(bottomPadding + 90.dp))
            }
        }

        if (showLiveAlertsSheet) {
            LiveAlertsBottomSheet(
                activeEvents = activeEvents,
                isDarkTheme = isDarkTheme,
                onDismiss = { showLiveAlertsSheet = false },
                onViewAllTrails = {
                    showLiveAlertsSheet = false
                    onNavigateToTrails()
                }
            )
        }


    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. Breadcrumbs Top Bar ("Home / Live Monitor" + Alerts Action)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MonitorBreadcrumbTopBar(
    isDarkTheme: Boolean,
    activeAlertCount: Int,
    onNavigateHome: () -> Unit,
    onOpenAlerts: () -> Unit,
    onNavigateToTrails: () -> Unit
) {
    val interactionHome = remember { MutableInteractionSource() }
    val interactionAlert = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Breadcrumb: Home / Live Monitor
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Home",
                style = MaterialTheme.typography.titleMedium,
                color = ModernBlue,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                modifier = Modifier
                    .pressScale(interactionHome)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button,
                        interactionSource = interactionHome,
                        indication = null,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onNavigateHome()
                        }
                    )
                    .padding(vertical = 4.dp, horizontal = 2.dp)
            )

            Text(
                text = "  /  ",

                modifier = Modifier.clearAndSetSemantics { },
                style = MaterialTheme.typography.titleMedium,
                color = if (isDarkTheme) Color(0xFF64748B) else Color(0xFF94A3B8),
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp
            )

            Text(
                modifier = Modifier.semantics { heading() },
                text = "Live Monitor",
                style = MaterialTheme.typography.titleLarge,
                color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HeaderActionPill(
                icon = if (activeAlertCount > 0) Icons.Default.NotificationsActive else Icons.Default.Notifications,
                label = "Alerts",
                darkTheme = isDarkTheme,
                badgeCount = activeAlertCount,
                onClick = onOpenAlerts
            )
            HeaderActionPill(
                icon = Icons.AutoMirrored.Filled.ShowChart,
                label = "Trails",
                darkTheme = isDarkTheme,
                onClick = onNavigateToTrails
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. Monitoring Status & Control (Borderless, Flat on Screen, Aesthetic 130dp Watch)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MonitorStatusControlCard(
    isMonitoring: Boolean,
    simulated: Boolean,
    scenario: VitalsScenario,
    wearableName: String?,
    link: SourceStatus,
    isDarkTheme: Boolean,
    onScenarioChange: (VitalsScenario) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current

    val cornerRadius by animateDpAsState(
        targetValue = if (isMonitoring) 14.dp else 24.dp,
        animationSpec = spring(
            dampingRatio = 0.75f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "monitorBtnCorner"
    )
    val buttonShape = RoundedCornerShape(cornerRadius)

    val buttonBgColor by animateColorAsState(
        targetValue = if (isMonitoring) {
            if (isDarkTheme) Color(0xFF1E293B).copy(alpha = 0.9f)
            else Color(0xFFE2E8F0).copy(alpha = 0.9f)
        } else {
            Color(0xFF10B981)
        },
        animationSpec = tween(280),
        label = "monitorBtnBg"
    )
    val buttonContentColor by animateColorAsState(
        targetValue = if (isMonitoring) {
            if (isDarkTheme) Color(0xFFE2E8F0) else Color(0xFF334155)
        } else {
            Color.White
        },
        animationSpec = tween(280),
        label = "monitorBtnFg"
    )

    // Borderless and flat on the screen itself
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Aesthetic Watch Scanning Lottie - Always prominent and beautifully displayed (130dp)
        WatchScanningLottieAnimation(
            modifier = Modifier.size(130.dp)
        )

        Spacer(Modifier.height(10.dp))

        // Status header with smooth animated transition
        AnimatedContent(
            targetState = isMonitoring,
            transitionSpec = {
                fadeIn(tween(220)) togetherWith fadeOut(tween(180))
            },
            label = "monitorStatusHeader"
        ) { monitoring ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (!monitoring) {
                    Text(
                        text = "Monitoring off",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )

                    Spacer(Modifier.height(3.dp))

                    Text(
                        text = when {
                            simulated -> "Simulated vitals · one reading every 5 seconds"
                            wearableName != null -> "Wearable: $wearableName · one reading stored every 5 seconds"
                            else -> "No data source — choose a wearable, or turn on simulated vitals in Settings"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isDarkTheme) Color(0xFF94A3B8) else Color(0xFF64748B),
                        fontWeight = FontWeight.Normal,
                        fontSize = 12.5.sp
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        simulated || link is SourceStatus.Streaming -> Color(0xFF10B981)
                                        link is SourceStatus.Failed -> Color(0xFFEF4444)
                                        else -> Color(0xFFF59E0B)
                                    }
                                )
                        )
                        Text(
                            text = "Live Monitoring Active",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }

                    Spacer(Modifier.height(3.dp))

                    Text(
                        text = if (simulated) "${scenario.displayName} · one reading every 5 seconds"
                               else linkSummary(link, wearableName),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isDarkTheme) Color(0xFF94A3B8) else Color(0xFF64748B),
                        fontWeight = FontWeight.Normal,
                        fontSize = 12.5.sp
                    )
                }
            }
        }

        // Scenario is a setup choice: hidden while running, because switching mid-run would
        // invalidate the baseline the detector has been learning.
        if (simulated && !isMonitoring) {
            Spacer(Modifier.height(12.dp))
            ScenarioPicker(selected = scenario, isDarkTheme = isDarkTheme, onSelect = onScenarioChange)
        }

        Spacer(Modifier.height(16.dp))

        // Morphing Start / Stop Monitoring Button
        Box(
            modifier = Modifier
                .pressScale(interaction, pressedScale = 0.95f)
                .shadow(
                    elevation = if (isDarkTheme) 0.dp else if (!isMonitoring) 4.dp else 1.dp,
                    shape = buttonShape,
                    ambientColor = LightShadow,
                    spotColor = LightShadow
                )
                .clip(buttonShape)
                .background(buttonBgColor)
                .clickable(role = Role.Button,
                    interactionSource = interaction,
                    indication = null,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (isMonitoring) onStop() else onStart()
                    }
                )
                .animateContentSize(animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow))
                .padding(horizontal = if (isMonitoring) 24.dp else 28.dp, vertical = if (isMonitoring) 10.dp else 12.dp),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = isMonitoring,
                transitionSpec = {
                    (fadeIn(tween(200)) + scaleIn(initialScale = 0.92f, animationSpec = tween(200))) togetherWith
                    (fadeOut(tween(150)) + scaleOut(targetScale = 0.92f, animationSpec = tween(150)))
                },
                label = "monitorBtnContent"
            ) { monitoring ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (monitoring) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (monitoring) "Stop" else "Start",
                        tint = buttonContentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = if (monitoring) "Stop Monitoring" else "Start Live Monitoring",
                        style = if (monitoring) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                        color = buttonContentColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (monitoring) 13.5.sp else 14.sp
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. Session Controls
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Start a session, then end it to get a report.
 *
 * A session is a time range over the readings monitoring stores anyway, so starting one
 * while monitoring is off is allowed but says plainly that nothing will be recorded.
 */
@Composable
private fun SessionControlsCard(
    activeSessionStartedAt: Long?,
    isMonitoring: Boolean,
    busy: Boolean,
    isDarkTheme: Boolean,
    onStart: () -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    onOpenReports: () -> Unit
) {
    val content = if (isDarkTheme) TextPrimary else TextPrimaryLight
    val muted = if (isDarkTheme) Color(0xFF94A3B8) else Color(0xFF64748B)

    // Ticks the elapsed time while a session runs.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(activeSessionStartedAt) {
        while (activeSessionStartedAt != null) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (isDarkTheme) ModernCardDark else ModernCardLight)
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Session", color = content, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    text = when {
                        activeSessionStartedAt == null -> "Record a stretch of monitoring and get a report"
                        !isMonitoring -> "Running ${elapsed(now - activeSessionStartedAt)} · monitoring is off, so nothing is being recorded"
                        else -> "Running ${elapsed(now - activeSessionStartedAt)}"
                    },
                    color = if (activeSessionStartedAt != null && !isMonitoring) Color(0xFFF59E0B) else muted,
                    fontSize = 12.sp
                )
            }
            TextButton(onClick = onOpenReports) { Text("Reports", color = ModernBlue, fontWeight = FontWeight.SemiBold) }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                busy -> {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = ModernBlue)
                    Text("Building the report…", color = muted, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp))
                }
                activeSessionStartedAt == null -> Button(
                    onClick = onStart,
                    colors = ButtonDefaults.buttonColors(containerColor = ModernBlue, contentColor = Color.White),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Start session", fontWeight = FontWeight.SemiBold) }
                else -> {
                    Button(
                        onClick = onFinish,
                        colors = ButtonDefaults.buttonColors(containerColor = ModernBlue, contentColor = Color.White),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("End and build report", fontWeight = FontWeight.SemiBold) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onCancel) { Text("Cancel", color = muted) }
                }
            }
        }
    }
}

private fun elapsed(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. Wearable Link Card
// ─────────────────────────────────────────────────────────────────────────────

/** How the wearable link reads in one line, for the status card and the notification. */
private fun linkSummary(link: SourceStatus, wearableName: String?): String {
    val name = wearableName ?: "the wearable"
    return when (link) {
        SourceStatus.Idle, SourceStatus.Starting -> "Connecting to $name…"
        SourceStatus.Streaming -> "Streaming from $name · one reading stored every 5 seconds"
        is SourceStatus.Recovering -> "Reconnecting to $name: ${link.reason}"
        SourceStatus.Stopped -> "Link to $name stopped"
        is SourceStatus.Failed -> link.reason
    }
}

/**
 * The wearable as the monitoring service sees it.
 *
 * Replaces a card that listed bonded Classic Bluetooth devices and said streaming was not
 * available. Everything shown here is reported by the link or the wearable itself; there is
 * no battery figure because the hardware has no way to measure one.
 */
@Composable
private fun WearableLinkCard(
    simulated: Boolean,
    wearableName: String?,
    usingWearable: Boolean,
    live: WearableLiveState,
    isDarkTheme: Boolean,
    onClick: () -> Unit,
    onSyncNow: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    val content = if (isDarkTheme) TextPrimary else TextPrimaryLight
    val muted = if (isDarkTheme) Color(0xFF94A3B8) else Color(0xFF64748B)

    val link = live.link
    val (statusLabel, statusColor) = when {
        simulated -> "Simulator" to Color(0xFF8B5CF6)
        wearableName == null -> "Choose" to ModernBlue
        !usingWearable -> "Ready" to ModernBlue
        link is SourceStatus.Streaming -> "Live" to Color(0xFF10B981)
        link is SourceStatus.Recovering -> "Reconnecting" to Color(0xFFF59E0B)
        link is SourceStatus.Failed -> "Failed" to Color(0xFFEF4444)
        else -> "Connecting" to ModernBlue
    }
    val subtitle = when {
        simulated -> "Simulated vitals are on, so the wearable is not used"
        wearableName == null -> "Tap to find and choose your wearable"
        !usingWearable -> "Connects when monitoring starts"
        else -> linkSummary(link, wearableName)
    }

    Box(
        modifier = Modifier
            .pressScale(interaction, pressedScale = 0.98f)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (isDarkTheme) ModernCardDark else ModernCardLight)
            .clickable(role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            )
            .padding(18.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(ModernBlue.copy(alpha = if (isDarkTheme) 0.22f else 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Bluetooth, contentDescription = "Wearable", tint = ModernBlue, modifier = Modifier.size(20.dp))
                    }
                    Column {
                        Text(
                            text = if (simulated) "Simulated vitals" else wearableName ?: "No wearable chosen",
                            style = MaterialTheme.typography.titleSmall,
                            color = content,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            maxLines = 1
                        )
                        Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = muted, fontSize = 11.5.sp, maxLines = 2)
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(statusColor))
                    Text(text = statusLabel, style = MaterialTheme.typography.labelSmall, color = statusColor, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
            }

            if (usingWearable) {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DeviceStatItem(
                        icon = Icons.Default.Sensors,
                        title = "Lines",
                        value = live.parser.linesAccepted.toString(),
                        valueColor = ModernBlue,
                        isDarkTheme = isDarkTheme
                    )
                    DeviceStatItem(
                        icon = Icons.Default.Warning,
                        title = "Rejected",
                        value = live.parser.linesRejected.toString(),
                        valueColor = if (live.parser.linesRejected > 0) Color(0xFFF59E0B) else muted,
                        isDarkTheme = isDarkTheme
                    )
                    DeviceStatItem(
                        icon = Icons.Default.CheckCircle,
                        title = "Clock",
                        value = when {
                            live.clockSyncFailed -> "Not set"
                            live.sensors?.clockSynced == true -> "Set"
                            else -> "—"
                        },
                        valueColor = Color(0xFF8B5CF6),
                        isDarkTheme = isDarkTheme
                    )
                    DeviceStatItem(
                        icon = Icons.Default.Sync,
                        title = "Waiting",
                        // Readings still on the wearable. (It has no battery sense line, so no battery figure.)
                        value = live.sync.pendingOnWearable?.toString() ?: "—",
                        valueColor = if ((live.sync.pendingOnWearable ?: 0) > 0) ModernBlue else muted,
                        isDarkTheme = isDarkTheme
                    )
                }

                val pending = live.sync.pendingOnWearable ?: 0
                if (pending > 0) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Catching up: $pending readings from while the phone was away are still on the wearable.",
                        color = content,
                        fontSize = 12.5.sp
                    )
                }
                if (link is SourceStatus.Recovering) {
                    TextButton(onClick = onSyncNow, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Try to reconnect now", color = ModernBlue, fontWeight = FontWeight.SemiBold)
                    }
                }

                val problems = live.sensors?.problems().orEmpty()
                if (problems.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    problems.forEach { problem ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            Icon(Icons.Default.Warning, null, tint = Color(0xFFF59E0B), modifier = Modifier.size(14.dp))
                            Text(problem, color = content, fontSize = 12.5.sp, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Live EMG trace
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The last minute of raw EMG envelope values, as they arrive from the wearable.
 *
 * Only real values are drawn — nothing interpolated or animated — with the rule's levels
 * as dashed guides. REFERENCE's "cardiac waveform" beside it was generated from a synthetic
 * heart rate, which is why there is no pulse trace here.
 */
@Composable
private fun LiveEmgTrace(
    trace: List<Int>,
    activeLevel: Int,
    highLevel: Int,
    veryHighLevel: Int,
    isDarkTheme: Boolean
) {
    val lineColor = Color(0xFF8B5CF6)
    val grid = if (isDarkTheme) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.06f)
    val muted = if (isDarkTheme) Color(0xFF94A3B8) else Color(0xFF64748B)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isDarkTheme) ModernCardDark else ModernCardLight)
            .padding(14.dp)
    ) {
        Text("Live muscle trace · last minute, raw", color = muted, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp)
                .clearAndSetSemantics {
                    contentDescription = ChartSummary.describe(
                        "Muscle signal", "",
                        trace.mapIndexed { i, v -> ChartPoint(i.toLong(), v.toFloat()) }
                    )
                }
        ) {
            val w = size.width
            val h = size.height
            val max = VitalsSample.EMG_ADC_MAX.toFloat()
            fun y(v: Int) = h - h * (v / max).coerceIn(0f, 1f)

            for (i in 0..4) drawLine(grid, Offset(0f, h * i / 4f), Offset(w, h * i / 4f), strokeWidth = 1f)
            val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
            drawLine(Color(0xFF10B981).copy(alpha = 0.4f), Offset(0f, y(activeLevel)), Offset(w, y(activeLevel)), strokeWidth = 1f, pathEffect = dash)
            drawLine(Color(0xFFF59E0B).copy(alpha = 0.5f), Offset(0f, y(highLevel)), Offset(w, y(highLevel)), strokeWidth = 1f, pathEffect = dash)
            drawLine(Color(0xFFEF4444).copy(alpha = 0.5f), Offset(0f, y(veryHighLevel)), Offset(w, y(veryHighLevel)), strokeWidth = 1f, pathEffect = dash)

            val step = w / (WearableLiveState.TRACE_LENGTH - 1).toFloat()
            val startX = w - (trace.size - 1) * step
            val path = Path()
            trace.forEachIndexed { i, v ->
                val x = startX + i * step
                if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
            }
            drawPath(path, color = lineColor, style = Stroke(width = 2f, cap = StrokeCap.Round))
        }
        Spacer(Modifier.height(4.dp))
        Text("Dashed lines: active, high, very high (uncalibrated)", color = muted, fontSize = 10.sp)
    }
}

@Composable
private fun ScenarioPicker(
    selected: VitalsScenario,
    isDarkTheme: Boolean,
    onSelect: (VitalsScenario) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val content = if (isDarkTheme) TextPrimary else TextPrimaryLight

    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (isDarkTheme) ModernCardDark else ModernCardLight)
                .clickable(role = Role.Button) { expanded = true }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Scenario: ${selected.displayName}",
                style = MaterialTheme.typography.labelMedium,
                color = content,
                fontWeight = FontWeight.SemiBold
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Choose scenario",
                tint = content,
                modifier = Modifier.size(18.dp)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            VitalsScenario.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.displayName) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun DeviceStatItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    valueColor: Color,
    isDarkTheme: Boolean
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isDarkTheme) Color(0xFF94A3B8) else Color(0xFF64748B),
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = if (isDarkTheme) Color(0xFF94A3B8) else Color(0xFF64748B),
                fontWeight = FontWeight.Normal,
                fontSize = 11.sp
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = valueColor,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
    }
}

// ── Live Alerts Bottom Sheet ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiveAlertsBottomSheet(
    activeEvents: List<AnomalyEventEntity>,
    isDarkTheme: Boolean,
    onDismiss: () -> Unit,
    onViewAllTrails: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = if (isDarkTheme) ModernCardDark else ModernCardLight,
        dragHandle = {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (isDarkTheme) ModernBorderDark else ModernBorderLight)
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Active alerts",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (activeEvents.isEmpty()) "Nothing needs attention right now" else "${activeEvents.size} not yet marked seen",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isDarkTheme) Color(0xFF94A3B8) else Color(0xFF64748B)
                    )
                }

                TextButton(onClick = onDismiss) {
                    Text("Done", color = ModernBlue, fontWeight = FontWeight.SemiBold)
                }
            }

            HorizontalDivider(color = if (isDarkTheme) ModernBorderDark else ModernBorderLight)
            Spacer(Modifier.height(14.dp))

            if (activeEvents.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = SuccessGreen,
                            modifier = Modifier.size(42.dp)
                        )
                        Text(
                            "No active alerts",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "No detection rule has an unacknowledged alert.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isDarkTheme) Color(0xFF94A3B8) else Color(0xFF64748B)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(activeEvents, key = { it.id }) { event ->
                        val sev = event.severityEnum()
                        val sevColor = severityColor(sev, isDarkTheme)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isDarkTheme) Color(0xFF1E2430) else Color(0xFFF1F5F9))
                                .border(1.dp, sevColor.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                                .padding(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(sevColor.copy(alpha = 0.15f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Warning,
                                        null,
                                        tint = sevColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = event.anomalyLabel(),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = sevColor
                                        )
                                        Text(
                                            text = sev.name,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = sevColor,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = event.displayExplanation,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = "Logged ${formatRelativeTime(event.createdAt)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isDarkTheme) Color(0xFF94A3B8) else Color(0xFF64748B)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onViewAllTrails,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ModernBlue,
                    contentColor = Color.White
                )
            ) {
                Icon(Icons.AutoMirrored.Filled.ShowChart, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open Full Trails & Trends", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun formatRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    val minutes = diff / 60000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days == 1L -> "Yesterday"
        else -> "${days}d ago"
    }
}

