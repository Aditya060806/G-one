package com.infinity.ai.health.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.infinity.ai.health.data.AnomalyEventEntity
import com.infinity.ai.health.data.EventStatus
import com.infinity.ai.health.data.severityEnum
import com.infinity.ai.health.data.statusEnum
import com.infinity.ai.ui.components.GradientBackground
import com.infinity.ai.ui.theme.GoneRadius
import com.infinity.ai.ui.theme.StaggeredEntrance
import com.infinity.ai.ui.theme.goneSurface
import com.infinity.ai.ui.theme.TextPrimary
import com.infinity.ai.ui.theme.TextPrimaryLight
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The alert history.
 *
 * Cards expand in place rather than navigating, because the point is to read the
 * explanation next to the reading that caused it — pushing a detail screen would separate
 * them and lose the context.
 *
 * Each card shows `displayExplanation`, which prefers the model's rewrite and falls back
 * to the deterministic template. The user is never shown a blank alert, and both texts
 * remain in the database for audit.
 */
@Composable
fun AlertsScreen(
    isDarkTheme: Boolean,
    bottomPadding: Dp,
    vm: HealthViewModel = viewModel()
) {
    val events by vm.allEvents.collectAsState()
    val filter by vm.statusFilter.collectAsState()

    val shown = remember(events, filter) {
        if (filter == null) events else events.filter { it.statusEnum() == filter }
    }
    val activeCount = remember(events) { events.count { it.statusEnum() == EventStatus.ACTIVE } }
    val ackCount = remember(events) { events.count { it.statusEnum() == EventStatus.ACKNOWLEDGED } }

    GradientBackground(darkTheme = isDarkTheme, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(bottom = bottomPadding)
        ) {
            Column(modifier = Modifier.padding(horizontal = 18.dp)) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Alerts",
                            style = MaterialTheme.typography.titleLarge,
                            color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (activeCount > 0) "$activeCount needing attention"
                            else "Nothing needs attention",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (activeCount > 0) healthColors(isDarkTheme).Warning
                            else secondaryTextColor(isDarkTheme)
                        )
                    }
                    if (events.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .background(healthColors(isDarkTheme).Connected.copy(alpha = 0.12f))
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                "${events.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = healthColors(isDarkTheme).Connected,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Tabs rather than the old scrolling chip row. Three fixed choices with
                // live counts fit a segmented control exactly, and the counts answer
                // "is there anything in there?" without switching tab to find out.
                HealthTabRow(
                    tabs = listOf(
                        HealthTab("Active", activeCount),
                        HealthTab("Seen", ackCount),
                        HealthTab("All", events.size)
                    ),
                    selectedIndex = when (filter) {
                        EventStatus.ACTIVE       -> 0
                        EventStatus.ACKNOWLEDGED -> 1
                        null                     -> 2
                    },
                    darkTheme = isDarkTheme,
                    onSelect = { i ->
                        vm.setStatusFilter(
                            when (i) {
                                0 -> EventStatus.ACTIVE
                                1 -> EventStatus.ACKNOWLEDGED
                                else -> null
                            }
                        )
                    }
                )

                Spacer(Modifier.height(14.dp))
            }

            if (shown.isEmpty()) {
                HealthEmptyState(
                    title = if (events.isEmpty()) "No alerts yet" else "Nothing in this filter",
                    body = if (events.isEmpty())
                        "This is the good outcome. Anything the detection engine flags " +
                            "will appear here with an explanation."
                    else "Try a different filter.",
                    darkTheme = isDarkTheme
                )
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 18.dp,
                        vertical = 2.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(shown, key = { _, e -> e.id }) { index, event ->
                        StaggeredEntrance(index = index) {
                            AlertCard(
                                event = event,
                                darkTheme = isDarkTheme,
                                onAcknowledge = { vm.acknowledge(event.id) }
                            )
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}

@Composable
private fun AlertCard(
    event: AnomalyEventEntity,
    darkTheme: Boolean,
    onAcknowledge: () -> Unit
) {
    var expanded by rememberSaveable(event.id) { mutableStateOf(false) }
    var justAcknowledged by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    val severity = event.severityEnum()
    val accent = severityColor(severity, darkTheme)
    val acknowledged = event.statusEnum() == EventStatus.ACKNOWLEDGED
    val fmt = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Glow only while the event still needs attention. An acknowledged critical
            // event is history, and history should not keep shouting.
            // Must precede goneSurface, which clips.
            .severityGlow(
                accent,
                active = !acknowledged && severity != com.infinity.ai.health.domain.Severity.LOW,
                corner = GoneRadius.Card
            )
            .then(goneSurface(darkTheme, corner = GoneRadius.Card))
            .animateContentSize(spring(dampingRatio = Spring.DampingRatioNoBouncy))
    ) {
        SeverityAccent(
            color = if (acknowledged) accent.copy(alpha = 0.35f) else accent,
            corner = 18.dp
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(accent.copy(alpha = 0.14f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Warning,
                        null,
                        tint = accent,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Spacer(Modifier.width(11.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        event.anomalyLabel(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (darkTheme) TextPrimary else TextPrimaryLight,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            severity.wireName,
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "·",
                            style = MaterialTheme.typography.labelSmall,
                            color = secondaryTextColor(darkTheme)
                        )
                        Text(
                            fmt.format(Date(event.createdAt)),
                            style = HealthType.monoSmall,
                            color = secondaryTextColor(darkTheme)
                        )
                    }
                }
                if (acknowledged) {
                    AnimatedCheck(
                        checked = true,
                        color = healthColors(darkTheme).Normal,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    if (expanded) "Collapse" else "Expand",
                    tint = secondaryTextColor(darkTheme),
                    modifier = Modifier.size(19.dp)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(120))
            ) {
                Column {
                    Spacer(Modifier.height(12.dp))

                    Text(
                        event.displayExplanation,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (darkTheme) TextPrimary else TextPrimaryLight,
                        lineHeight = 22.sp
                    )

                    Spacer(Modifier.height(12.dp))

                    // Risk snapshot as it stood when the event fired, not as it is now —
                    // an alert should describe the moment it describes.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RiskPill("Heat", event.riskHeat, darkTheme)
                        RiskPill("Resp", event.riskRespiratory, darkTheme)
                        RiskPill("Cardio", event.riskCardiovascular, darkTheme)
                    }

                    Spacer(Modifier.height(10.dp))

                    Text(
                        "Detected by rule · ${event.ruleIdOrUnknown()}" +
                            if (event.aiExplanation != null) "  ·  reworded on-device" else "",
                        style = HealthType.monoSmall,
                        color = secondaryTextColor(darkTheme)
                    )

                    if (!acknowledged) {
                        Spacer(Modifier.height(14.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(healthColors(darkTheme).Normal.copy(alpha = 0.12f))
                                .border(
                                    1.dp,
                                    healthColors(darkTheme).Normal.copy(alpha = 0.4f),
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    // One of only three haptic moments in the app.
                                    // Acknowledging an alert is a real decision.
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    justAcknowledged = true
                                    onAcknowledge()
                                }
                                .padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                AnimatedCheck(
                                    checked = justAcknowledged,
                                    color = healthColors(darkTheme).Normal,
                                    modifier = Modifier.size(17.dp)
                                )
                                Text(
                                    "Mark as seen",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = healthColors(darkTheme).Normal,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RiskPill(label: String, score: Int, darkTheme: Boolean) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(riskColor(score, darkTheme).copy(alpha = 0.12f))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = secondaryTextColor(darkTheme)
        )
        Text(
            "$score",
            style = HealthType.monoSmall,
            color = riskColor(score, darkTheme),
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Rule id pulled back out of the stored evidence JSON.
 *
 * Read from the payload rather than a dedicated column because the evidence is the record
 * of what actually happened; duplicating the id into a column would create a second source
 * of truth that could drift.
 */
private fun AnomalyEventEntity.ruleIdOrUnknown(): String =
    Regex("\"rule\"\\s*:\\s*\"([^\"]+)\"")
        .find(evidenceJson)
        ?.groupValues
        ?.getOrNull(1)
        ?: "unknown"

