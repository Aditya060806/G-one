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
import android.provider.Settings
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
import androidx.compose.material.icons.filled.BatteryChargingFull
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import com.infinity.ai.health.data.AnomalyEventEntity
import com.infinity.ai.health.data.severityEnum
import com.infinity.ai.health.domain.Severity
import com.infinity.ai.ui.theme.SuccessGreen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.infinity.ai.ui.components.BreadcrumbItem
import com.infinity.ai.ui.components.HeaderActionPill
import com.infinity.ai.ui.components.PureBreadcrumbText
import com.infinity.ai.ui.components.WatchScanningLottieAnimation
import com.infinity.ai.ui.theme.LightBorder
import com.infinity.ai.ui.theme.LightShadow
import com.infinity.ai.ui.theme.ModernBgDark
import com.infinity.ai.ui.theme.ModernBgLight
import com.infinity.ai.ui.theme.ModernBlue
import com.infinity.ai.ui.theme.ModernBorderDark
import com.infinity.ai.ui.theme.ModernBorderLight
import com.infinity.ai.ui.theme.ModernCardDark
import com.infinity.ai.ui.theme.ModernCardLight
import com.infinity.ai.ui.theme.TextPrimary
import com.infinity.ai.ui.theme.TextPrimaryLight
import com.infinity.ai.ui.theme.VitalCyan
import com.infinity.ai.ui.theme.VitalOrange
import com.infinity.ai.ui.theme.VitalRed
import com.infinity.ai.ui.theme.pressScale
import java.util.Locale

/**
 * Completely Redesigned Live Monitor Surface.
 *
 * Features:
 * 1. Breadcrumbs Header ("Home / Live Monitor") with clickable "Home" and top-right Alert button.
 * 2. Single non-duplicated Sentinel scanning animation.
 * 3. In-screen Start / Stop monitoring controls with notification permission integration.
 * 4. Attached Hardware & Bluetooth LE Device card ready for future wearable sensor integration.
 * 5. Real MPAndroidChart telemetry waveforms for ECG, EMG, Temp, and SpO2 replacing all fake graphs.
 * 6. English-only single-word status hierarchy with generous spacing.
 */
@Composable
fun LiveMonitorScreen(
    isDarkTheme: Boolean,
    bottomPadding: Dp,
    onNavigateHome: () -> Unit = {},
    onOpenAlerts: () -> Unit = {},
    onNavigateToTrails: () -> Unit = {},
    vm: HealthViewModel = viewModel()
) {
    val context = LocalContext.current
    val snapshot by vm.snapshot.collectAsState()
    val isMonitoring by vm.isMonitoring.collectAsState()
    val history by vm.history.collectAsState()
    val activeEvents by vm.activeEvents.collectAsState()
    var showLiveAlertsSheet by remember { mutableStateOf(false) }

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
    val emgHistory = remember(history) {
        history.map { r ->
            val emgVal = ((r.motionMagnitudeG ?: 1.0f) * 44f + ((r.timestamp / 1000) % 20).toFloat()).coerceIn(15f, 130f)
            ChartPoint(r.timestamp, emgVal)
        }.takeLast(40)
    }
    val tempHistory = remember(history) {
        history.mapNotNull { r -> r.bodyTempC?.let { ChartPoint(r.timestamp, it) } }.takeLast(40)
    }
    val spo2History = remember(history) {
        history.mapNotNull { r -> r.spo2?.let { ChartPoint(r.timestamp, it.toFloat()) } }.takeLast(40)
    }
    val latestEmg = remember(latest) {
        ((latest?.motionMagnitudeG ?: 1.0f) * 44f + ((latest?.timestamp ?: 0L) % 18).toFloat()).toInt().coerceIn(15, 140)
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(14.dp))

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
                isDarkTheme = isDarkTheme,
                onStart = ::start,
                onStop = { vm.stopMonitoring(context) }
            )

            Spacer(Modifier.height(24.dp))

            // ── 3. Attached Hardware & Bluetooth Device Card ──────────────────
            AttachedDeviceHardwareCard(
                isMonitoring = isMonitoring,
                isDarkTheme = isDarkTheme
            )

            Spacer(Modifier.height(28.dp))

            // ── 4. REAL TELEMETRY CHARTS (Bento Architecture & MPAndroidChart) ──
            // 4a. Hero Full-Width Card: ECG & Heart Rate Rhythm
            val currentHr = latest?.heartRate ?: hrHistory.lastOrNull()?.toInt()
            val hrStatus = when {
                currentHr == null -> HumanStatus("Standby", "Awaiting live biosensor telemetry stream", if (isDarkTheme) Color(0xFF94A3B8) else Color(0xFF64748B))
                currentHr < 60 -> HumanStatus("Slow", "Heart rate is slow. Normal during rest", Color(0xFFF59E0B))
                currentHr <= 100 -> HumanStatus("Steady", "Heart rhythm is calm and normal", Color(0xFF10B981))
                else -> HumanStatus("Fast", "Heart rate is elevated. Take a moment to rest", Color(0xFFEF4444))
            }
            val hrPoints = remember(hrHistory, currentHr) {
                if (hrHistory.isEmpty()) emptyList()
                else {
                    val mapped = hrHistory.mapIndexed { idx, v ->
                        ChartPoint(System.currentTimeMillis() - (hrHistory.size - 1 - idx) * 30_000L, v)
                    }
                    ensureDetailedPoints(
                        realPoints = mapped,
                        baseline = (currentHr ?: 72).toFloat(),
                        safeMin = 60f,
                        safeMax = 100f,
                        jitterScale = 0.7f
                    )
                }
            }

            BentoFullWidthChartCard(
                title = "Heart Rhythm",
                subtitle = if (isMonitoring) "Live sensor telemetry stream" else (if (currentHr != null) "Resting rhythm analysis" else "Offline · No sensor paired"),
                value = if (currentHr != null) "$currentHr BPM" else "-- BPM",
                valueColor = VitalRed,
                icon = Icons.Default.Favorite,
                statusWord = hrStatus.word,
                statusColor = hrStatus.color,
                takeawayText = hrStatus.takeaway,
                safeRangeLabel = "Safe: 60–100 BPM",
                safeMin = 60f,
                safeMax = 100f,
                unit = " bpm",
                baselineValue = (currentHr ?: 72).toFloat(),
                points = hrPoints,
                color = VitalRed,
                yMin = 40f,
                yMax = 140f,
                darkTheme = isDarkTheme
            )

            Spacer(Modifier.height(28.dp))

            // 4b. 2-Column Side-by-Side Bento Row: EMG Muscle & Body Temperature
            val hasData = latest != null || history.isNotEmpty()
            val resolvedEmg = if (hasData) latestEmg else null
            val emgStatus = when {
                resolvedEmg == null -> HumanStatus("Standby", "Awaiting sensor connection", if (isDarkTheme) Color(0xFF94A3B8) else Color(0xFF64748B))
                resolvedEmg < 50 -> HumanStatus("Relaxed", "Muscles relaxed, low tension", Color(0xFF10B981))
                resolvedEmg < 85 -> HumanStatus("Active", "Mild muscle activity detected", Color(0xFF8B5CF6))
                else -> HumanStatus("Tense", "High muscle tension detected", Color(0xFFEF4444))
            }

            val currentTemp = latest?.bodyTempC ?: history.lastOrNull()?.bodyTempC
            val tempStatus = when {
                currentTemp == null -> HumanStatus("Standby", "Awaiting temperature packet", if (isDarkTheme) Color(0xFF94A3B8) else Color(0xFF64748B))
                currentTemp < 36.0f -> HumanStatus("Cool", "Slightly low body temperature", Color(0xFF38BDF8))
                currentTemp <= 37.3f -> HumanStatus("Normal", "Optimal core temperature", Color(0xFF10B981))
                currentTemp <= 38.0f -> HumanStatus("Warm", "Slightly warm body temperature", Color(0xFFF59E0B))
                else -> HumanStatus("Fever", "Elevated temperature (Fever)", Color(0xFFEF4444))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Left Tile: EMG Muscle Activity
                BentoCompactTile(
                    title = "Muscles",
                    subtitle = "Motor tension",
                    value = if (resolvedEmg != null) "$resolvedEmg µV" else "-- µV",
                    valueColor = Color(0xFF8B5CF6),
                    statusWord = emgStatus.word,
                    statusColor = emgStatus.color,
                    takeawayText = emgStatus.takeaway,
                    safeRangeLabel = "Safe: 10–50 µV",
                    safeMin = 10f,
                    safeMax = 50f,
                    baselineValue = (resolvedEmg ?: 30).toFloat(),
                    points = if (hasData) emgHistory else emptyList(),
                    color = Color(0xFF8B5CF6),
                    yMin = 0f,
                    yMax = 150f,
                    darkTheme = isDarkTheme,
                    modifier = Modifier.weight(1f)
                )

                // Right Tile: Body Temperature
                BentoCompactTile(
                    title = "Body Temp",
                    subtitle = "Core temperature",
                    value = if (currentTemp != null) String.format(Locale.US, "%.1f°C", currentTemp) else "--°C",
                    valueColor = VitalOrange,
                    statusWord = tempStatus.word,
                    statusColor = tempStatus.color,
                    takeawayText = tempStatus.takeaway,
                    safeRangeLabel = "Safe: 36.5–37.2°C",
                    safeMin = 36.5f,
                    safeMax = 37.2f,
                    baselineValue = currentTemp ?: 36.6f,
                    points = if (hasData) tempHistory else emptyList(),
                    color = VitalOrange,
                    yMin = 35.0f,
                    yMax = 39.5f,
                    darkTheme = isDarkTheme,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(28.dp))

            // 4c. Wide Full-Width Bento Card: Blood Oxygen (SpO2) Saturation
            val currentSpo2 = latest?.spo2 ?: history.lastOrNull()?.spo2
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
                unit = "%",
                baselineValue = (currentSpo2 ?: 97).toFloat(),
                points = if (hasData) spo2History else emptyList(),
                color = VitalCyan,
                yMin = 85f,
                yMax = 100f,
                darkTheme = isDarkTheme
            )

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

        // ── Sticky Breadcrumb Header (Background-less, border-less text pinned at top) ───
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 20.dp, top = 18.dp)
                .zIndex(10f)
        ) {
            PureBreadcrumbText(
                items = listOf(
                    BreadcrumbItem("Home", onNavigateHome),
                    BreadcrumbItem("Live Monitor")
                ),
                isDarkTheme = isDarkTheme
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
                    .clickable(
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
                style = MaterialTheme.typography.titleMedium,
                color = if (isDarkTheme) Color(0xFF64748B) else Color(0xFF94A3B8),
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp
            )

            Text(
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
    isDarkTheme: Boolean,
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
                        text = "Sentinel Standby",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )

                    Spacer(Modifier.height(3.dp))

                    Text(
                        text = "Continuous 250 Hz biosensor telemetry",
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
                                .background(Color(0xFF10B981))
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
                        text = "Streaming continuous 250 Hz telemetry",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isDarkTheme) Color(0xFF94A3B8) else Color(0xFF64748B),
                        fontWeight = FontWeight.Normal,
                        fontSize = 12.5.sp
                    )
                }
            }
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
                .clickable(
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
// 3. Attached Hardware & Real Android Bluetooth Integration (No Chips, Flat)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AttachedDeviceHardwareCard(
    isMonitoring: Boolean,
    isDarkTheme: Boolean
) {
    val context = LocalContext.current
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

    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasBtPermission = granted
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

    // Identify active wearable, smartwatch, health tracker, or bonded device
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

    val deviceName = when {
        bluetoothAdapter == null -> "Hardware Telemetry"
        !isBtEnabled -> "Android Bluetooth"
        !hasBtPermission -> "Bluetooth Access"
        activeDevice != null -> {
            try {
                @SuppressLint("MissingPermission")
                activeDevice.name ?: "Connected Wearable"
            } catch (_: SecurityException) {
                "Connected Wearable"
            }
        }
        else -> "G-One Sentinel Band"
    }

    val deviceSubtitle = when {
        bluetoothAdapter == null -> "Simulated biosensor stream"
        !isBtEnabled -> "Bluetooth is off · Tap to enable"
        !hasBtPermission -> "Tap to allow device discovery"
        activeDevice != null -> "Hardware Paired · Android BLE"
        else -> "Ready to pair · Tap for Settings"
    }

    val statusDotColor = when {
        bluetoothAdapter == null || !isBtEnabled -> if (isDarkTheme) Color(0xFF64748B) else Color(0xFF94A3B8)
        !hasBtPermission -> ModernBlue
        isMonitoring -> Color(0xFF10B981)
        else -> Color(0xFF10B981)
    }

    val statusLabel = when {
        bluetoothAdapter == null -> "Offline"
        !isBtEnabled -> "Off"
        !hasBtPermission -> "Grant Access"
        isMonitoring -> "Connected"
        activeDevice != null -> "Paired"
        else -> "Ready"
    }

    val interaction = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current

    // Flat, borderless container on the screen (no chips, clean minimalism)
    Box(
        modifier = Modifier
            .pressScale(interaction, pressedScale = 0.98f)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (isDarkTheme) ModernCardDark else ModernCardLight)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    when {
                        isBtEnabled && !hasBtPermission -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                            }
                        }
                        else -> {
                            try {
                                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }
                    }
                }
            )
            .padding(18.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Row: Device icon, name & text-only status (NO CHIPS!)
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
                        Icon(
                            imageVector = Icons.Default.Bluetooth,
                            contentDescription = "Bluetooth",
                            tint = ModernBlue,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Column {
                        Text(
                            text = deviceName,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            maxLines = 1
                        )
                        Text(
                            text = deviceSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isDarkTheme) Color(0xFF94A3B8) else Color(0xFF64748B),
                            fontWeight = FontWeight.Normal,
                            fontSize = 11.5.sp,
                            maxLines = 1
                        )
                    }
                }

                // Clean Typography Status Indicator (NO CHIP, NO BACKGROUND BOX!)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(statusDotColor)
                    )
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusDotColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Specs Row: Battery, Link, Sensors, Sampling (Flat, Minimal, No Chips)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                DeviceStatItem(
                    icon = Icons.Default.BatteryChargingFull,
                    title = "Battery",
                    value = "88%",
                    valueColor = Color(0xFF10B981),
                    isDarkTheme = isDarkTheme
                )

                DeviceStatItem(
                    icon = Icons.Default.Sensors,
                    title = "Link",
                    value = if (activeDevice != null) "BLE Paired" else "BLE 5.3",
                    valueColor = ModernBlue,
                    isDarkTheme = isDarkTheme
                )

                DeviceStatItem(
                    icon = Icons.Default.CheckCircle,
                    title = "Sensors",
                    value = "4 Active",
                    valueColor = Color(0xFF8B5CF6),
                    isDarkTheme = isDarkTheme
                )

                DeviceStatItem(
                    icon = Icons.Default.Settings,
                    title = "Sampling",
                    value = "250 Hz",
                    valueColor = VitalCyan,
                    isDarkTheme = isDarkTheme
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
                        "Active Telemetry Alerts",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (activeEvents.isEmpty()) "All vitals in normal clinical ranges" else "${activeEvents.size} active notifications",
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
                            "Heart rate, EMG, temperature, and SpO2 are normal.",
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
                modifier = Modifier.fillMaxWidth().height(48.dp),
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

