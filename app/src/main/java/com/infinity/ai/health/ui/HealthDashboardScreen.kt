package com.infinity.ai.health.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.infinity.ai.health.data.AnomalyEventEntity
import com.infinity.ai.health.data.EventStatus
import com.infinity.ai.health.data.severityEnum
import com.infinity.ai.health.data.statusEnum
import com.infinity.ai.health.source.VitalsScenario
import com.infinity.ai.ui.components.GradientBackground
import com.infinity.ai.ui.theme.GoneCard
import com.infinity.ai.ui.theme.GoneElevation
import com.infinity.ai.ui.theme.GoneRadius
import com.infinity.ai.ui.theme.StaggeredEntrance
import com.infinity.ai.ui.theme.DarkBorder
import com.infinity.ai.ui.theme.DarkSurface
import com.infinity.ai.ui.theme.LightBorder
import com.infinity.ai.ui.theme.LightSurface
import com.infinity.ai.ui.theme.TextPrimary
import com.infinity.ai.ui.theme.TextPrimaryLight
import com.infinity.ai.ui.theme.goneSurface
import com.infinity.ai.ui.theme.pressScale
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The health home screen.
 *
 * STRUCTURE, AND WHY IT CHANGED: the monitoring control used to sit at the very bottom of a
 * four-screen scroll. Start/stop monitoring is the single most important action in the app,
 * and it was the hardest thing on the page to reach. It now lives in the hero, above the
 * fold — and because it must stay reachable once the hero scrolls away, a compact bar
 * carrying the same control slides down and pins to the top as you scroll.
 *
 * So the primary action is available at every scroll position, in exactly one of two
 * places, and the transition between them is the scroll itself.
 *
 * Calm by default. The only element permitted to feel urgent is the active-alert banner.
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
    vm: HealthViewModel = viewModel()
) {
    val context = LocalContext.current
    val isMonitoring by vm.isMonitoring.collectAsState()
    val snapshot by vm.snapshot.collectAsState()
    val activeEvents by vm.activeEvents.collectAsState()
    val allEvents by vm.allEvents.collectAsState()
    val history by vm.history.collectAsState()
    val scenario by vm.scenario.collectAsState()

    // Notifications are how an alert reaches someone who is not looking at the screen —
    // which is most of the time. Requested at the moment monitoring starts, so the
    // prompt has obvious context rather than appearing at launch.
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
    val spo2History = remember(history) { history.mapNotNull { it.spo2?.toFloat() }.takeLast(40) }
    val tempHistory = remember(history) { history.mapNotNull { it.bodyTempC }.takeLast(40) }
    val motionHistory = remember(history) { history.mapNotNull { it.motionMagnitudeG }.takeLast(40) }

    val stateColor = monitoringStateColor(snapshot.state, isDarkTheme)
    val dominantRisk = maxOf(
        snapshot.risk.heat,
        snapshot.risk.respiratory,
        snapshot.risk.cardiovascular
    )

    // ── Scroll-driven collapse ────────────────────────────────────────────────
    val scrollState = rememberScrollState()
    val collapseDistancePx = with(LocalDensity.current) { HeroCollapseDistance.toPx() }
    val collapse = (scrollState.value / collapseDistancePx).coerceIn(0f, 1f)

    GradientBackground(darkTheme = isDarkTheme, modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 18.dp)
            ) {
                Spacer(Modifier.height(12.dp))

                TopBar(
                    darkTheme = isDarkTheme,
                    onOpenTools = onOpenTools,
                    onOpenSettings = onOpenSettings
                )

                Spacer(Modifier.height(10.dp))

                // ── Hero ──────────────────────────────────────────────────────
                // Fades and shrinks with scroll rather than changing its layout height.
                // Animating the height would reflow everything below it on every scroll
                // frame; fading a fixed-size block scrolls away just as convincingly and
                // never jitters.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            alpha = 1f - collapse
                            val s = 1f - 0.12f * collapse
                            scaleX = s
                            scaleY = s
                        },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    StatusRing(
                        heartRate = latest?.heartRate,
                        dominantRisk = dominantRisk,
                        stateColor = stateColor,
                        stateLabel = monitoringStateLabel(snapshot.state),
                        active = snapshot.state.isActive,
                        history = hrHistory,
                        darkTheme = isDarkTheme
                    )

                    Spacer(Modifier.height(16.dp))

                    MonitoringButton(
                        running = isMonitoring,
                        darkTheme = isDarkTheme,
                        onStart = ::start,
                        onStop = { vm.stopMonitoring(context) }
                    )

                    if (isMonitoring) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            StatText("Samples", "${snapshot.samplesProcessed}", isDarkTheme)
                            StatText("Events", "${snapshot.eventsRaised}", isDarkTheme)
                            StatText(
                                "Baseline",
                                if (snapshot.baselineReady) "ready" else "learning",
                                isDarkTheme
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // ── Active alert banner ───────────────────────────────────────
                AnimatedVisibility(
                    visible = activeEvents.isNotEmpty(),
                    enter = slideInVertically(
                        spring(dampingRatio = Spring.DampingRatioMediumBouncy)
                    ) { -it } + fadeIn(),
                    exit = slideOutVertically { -it } + fadeOut()
                ) {
                    activeEvents.firstOrNull()?.let { event ->
                        ActiveAlertBanner(
                            event = event,
                            extraCount = activeEvents.size - 1,
                            darkTheme = isDarkTheme,
                            onClick = onOpenAlerts
                        )
                    }
                }
                if (activeEvents.isNotEmpty()) Spacer(Modifier.height(16.dp))

                // ── Quick actions ─────────────────────────────────────────────
                StaggeredEntrance(0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        QuickAction(Icons.Default.MonitorHeart, "Live", isDarkTheme, onOpenMonitor)
                        QuickAction(Icons.AutoMirrored.Filled.ShowChart, "History", isDarkTheme, onOpenHistory)
                        QuickAction(
                            Icons.Default.NotificationsActive,
                            if (activeEvents.isEmpty()) "Alerts" else "Alerts (${activeEvents.size})",
                            isDarkTheme,
                            onOpenAlerts
                        )
                        QuickAction(Icons.Default.Apps, "Tools", isDarkTheme, onOpenTools)
                    }
                }

                Spacer(Modifier.height(22.dp))

                // ── Vitals ────────────────────────────────────────────────────
                SectionHeader(
                    title = "Vitals",
                    darkTheme = isDarkTheme,
                    actionLabel = "Live",
                    onAction = onOpenMonitor
                )
                Spacer(Modifier.height(10.dp))

                StaggeredEntrance(1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        VitalTile(
                            label = "Heart rate",
                            unit = "bpm",
                            severity = vm.heartRateSeverity(latest?.heartRate),
                            darkTheme = isDarkTheme,
                            history = hrHistory,
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.MonitorHeart,
                            onClick = onOpenMonitor
                        ) {
                            AnimatedVitalNumber(
                                value = latest?.heartRate,
                                color = if (isDarkTheme) TextPrimary else TextPrimaryLight
                            )
                        }
                        VitalTile(
                            label = "Blood oxygen",
                            unit = "%",
                            severity = vm.spo2Severity(latest?.spo2),
                            darkTheme = isDarkTheme,
                            history = spo2History,
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.Air,
                            onClick = onOpenMonitor
                        ) {
                            AnimatedVitalNumber(
                                value = latest?.spo2,
                                color = if (isDarkTheme) TextPrimary else TextPrimaryLight
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                StaggeredEntrance(2) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        VitalTile(
                            label = "Temperature",
                            unit = "°C",
                            severity = vm.temperatureSeverity(latest?.bodyTempC),
                            darkTheme = isDarkTheme,
                            history = tempHistory,
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.Thermostat,
                            onClick = onOpenMonitor
                        ) {
                            AnimatedVitalDecimal(
                                value = latest?.bodyTempC,
                                color = if (isDarkTheme) TextPrimary else TextPrimaryLight
                            )
                        }
                        VitalTile(
                            label = "Movement",
                            unit = "g",
                            severity = vm.motionSeverity(latest?.motionMagnitudeG),
                            darkTheme = isDarkTheme,
                            history = motionHistory,
                            modifier = Modifier.weight(1f),
                            icon = Icons.AutoMirrored.Filled.DirectionsRun,
                            onClick = onOpenMonitor
                        ) {
                            AnimatedVitalDecimal(
                                value = latest?.motionMagnitudeG,
                                color = if (isDarkTheme) TextPrimary else TextPrimaryLight
                            )
                        }
                    }
                }

                Spacer(Modifier.height(22.dp))

                // ── Risk ──────────────────────────────────────────────────────
                SectionHeader(
                    title = "Risk",
                    darkTheme = isDarkTheme,
                    subtitle = "These move before any threshold is crossed."
                )
                Spacer(Modifier.height(12.dp))

                StaggeredEntrance(3) {
                    GoneCard(darkTheme = isDarkTheme, contentPadding = 16.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            RadialGauge("Heat", snapshot.risk.heat, isDarkTheme, size = 92.dp)
                            RadialGauge("Respiratory", snapshot.risk.respiratory, isDarkTheme, size = 92.dp)
                            RadialGauge("Cardiac", snapshot.risk.cardiovascular, isDarkTheme, size = 92.dp)
                        }
                    }
                }

                Spacer(Modifier.height(22.dp))

                // ── Recent activity ───────────────────────────────────────────
                SectionHeader(
                    title = "Recent activity",
                    darkTheme = isDarkTheme,
                    actionLabel = if (allEvents.isNotEmpty()) "See all" else null,
                    onAction = if (allEvents.isNotEmpty()) onOpenAlerts else null
                )
                Spacer(Modifier.height(10.dp))

                StaggeredEntrance(4) {
                    GoneCard(darkTheme = isDarkTheme, contentPadding = 4.dp) {
                        if (allEvents.isEmpty()) {
                            Text(
                                text = "Nothing detected yet. This is the good outcome.",
                                style = MaterialTheme.typography.bodySmall,
                                color = secondaryTextColor(isDarkTheme),
                                modifier = Modifier.padding(12.dp)
                            )
                        } else {
                            allEvents.take(3).forEach { event ->
                                ActivityRow(event, isDarkTheme, onOpenAlerts)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(22.dp))

                // ── Data source ───────────────────────────────────────────────
                // The scenario picker lives down here rather than in the hero. It is a
                // setup choice made once, not something reached for repeatedly, and it
                // only appears while stopped because switching scenario mid-run would
                // silently invalidate the baseline the detector has been learning.
                if (!isMonitoring) {
                    SectionHeader(
                        title = "Data source",
                        darkTheme = isDarkTheme,
                        subtitle = "No wearable is paired, so G-one replays a physiological " +
                            "scenario through the real detection engine. Everything " +
                            "downstream is genuine output."
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        VitalsScenario.entries.forEach { s ->
                            ScenarioChip(
                                scenario = s,
                                selected = s == scenario,
                                darkTheme = isDarkTheme,
                                onClick = { vm.setScenario(s) }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(bottomPadding + 28.dp))
            }

            // ── Sticky monitoring bar ─────────────────────────────────────────
            // Drawn last so it sits above the scrolling content. Appears once the hero is
            // mostly gone, which is the point where the button below would otherwise be
            // off-screen with no replacement.
            AnimatedVisibility(
                visible = collapse > 0.55f,
                enter = slideInVertically { -it } + fadeIn(tween(180)),
                exit = slideOutVertically { -it } + fadeOut(tween(140)),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                StickyMonitorBar(
                    running = isMonitoring,
                    stateColor = stateColor,
                    stateLabel = monitoringStateLabel(snapshot.state),
                    heartRate = latest?.heartRate,
                    active = snapshot.state.isActive,
                    darkTheme = isDarkTheme,
                    onStart = ::start,
                    onStop = { vm.stopMonitoring(context) }
                )
            }
        }
    }
}

/** How far the user scrolls before the hero is fully collapsed. */
private val HeroCollapseDistance = 190.dp

// ─────────────────────────────────────────────────────────────────────────────
// Top bar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TopBar(
    darkTheme: Boolean,
    onOpenTools: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val greeting = remember {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 0..4 -> "Good night"
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            else -> "Good evening"
        }
    }
    val today = remember {
        SimpleDateFormat("EEEE, d MMM", Locale.getDefault()).format(Date())
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = greeting,
                style = MaterialTheme.typography.titleLarge,
                color = if (darkTheme) TextPrimary else TextPrimaryLight,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = today,
                style = MaterialTheme.typography.bodySmall,
                color = secondaryTextColor(darkTheme)
            )
        }
        HeaderIcon(Icons.Default.Apps, "Tools", darkTheme, onOpenTools)
        Spacer(Modifier.width(8.dp))
        HeaderIcon(Icons.Default.Settings, "Settings", darkTheme, onOpenSettings)
    }
}

@Composable
private fun HeaderIcon(
    icon: ImageVector,
    description: String,
    darkTheme: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .pressScale(interaction)
            .size(38.dp)
            .then(goneSurface(darkTheme, corner = 19.dp, elevation = GoneElevation.Card))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            description,
            tint = secondaryTextColor(darkTheme),
            modifier = Modifier.size(17.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sticky monitoring bar
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The collapsed form of the hero.
 *
 * Carries exactly what the hero carried and nothing more: whether monitoring is on, the
 * live heart rate, and the control to change it. Adding the risk numbers here was
 * tempting and wrong — a pinned bar competes with the content underneath it, so it has to
 * earn its space with the minimum.
 */
@Composable
private fun StickyMonitorBar(
    running: Boolean,
    stateColor: Color,
    stateLabel: String,
    heartRate: Int?,
    active: Boolean,
    darkTheme: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val accent by animateColorAsState(stateColor, tween(400), label = "barAccent")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(goneSurface(darkTheme, corner = GoneRadius.Pill, elevation = GoneElevation.Raised))
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PulseRing(
                color = accent,
                active = active,
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stateLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (heartRate != null) "$heartRate bpm" else "no reading",
                    style = HealthType.monoSmall,
                    color = secondaryTextColor(darkTheme)
                )
            }

            CompactRunButton(
                running = running,
                darkTheme = darkTheme,
                onStart = onStart,
                onStop = onStop
            )
        }
    }
}

@Composable
private fun CompactRunButton(
    running: Boolean,
    darkTheme: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val palette = healthColors(darkTheme)
    val bg = if (running) palette.SystemFault else palette.Connected
    val interaction = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .pressScale(interaction)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(interactionSource = interaction, indication = null) {
                if (running) onStop() else onStart()
            }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(
            imageVector = if (running) Icons.Default.Stop else Icons.Default.PlayArrow,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = if (running) "Stop" else "Start",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Quick actions
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun QuickAction(
    icon: ImageVector,
    label: String,
    darkTheme: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .pressScale(interaction)
            .then(goneSurface(darkTheme, corner = GoneRadius.Pill, elevation = GoneElevation.Card))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Icon(
            icon,
            null,
            tint = healthColors(darkTheme).Connected,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (darkTheme) TextPrimary else TextPrimaryLight,
            fontWeight = FontWeight.Medium
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Recent activity
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ActivityRow(
    event: AnomalyEventEntity,
    darkTheme: Boolean,
    onClick: () -> Unit
) {
    val color = severityColor(event.severityEnum(), darkTheme)
    val acknowledged = event.statusEnum() == EventStatus.ACKNOWLEDGED
    val fmt = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GoneRadius.Small))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(color.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Warning, null, tint = color, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.anomalyLabel(),
                style = MaterialTheme.typography.labelLarge,
                color = if (darkTheme) TextPrimary else TextPrimaryLight,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                text = fmt.format(Date(event.createdAt)),
                style = HealthType.monoSmall,
                color = secondaryTextColor(darkTheme)
            )
        }
        if (acknowledged) {
            AnimatedCheck(
                checked = true,
                color = healthColors(darkTheme).Normal,
                modifier = Modifier.size(16.dp)
            )
        } else {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                null,
                tint = secondaryTextColor(darkTheme),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Pieces
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The one urgent element on the screen.
 *
 * Uses the severity colour directly rather than an accent edge, because this is where an
 * unmissable signal is the point — and it is the only place on the dashboard that does.
 */
@Composable
private fun ActiveAlertBanner(
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
            .clip(RoundedCornerShape(GoneRadius.Card))
            .background(
                Brush.horizontalGradient(
                    listOf(color.copy(alpha = 0.20f), color.copy(alpha = 0.08f))
                )
            )
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(GoneRadius.Card))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.size(34.dp).background(color.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Warning, null, tint = color, modifier = Modifier.size(17.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                event.anomalyLabel(),
                style = MaterialTheme.typography.titleSmall,
                color = if (darkTheme) TextPrimary else TextPrimaryLight,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (extraCount > 0) "and $extraCount more needing attention"
                else "Tap to read what this means",
                style = MaterialTheme.typography.bodySmall,
                color = color.copy(alpha = 0.85f)
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward, null,
            tint = color, modifier = Modifier.size(15.dp)
        )
    }
}

@Composable
private fun ScenarioChip(
    scenario: VitalsScenario,
    selected: Boolean,
    darkTheme: Boolean,
    onClick: () -> Unit
) {
    val palette = healthColors(darkTheme)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (selected) palette.Connected.copy(alpha = 0.14f)
                else if (darkTheme) DarkSurface else LightSurface
            )
            .border(
                1.dp,
                if (selected) palette.Connected.copy(alpha = 0.5f)
                else if (darkTheme) DarkBorder else LightBorder,
                RoundedCornerShape(20.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            scenario.displayName,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) palette.Connected else secondaryTextColor(darkTheme),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

/**
 * Primary run control.
 *
 * Stop is the one place outside a critical alert that uses a warm colour, and it is
 * deliberately the muted system tone rather than alert red.
 */
@Composable
private fun MonitoringButton(
    running: Boolean,
    darkTheme: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val palette = healthColors(darkTheme)
    val interaction = remember { MutableInteractionSource() }

    val brush = if (running) {
        Brush.horizontalGradient(
            listOf(palette.SystemFault, palette.SystemFault.copy(alpha = 0.85f))
        )
    } else {
        Brush.horizontalGradient(listOf(palette.Connected, palette.Analysing))
    }

    Box(
        modifier = Modifier
            .pressScale(interaction)
            .fillMaxWidth()
            .clip(RoundedCornerShape(GoneRadius.Card))
            .background(brush)
            .clickable(interactionSource = interaction, indication = null) {
                if (running) onStop() else onStart()
            }
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (running) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(19.dp)
            )
            Text(
                text = if (running) "Stop monitoring" else "Start monitoring",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun StatText(label: String, value: String, darkTheme: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = HealthType.vitalMedium,
            color = if (darkTheme) TextPrimary else TextPrimaryLight
        )
        Text(
            text = label,
            style = HealthType.monoSmall,
            color = secondaryTextColor(darkTheme)
        )
    }
}
