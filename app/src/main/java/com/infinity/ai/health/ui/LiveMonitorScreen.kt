package com.infinity.ai.health.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.infinity.ai.health.domain.Severity
import com.infinity.ai.ui.components.AiBodyOrb
import com.infinity.ai.ui.components.GradientBackground
import com.infinity.ai.ui.theme.GoneCard
import com.infinity.ai.ui.theme.goneSurface
import com.infinity.ai.ui.theme.DarkBorder
import com.infinity.ai.ui.theme.DarkSurface
import com.infinity.ai.ui.theme.LightBorder
import com.infinity.ai.ui.theme.LightSurface
import com.infinity.ai.ui.theme.TextPrimary
import com.infinity.ai.ui.theme.TextPrimaryLight
import kotlinx.coroutines.delay

/**
 * Live monitoring surface.
 *
 * Larger cards than the dashboard grid, each with a live signal strip. This is the screen
 * someone watches while worried, so it favours legibility and a visible sense of "the
 * device is working right now" over information density.
 */
@Composable
fun LiveMonitorScreen(
    isDarkTheme: Boolean,
    bottomPadding: Dp,
    vm: HealthViewModel = viewModel()
) {
    val snapshot by vm.snapshot.collectAsState()
    val isMonitoring by vm.isMonitoring.collectAsState()
    val latest = snapshot.latest
    val live = snapshot.state.isActive

    // Ticks once a second purely so "Last reading 3s ago" stays honest. Without it the
    // label would freeze at whatever it said when the last sample arrived.
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(live) {
        while (live) {
            nowTick = System.currentTimeMillis()
            delay(1_000)
        }
    }

    GradientBackground(darkTheme = isDarkTheme, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Live monitor",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                MonitoringBadge(
                    label = monitoringStateLabel(snapshot.state),
                    color = monitoringStateColor(snapshot.state, isDarkTheme),
                    live = live,
                    darkTheme = isDarkTheme
                )
            }

            Spacer(Modifier.height(16.dp))

            // The orb reflects the pipeline, not the AI engine — see orbStateFor.
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AiBodyOrb(
                    orbState = orbStateFor(snapshot.state),
                    isDarkTheme = isDarkTheme,
                    size = 150.dp
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = monitoringStateLabel(snapshot.state),
                style = MaterialTheme.typography.bodyMedium,
                color = monitoringStateColor(snapshot.state, isDarkTheme),
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(Modifier.height(20.dp))

            if (!isMonitoring && latest == null) {
                HealthEmptyState(
                    title = "Not monitoring",
                    body = "Start monitoring from the Health tab and readings will appear here.",
                    darkTheme = isDarkTheme,
                    modifier = Modifier.height(240.dp)
                )
            } else {
                MonitorCard(
                    label = "Heart rate",
                    unit = "bpm",
                    severity = vm.heartRateSeverity(latest?.heartRate),
                    live = live,
                    darkTheme = isDarkTheme,
                    updatedAt = latest?.timestamp,
                    now = nowTick
                ) {
                    AnimatedVitalNumber(
                        value = latest?.heartRate,
                        style = HealthType.vitalHero,
                        color = if (isDarkTheme) TextPrimary else TextPrimaryLight
                    )
                }

                Spacer(Modifier.height(12.dp))

                MonitorCard(
                    label = "Blood oxygen",
                    unit = "%",
                    severity = vm.spo2Severity(latest?.spo2),
                    live = live,
                    darkTheme = isDarkTheme,
                    updatedAt = latest?.timestamp,
                    now = nowTick
                ) {
                    AnimatedVitalNumber(
                        value = latest?.spo2,
                        style = HealthType.vitalHero,
                        color = if (isDarkTheme) TextPrimary else TextPrimaryLight
                    )
                }

                Spacer(Modifier.height(12.dp))

                MonitorCard(
                    label = "Body temperature",
                    unit = "°C",
                    severity = vm.temperatureSeverity(latest?.bodyTempC),
                    live = live,
                    darkTheme = isDarkTheme,
                    updatedAt = latest?.timestamp,
                    now = nowTick
                ) {
                    AnimatedVitalDecimal(
                        value = latest?.bodyTempC,
                        style = HealthType.vitalHero,
                        color = if (isDarkTheme) TextPrimary else TextPrimaryLight
                    )
                }

                Spacer(Modifier.height(20.dp))

                // ── Risk ──────────────────────────────────────────────────────
                // Linear meters here, radial gauges on the dashboard. Same numbers, but
                // this screen is a vertical stack of full-width cards, and thin bars sit
                // in that rhythm better than three arcs would.
                Text(
                    "RISK",
                    style = HealthType.sectionLabel,
                    color = secondaryTextColor(isDarkTheme)
                )
                Spacer(Modifier.height(10.dp))
                GoneCard(darkTheme = isDarkTheme, verticalSpacing = 12.dp) {
                    RiskMeter("Heat stress", snapshot.risk.heat, isDarkTheme)
                    RiskMeter("Respiratory", snapshot.risk.respiratory, isDarkTheme)
                    RiskMeter("Cardiovascular", snapshot.risk.cardiovascular, isDarkTheme)
                }

                Spacer(Modifier.height(20.dp))

                // ── Environment ───────────────────────────────────────────────
                Text(
                    "ENVIRONMENT",
                    style = HealthType.sectionLabel,
                    color = secondaryTextColor(isDarkTheme)
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    EnvironmentCell(
                        "Air temp",
                        latest?.ambientTempC?.let { String.format(java.util.Locale.US, "%.0f°C", it) },
                        isDarkTheme,
                        Modifier.weight(1f)
                    )
                    EnvironmentCell(
                        "Humidity",
                        latest?.ambientHumidityPct?.let { String.format(java.util.Locale.US, "%.0f%%", it) },
                        isDarkTheme,
                        Modifier.weight(1f)
                    )
                    EnvironmentCell(
                        "AQI",
                        latest?.aqi?.toString(),
                        isDarkTheme,
                        Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(bottomPadding + 28.dp))
        }
    }
}

/**
 * One vital, full width, with a live strip.
 *
 * Warning and critical states get a glow rather than only a border change, because glow
 * reads as "actively significant" in peripheral vision — which is how someone glancing at
 * the screen actually consumes it.
 */
@Composable
private fun MonitorCard(
    label: String,
    unit: String,
    severity: Severity?,
    live: Boolean,
    darkTheme: Boolean,
    updatedAt: Long?,
    now: Long,
    content: @Composable () -> Unit
) {
    val accent = severity?.let { severityColor(it, darkTheme) } ?: healthColors(darkTheme).Normal
    val warn = severity != null && severity != Severity.LOW

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Glow before surface: goneSurface clips, and severityGlow draws outside its
            // own bounds.
            .severityGlow(accent, active = warn, corner = 20.dp)
            .then(goneSurface(darkTheme, corner = 20.dp))
    ) {
        SeverityAccent(color = accent, width = 4.dp, corner = 20.dp)
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = secondaryTextColor(darkTheme),
                    letterSpacing = 1.2.sp,
                    modifier = Modifier.weight(1f)
                )
                if (severity != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(accent.copy(alpha = 0.15f))
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            severity.wireName,
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.Bottom) {
                content()
                Spacer(Modifier.width(4.dp))
                Text(
                    unit,
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryTextColor(darkTheme),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            Spacer(Modifier.height(10.dp))

            LiveWaveform(
                active = live,
                color = accent,
                modifier = Modifier.fillMaxWidth().height(20.dp)
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = updatedAt?.let { "Last reading ${relativeSeconds(it, now)}" }
                    ?: "Waiting for first reading",
                style = HealthType.monoSmall,
                color = secondaryTextColor(darkTheme)
            )
        }
    }
}

@Composable
private fun EnvironmentCell(
    label: String,
    value: String?,
    darkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (darkTheme) DarkSurface else LightSurface)
            .border(1.dp, if (darkTheme) DarkBorder else LightBorder, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = secondaryTextColor(darkTheme),
            letterSpacing = 0.8.sp
        )
        Text(
            value ?: "––",
            style = HealthType.vitalMedium,
            color = if (darkTheme) TextPrimary else TextPrimaryLight
        )
    }
}

/** "3s ago" / "2m ago" / "1h ago" — short enough not to wrap. */
internal fun relativeSeconds(then: Long, now: Long): String {
    val secs = ((now - then) / 1000).coerceAtLeast(0)
    return when {
        secs < 60 -> "${secs}s ago"
        secs < 3600 -> "${secs / 60}m ago"
        else -> "${secs / 3600}h ago"
    }
}

/**
 * Pipeline state → orb animation.
 *
 * IMPORTANT: `ERROR` maps to the orb's `Error` state and `ANOMALY_DETECTED` maps to
 * `Responding`, keeping a *system fault* visually distinct from a *health emergency*. The
 * orb's error state has an unstable flicker quality; a detected anomaly gets urgent but
 * steady motion. Conflating the two would leave the user unable to tell "the app broke"
 * from "you are in danger", which is the single most important distinction this screen
 * has to make.
 */
internal fun orbStateFor(
    state: com.infinity.ai.health.service.MonitoringState
): com.infinity.ai.ui.components.OrbState = when (state) {
    com.infinity.ai.health.service.MonitoringState.IDLE ->
        com.infinity.ai.ui.components.OrbState.Idle
    com.infinity.ai.health.service.MonitoringState.MONITORING ->
        com.infinity.ai.ui.components.OrbState.Idle
    com.infinity.ai.health.service.MonitoringState.READING ->
        com.infinity.ai.ui.components.OrbState.Loading
    com.infinity.ai.health.service.MonitoringState.ANALYZING ->
        com.infinity.ai.ui.components.OrbState.Thinking
    com.infinity.ai.health.service.MonitoringState.ANOMALY_DETECTED ->
        com.infinity.ai.ui.components.OrbState.Responding
    com.infinity.ai.health.service.MonitoringState.AI_EXPLAINING ->
        com.infinity.ai.ui.components.OrbState.Responding
    com.infinity.ai.health.service.MonitoringState.ERROR ->
        com.infinity.ai.ui.components.OrbState.Error
}

