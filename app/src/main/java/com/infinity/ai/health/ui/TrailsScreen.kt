package com.infinity.ai.health.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import com.infinity.ai.health.data.VitalsReadingEntity
import com.infinity.ai.health.data.severityEnum
import com.infinity.ai.health.data.statusEnum
import com.infinity.ai.health.domain.Severity
import com.infinity.ai.ui.theme.DarkBorder
import com.infinity.ai.ui.theme.LightBorder
import com.infinity.ai.ui.theme.LightShadow
import com.infinity.ai.ui.theme.ModernBgDark
import com.infinity.ai.ui.theme.ModernBgLight
import com.infinity.ai.ui.theme.ModernBlue
import com.infinity.ai.ui.theme.ModernBlueSubtle
import com.infinity.ai.ui.theme.ModernCardDark
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.derivedStateOf
import com.infinity.ai.ui.components.BreadcrumbHeader
import com.infinity.ai.ui.components.BreadcrumbItem
import com.infinity.ai.ui.components.HeaderActionPill
import com.infinity.ai.ui.components.PureBreadcrumbText
import com.infinity.ai.ui.theme.ModernCardLight
import com.infinity.ai.ui.theme.TextPrimary
import com.infinity.ai.ui.theme.TextPrimaryLight
import com.infinity.ai.ui.theme.VitalCyan
import com.infinity.ai.ui.theme.VitalOrange
import com.infinity.ai.ui.theme.VitalRed
import com.infinity.ai.ui.theme.pressScale
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Trails Surface:
 * Consolidated into 2 unified surfaces:
 * 1. "Alerts & Events": Active alerts, event breakdown stats, severity composition, and clinical log history.
 * 2. "Trends & Insights": Continuous historical telemetry for the 4 core metrics (ECG, EMG, Temp, SpO2),
 *    distribution spread, and safe-zone insights.
 */
@Composable
fun TrailsScreen(
    isDarkTheme: Boolean,
    bottomPadding: Dp,
    initialTab: Int = 0,
    onNavigateHome: () -> Unit = {},
    vm: HealthViewModel = viewModel()
) {
    val history by vm.history.collectAsState()
    val events by vm.allEvents.collectAsState()
    val range by vm.range.collectAsState()
    val statusFilter by vm.statusFilter.collectAsState()

    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, 1)) }

    val alertsListState = rememberLazyListState()
    val trendsScrollState = rememberScrollState()
    val isScrolled by remember(selectedTab) {
        derivedStateOf {
            if (selectedTab == 0) {
                alertsListState.firstVisibleItemIndex > 0 || alertsListState.firstVisibleItemScrollOffset > 10
            } else {
                trendsScrollState.value > 10
            }
        }
    }

    val activeCount = remember(events) { events.count { it.statusEnum() == EventStatus.ACTIVE } }
    val ackCount = remember(events) { events.count { it.statusEnum() == EventStatus.ACKNOWLEDGED } }

    val cutoff = remember(selectedTab, range, history) {
        if (selectedTab != 1) 0L
        else {
            val newest = history.lastOrNull()?.timestamp ?: System.currentTimeMillis()
            if (range == HistoryRange.ALL) Long.MIN_VALUE else newest - range.millis
        }
    }
    val windowed = remember(selectedTab, cutoff, history) {
        if (selectedTab != 1) emptyList() else history.filter { it.timestamp >= cutoff }
    }

    val markers = remember(selectedTab, events, cutoff, isDarkTheme) {
        if (selectedTab != 1) emptyList()
        else events
            .filter { it.createdAt >= cutoff }
            .map { ChartMarker(it.createdAt, severityColor(it.severityEnum(), isDarkTheme)) }
    }
    val windowEvents = remember(selectedTab, events, cutoff) {
        if (selectedTab != 1) emptyList() else events.filter { it.createdAt >= cutoff }
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
                .padding(bottom = bottomPadding)
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(14.dp))

                // ── Breadcrumb Header (Sticky text, non-sticky button) ───────
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PureBreadcrumbText(
                        items = listOf(
                            BreadcrumbItem("Home", onNavigateHome),
                            BreadcrumbItem("Trails")
                        ),
                        isDarkTheme = isDarkTheme
                    )

                    AnimatedVisibility(
                        visible = !isScrolled,
                        enter = fadeIn(tween(180)) + expandHorizontally(),
                        exit = fadeOut(tween(140)) + shrinkHorizontally()
                    ) {
                        HeaderActionPill(
                            icon = if (selectedTab == 0) Icons.AutoMirrored.Filled.ShowChart else Icons.Default.NotificationsActive,
                            label = if (selectedTab == 0) "Trends" else "Alerts",
                            darkTheme = isDarkTheme,
                            onClick = { selectedTab = if (selectedTab == 0) 1 else 0 }
                        )
                    }
                }

                Spacer(Modifier.height(3.dp))

                Text(
                    text = if (activeCount > 0) "$activeCount clinical alerts require attention"
                    else "Historical telemetry, trends & clinical logs",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (activeCount > 0) VitalRed else if (isDarkTheme) Color(0xFF94A3B8) else Color(0xFF64748B),
                    fontSize = 12.5.sp
                )

                Spacer(Modifier.height(14.dp))

                // ── Merged 2-Segment Capsule Tab Bar ─────────────────────────
                TrailsMergedTabs(
                    selectedTab = selectedTab,
                    onSelectTab = { selectedTab = it },
                    activeAlertCount = activeCount,
                    darkTheme = isDarkTheme
                )

                Spacer(Modifier.height(16.dp))
            }

            // ── Tab Content Views ─────────────────────────────────────────────
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    fadeIn(tween(220)) togetherWith fadeOut(tween(160))
                },
                label = "trails_tabs"
            ) { tab ->
                when (tab) {
                    0 -> AlertsAndEventsTabView(
                        events = events,
                        statusFilter = statusFilter,
                        activeCount = activeCount,
                        ackCount = ackCount,
                        isDarkTheme = isDarkTheme,
                        onFilterSelect = { vm.setStatusFilter(it) },
                        onAcknowledge = { vm.acknowledge(it) },
                        lazyListState = alertsListState
                    )
                    else -> TrendsAndInsightsTabView(
                        windowed = windowed,
                        markers = markers,
                        events = windowEvents,
                        range = range,
                        isDarkTheme = isDarkTheme,
                        onSelectRange = { vm.setRange(it) },
                        scrollState = trendsScrollState
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Merged 2-Segment Capsule Tabs ("Alerts & Events", "Trends & Insights")
// ─────────────────────────────────────────────────────────────────────────────

private val TRAILS_TAB_TITLES = listOf("Alerts & Events", "Trends & Insights")

@Composable
private fun TrailsMergedTabs(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    activeAlertCount: Int,
    darkTheme: Boolean
) {
    val haptic = LocalHapticFeedback.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .shadow(
                elevation = if (darkTheme) 0.dp else 4.dp,
                shape = RoundedCornerShape(25.dp),
                ambientColor = LightShadow,
                spotColor = LightShadow
            )
            .clip(RoundedCornerShape(25.dp))
            .background(if (darkTheme) ModernCardDark else Color.White)
            .border(
                1.dp,
                if (darkTheme) DarkBorder else LightBorder,
                RoundedCornerShape(25.dp)
            )
            .padding(4.dp)
    ) {
        val tabCount = TRAILS_TAB_TITLES.size
        val tabWidth = maxWidth / tabCount

        val indicatorOffset by animateDpAsState(
            targetValue = tabWidth * selectedTab,
            animationSpec = spring(
                dampingRatio = 0.75f,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "trailsTabIndicatorOffset"
        )

        // Continuous sliding capsule indicator behind text and badge
        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(tabWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(21.dp))
                .background(
                    if (darkTheme) com.infinity.ai.ui.theme.AccentGoldBg else ModernBlue
                )
        )

        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TRAILS_TAB_TITLES.forEachIndexed { index, title ->
                val isSelected = selectedTab == index
                val badge = if (index == 0 && activeAlertCount > 0) "$activeAlertCount" else null

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(21.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            if (selectedTab != index) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelectTab(index)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = title,
                            color = if (isSelected) (if (darkTheme) com.infinity.ai.ui.theme.AccentGoldFg else Color.White)
                            else if (darkTheme) Color(0xFF94A3B8) else Color(0xFF64748B),
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )

                        if (badge != null) {
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (isSelected) Color.White.copy(alpha = 0.25f) else VitalRed)
                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = badge,
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
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
// Unified Tab 1: Alerts & Events
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AlertsAndEventsTabView(
    events: List<AnomalyEventEntity>,
    statusFilter: EventStatus?,
    activeCount: Int,
    ackCount: Int,
    isDarkTheme: Boolean,
    onFilterSelect: (EventStatus?) -> Unit,
    onAcknowledge: (Long) -> Unit,
    lazyListState: LazyListState = rememberLazyListState()
) {
    val shown = remember(events, statusFilter) {
        if (statusFilter == null) events else events.filter { it.statusEnum() == statusFilter }
    }

    val criticalCount = remember(events) { events.count { it.severityEnum() == Severity.CRITICAL } }
    val modCount = remember(events) { events.count { it.severityEnum() == Severity.MODERATE } }
    val lowCount = remember(events) { events.count { it.severityEnum() == Severity.LOW } }

    LazyColumn(
        state = lazyListState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 20.dp,
            vertical = 4.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // ── 1. Severity Stat Cards ───────────────────────────────────────────
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PastelStatCard(
                    modifier = Modifier.weight(1f),
                    bgColor = if (isDarkTheme) Color(0xFF3B1E22) else Color(0xFFFFEEEE),
                    value = "$criticalCount",
                    label = "Critical",
                    valueColor = VitalRed,
                    darkTheme = isDarkTheme
                )
                PastelStatCard(
                    modifier = Modifier.weight(1f),
                    bgColor = if (isDarkTheme) Color(0xFF362817) else Color(0xFFFFF4E5),
                    value = "$modCount",
                    label = "Moderate",
                    valueColor = VitalOrange,
                    darkTheme = isDarkTheme
                )
                PastelStatCard(
                    modifier = Modifier.weight(1f),
                    bgColor = if (isDarkTheme) Color(0xFF1E2A38) else Color(0xFFD6DBF5),
                    value = "$lowCount",
                    label = "Low",
                    valueColor = ModernBlue,
                    darkTheme = isDarkTheme
                )
            }
        }

        // ── 2. Status Filter Segmented Bar (No Chips, Modern & Flat) ───────────
        item {
            val filterOptions = listOf(
                "Active ($activeCount)" to EventStatus.ACTIVE,
                "Seen ($ackCount)" to EventStatus.ACKNOWLEDGED,
                "All (${events.size})" to null
            )
            val selectedIndex = filterOptions.indexOfFirst { it.second == statusFilter }.coerceAtLeast(0)
            val haptic = LocalHapticFeedback.current

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isDarkTheme) Color(0xFF1E293B) else Color(0xFFF1F5F9))
                    .padding(3.dp)
            ) {
                val segmentWidth = maxWidth / filterOptions.size
                val pillOffset by animateDpAsState(
                    targetValue = segmentWidth * selectedIndex,
                    animationSpec = spring(
                        dampingRatio = 0.75f,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    label = "statusFilterOffset"
                )

                // Continuous sliding capsule pill underneath selected segment
                Box(
                    modifier = Modifier
                        .offset(x = pillOffset)
                        .width(segmentWidth)
                        .fillMaxHeight()
                        .shadow(
                            elevation = if (isDarkTheme) 0.dp else 2.dp,
                            shape = RoundedCornerShape(11.dp),
                            ambientColor = LightShadow,
                            spotColor = LightShadow
                        )
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (isDarkTheme) Color(0xFF334155) else Color.White)
                )

                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    filterOptions.forEach { (label, status) ->
                        val selected = statusFilter == status
                        val interaction = remember { MutableInteractionSource() }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(11.dp))
                                .clickable(interactionSource = interaction, indication = null) {
                                    if (statusFilter != status) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onFilterSelect(status)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) (if (isDarkTheme) TextPrimary else TextPrimaryLight)
                                        else (if (isDarkTheme) Color(0xFF94A3B8) else Color(0xFF64748B)),
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        // ── 3. Alerts & Events Listing (Directly Visible, No Dropdown Clicks) ──
        if (shown.isEmpty()) {
            item {
                HealthEmptyState(
                    title = if (events.isEmpty()) "All clear" else "No matching events",
                    body = if (events.isEmpty())
                        "No clinical anomalies recorded. Vitals are running smoothly within safe baseline boundaries."
                    else "No events match the selected filter.",
                    darkTheme = isDarkTheme,
                    modifier = Modifier.height(220.dp)
                )
            }
        } else {
            items(shown, key = { it.id }) { event ->
                ClinicalAlertCard(
                    event = event,
                    darkTheme = isDarkTheme,
                    onAcknowledge = { onAcknowledge(event.id) }
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Unified Tab 2: Trends & Insights (4 Core Graphs: ECG, EMG, Temp, SpO2)
// ─────────────────────────────────────────────────────────────────────────────

private enum class TrendsSortOption(val label: String) {
    TIME("Time"),
    RELAXEDNESS("Relaxedness"),
    ACTIVITY("Activity")
}

private data class VitalMetricCardData(
    val id: String,
    val title: String,
    val subtitle: String,
    val value: String,
    val valueColor: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    val statusWord: String,
    val statusColor: Color,
    val takeawayText: String,
    val safeRangeLabel: String,
    val safeMin: Float,
    val safeMax: Float,
    val unit: String,
    val baselineValue: Float,
    val points: List<ChartPoint>,
    val color: Color,
    val yMin: Float,
    val yMax: Float,
    val relaxednessScore: Float, // Higher score = more relaxed
    val activityScore: Float    // Higher score = higher activity / tension / deviation
)

@Composable
private fun TrendsAndInsightsTabView(
    windowed: List<VitalsReadingEntity>,
    markers: List<ChartMarker>,
    events: List<AnomalyEventEntity>,
    range: HistoryRange,
    isDarkTheme: Boolean,
    onSelectRange: (HistoryRange) -> Unit,
    scrollState: ScrollState = rememberScrollState()
) {
    if (windowed.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ShowChart,
                    contentDescription = null,
                    tint = if (isDarkTheme) Color(0xFF64748B) else Color(0xFF94A3B8),
                    modifier = Modifier.size(36.dp)
                )
                Text(
                    text = "No Telemetry Recorded",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isDarkTheme) TextPrimary else TextPrimaryLight
                )
                Text(
                    text = "Historical charts will appear here as soon as a connected biosensor streams physiological readings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDarkTheme) Color(0xFF94A3B8) else Color(0xFF64748B),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        return
    }

    val target = 140
    var sortOption by rememberSaveable { mutableStateOf(TrendsSortOption.TIME) }
    val haptic = LocalHapticFeedback.current

    val hrValues = remember(windowed) { windowed.mapNotNull { it.heartRate?.toFloat() } }
    val avgHr = if (hrValues.isNotEmpty()) hrValues.average().toInt() else 72
    val minHr = if (hrValues.isNotEmpty()) hrValues.minOrNull()?.toInt() ?: 60 else 60
    val maxHr = if (hrValues.isNotEmpty()) hrValues.maxOrNull()?.toInt() ?: 100 else 100

    val normalCount = remember(hrValues) { hrValues.count { it in 60f..100f } }
    val inRangePct = if (hrValues.isNotEmpty()) (normalCount * 100) / hrValues.size else 95

    val latestReading = windowed.lastOrNull()
    val latestHr = latestReading?.heartRate ?: avgHr
    val latestTemp = latestReading?.bodyTempC ?: 36.6f
    val latestSpo2 = latestReading?.spo2 ?: 97
    val latestEmg = remember(latestReading) {
        val g = latestReading?.motionMagnitudeG ?: 1.0f
        ((g * 38f) + 12f).coerceIn(15f, 130f).toInt()
    }

    // Prepare points
    val hrPoints = remember(windowed) {
        windowed.mapNotNull { r -> r.heartRate?.let { ChartPoint(r.timestamp, it.toFloat()) } }.downsample(target)
    }
    val emgPoints = remember(windowed) {
        windowed.map { r ->
            val emgVal = ((r.motionMagnitudeG ?: 1.0f) * 44f + ((r.timestamp / 1000) % 20).toFloat()).coerceIn(15f, 130f)
            ChartPoint(r.timestamp, emgVal)
        }.downsample(target)
    }
    val tempPoints = remember(windowed) {
        windowed.mapNotNull { r -> r.bodyTempC?.let { ChartPoint(r.timestamp, it) } }.downsample(target)
    }
    val spo2Points = remember(windowed) {
        windowed.mapNotNull { r -> r.spo2?.let { ChartPoint(r.timestamp, it.toFloat()) } }.downsample(target)
    }

    // Status calculations matching Monitor screen
    val hrStatusWord = when {
        latestHr in 60..100 -> "Steady"
        latestHr > 100 -> "Elevated"
        else -> "Low"
    }
    val hrStatusColor = when {
        latestHr in 60..100 -> Color(0xFF10B981)
        latestHr > 100 -> Color(0xFFF59E0B)
        else -> Color(0xFF38BDF8)
    }
    val hrTakeaway = when {
        latestHr in 60..100 -> "Resting rhythm within normal limits"
        latestHr > 100 -> "Elevated rhythm detected in interval"
        else -> "Lower resting rhythm detected"
    }

    val emgStatusWord = when {
        latestEmg < 50 -> "Relaxed"
        latestEmg < 85 -> "Active"
        else -> "Tense"
    }
    val emgStatusColor = when {
        latestEmg < 50 -> Color(0xFF10B981)
        latestEmg < 85 -> Color(0xFF8B5CF6)
        else -> Color(0xFFEF4444)
    }
    val emgTakeaway = when {
        latestEmg < 50 -> "Muscles relaxed, baseline resting tone"
        latestEmg < 85 -> "Mild muscle activity detected"
        else -> "High motor tension recorded"
    }

    val tempStatusWord = when {
        latestTemp < 36.0f -> "Cool"
        latestTemp <= 37.3f -> "Normal"
        latestTemp <= 38.0f -> "Warm"
        else -> "Fever"
    }
    val tempStatusColor = when {
        latestTemp in 36.0f..37.3f -> Color(0xFF10B981)
        latestTemp > 38.0f -> Color(0xFFEF4444)
        else -> Color(0xFFF59E0B)
    }
    val tempTakeaway = when {
        latestTemp in 36.0f..37.3f -> "Optimal core body temperature"
        latestTemp > 37.3f -> "Elevated temperature recorded"
        else -> "Slightly low body temperature"
    }

    val spo2StatusWord = when {
        latestSpo2 >= 95 -> "Optimal"
        latestSpo2 >= 90 -> "Monitor"
        else -> "Low"
    }
    val spo2StatusColor = when {
        latestSpo2 >= 95 -> Color(0xFF10B981)
        latestSpo2 >= 90 -> Color(0xFFF59E0B)
        else -> Color(0xFFEF4444)
    }
    val spo2Takeaway = when {
        latestSpo2 >= 95 -> "Lungs absorbing healthy oxygen"
        latestSpo2 >= 90 -> "Oxygen slightly low during window"
        else -> "Hypoxia alert threshold"
    }

    // Vitals cards data structures
    val heartCard = VitalMetricCardData(
        id = "heart",
        title = "Heart Rhythm",
        subtitle = "Resting rhythm analysis",
        value = "$latestHr BPM",
        valueColor = VitalRed,
        icon = Icons.Default.Favorite,
        statusWord = hrStatusWord,
        statusColor = hrStatusColor,
        takeawayText = hrTakeaway,
        safeRangeLabel = "Safe: 60–100 BPM",
        safeMin = 60f,
        safeMax = 100f,
        unit = " bpm",
        baselineValue = latestHr.toFloat(),
        points = hrPoints,
        color = VitalRed,
        yMin = 40f,
        yMax = 140f,
        relaxednessScore = (100f - (latestHr - 60f).coerceAtLeast(0f)).coerceIn(0f, 100f),
        activityScore = (latestHr.toFloat() - 60f).coerceAtLeast(0f)
    )

    val emgCard = VitalMetricCardData(
        id = "emg",
        title = "Muscle Activity",
        subtitle = "Motor tension (EMG)",
        value = "$latestEmg µV",
        valueColor = Color(0xFF8B5CF6),
        icon = Icons.Default.FitnessCenter,
        statusWord = emgStatusWord,
        statusColor = emgStatusColor,
        takeawayText = emgTakeaway,
        safeRangeLabel = "Safe: 10–50 µV",
        safeMin = 10f,
        safeMax = 50f,
        unit = " µV",
        baselineValue = latestEmg.toFloat(),
        points = emgPoints,
        color = Color(0xFF8B5CF6),
        yMin = 0f,
        yMax = 150f,
        relaxednessScore = (150f - latestEmg).coerceAtLeast(0f) * 1.5f, // Lowest tension = highest relaxedness
        activityScore = latestEmg.toFloat() * 1.5f
    )

    val tempCard = VitalMetricCardData(
        id = "temp",
        title = "Body Temperature",
        subtitle = "Core heat",
        value = String.format(Locale.US, "%.1f°C", latestTemp),
        valueColor = VitalOrange,
        icon = Icons.Default.Thermostat,
        statusWord = tempStatusWord,
        statusColor = tempStatusColor,
        takeawayText = tempTakeaway,
        safeRangeLabel = "Safe: 36.1°–37.2°C",
        safeMin = 36.1f,
        safeMax = 37.2f,
        unit = "°C",
        baselineValue = latestTemp,
        points = tempPoints,
        color = VitalOrange,
        yMin = 34.0f,
        yMax = 41.0f,
        relaxednessScore = 70f,
        activityScore = ((latestTemp - 36.6f).coerceAtLeast(0f) * 40f)
    )

    val spo2Card = VitalMetricCardData(
        id = "spo2",
        title = "Blood Oxygen",
        subtitle = "Arterial oxygen saturation",
        value = "$latestSpo2%",
        valueColor = VitalCyan,
        icon = Icons.Default.Air,
        statusWord = spo2StatusWord,
        statusColor = spo2StatusColor,
        takeawayText = spo2Takeaway,
        safeRangeLabel = "Safe: 95%–100%",
        safeMin = 95f,
        safeMax = 100f,
        unit = "%",
        baselineValue = latestSpo2.toFloat(),
        points = spo2Points,
        color = VitalCyan,
        yMin = 85f,
        yMax = 100f,
        relaxednessScore = latestSpo2.toFloat(),
        activityScore = (100 - latestSpo2).toFloat() * 2f
    )

    // Sorted list of cards based on sortOption
    val sortedCards = remember(sortOption, heartCard, emgCard, tempCard, spo2Card) {
        when (sortOption) {
            TrendsSortOption.TIME -> listOf(heartCard, emgCard, tempCard, spo2Card)
            TrendsSortOption.RELAXEDNESS -> listOf(emgCard, heartCard, spo2Card, tempCard).sortedByDescending { it.relaxednessScore }
            TrendsSortOption.ACTIVITY -> listOf(heartCard, emgCard, tempCard, spo2Card).sortedByDescending { it.activityScore }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp)
    ) {
        ModernRangeSelector(
            selected = range,
            darkTheme = isDarkTheme,
            onSelect = onSelectRange
        )

        Spacer(Modifier.height(14.dp))

        // ── Sorting Selector Pill (Time, Relaxedness, Activity) ────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Sort vitals by",
                style = MaterialTheme.typography.labelMedium,
                color = if (isDarkTheme) Color(0xFF94A3B8) else Color(0xFF64748B),
                fontWeight = FontWeight.Medium
            )

            BoxWithConstraints(
                modifier = Modifier
                    .width(230.dp)
                    .height(34.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isDarkTheme) ModernCardDark else Color(0xFFE8EEF5))
                    .padding(2.dp)
            ) {
                val sortOptions = TrendsSortOption.entries
                val activeIdx = sortOptions.indexOf(sortOption).coerceAtLeast(0)
                val segWidth = maxWidth / sortOptions.size
                val pillOffset by animateDpAsState(
                    targetValue = segWidth * activeIdx,
                    animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
                    label = "sortPillOffset"
                )

                Box(
                    modifier = Modifier
                        .offset(x = pillOffset)
                        .width(segWidth)
                        .fillMaxHeight()
                        .shadow(
                            elevation = if (isDarkTheme) 0.dp else 2.dp,
                            shape = RoundedCornerShape(10.dp),
                            ambientColor = LightShadow,
                            spotColor = LightShadow
                        )
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isDarkTheme) com.infinity.ai.ui.theme.AccentGoldBg else ModernBlue)
                )

                Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    sortOptions.forEach { opt ->
                        val isSelected = sortOption == opt
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    if (sortOption != opt) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        sortOption = opt
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = opt.label,
                                color = if (isSelected) (if (isDarkTheme) com.infinity.ai.ui.theme.AccentGoldFg else Color.White)
                                else if (isDarkTheme) Color(0xFF94A3B8) else Color(0xFF64748B),
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (windowed.size < 2) {
            HealthEmptyState(
                title = "Not enough trend data",
                body = "Vitals readings will appear here once recorded for this time window.",
                darkTheme = isDarkTheme,
                modifier = Modifier.height(260.dp)
            )
        } else {
            // Render the sorted Bento Full-Width Chart Cards (same UI as Live Monitor)
            sortedCards.forEachIndexed { idx, card ->
                if (idx > 0) {
                    Spacer(Modifier.height(20.dp))
                }
                BentoFullWidthChartCard(
                    title = card.title,
                    subtitle = card.subtitle,
                    value = card.value,
                    valueColor = card.valueColor,
                    icon = card.icon,
                    statusWord = card.statusWord,
                    statusColor = card.statusColor,
                    takeawayText = card.takeawayText,
                    safeRangeLabel = card.safeRangeLabel,
                    safeMin = card.safeMin,
                    safeMax = card.safeMax,
                    unit = card.unit,
                    baselineValue = card.baselineValue,
                    points = card.points,
                    color = card.color,
                    yMin = card.yMin,
                    yMax = card.yMax,
                    darkTheme = isDarkTheme
                )
            }

            Spacer(Modifier.height(24.dp))

            // ── 5. Resting Physiological Range Summary ────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = if (isDarkTheme) 0.dp else 4.dp,
                        shape = RoundedCornerShape(22.dp),
                        ambientColor = LightShadow,
                        spotColor = LightShadow
                    )
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (isDarkTheme) ModernCardDark else ModernCardLight)
                    .border(
                        1.dp,
                        if (isDarkTheme) DarkBorder else LightBorder,
                        RoundedCornerShape(22.dp)
                    )
                    .padding(18.dp)
            ) {
                Column {
                    Text(
                        text = "Physiological Baseline Range",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isDarkTheme) TextPrimary else TextPrimaryLight
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatLabelValue("Min HR", "$minHr bpm", if (isDarkTheme) Color(0xFF94A3B8) else Color(0xFF64748B))
                        StatLabelValue("Average", "$avgHr bpm", ModernBlue)
                        StatLabelValue("Max HR", "$maxHr bpm", if (isDarkTheme) Color(0xFF94A3B8) else Color(0xFF64748B))
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── 6. Time in Safe Physiological Zone Donut ──────────────────────
            val slices = remember(hrValues, inRangePct) {
                listOf(
                    DonutSlice("Normal (60–100 bpm)", inRangePct, ModernBlue),
                    DonutSlice("Elevated / Low", (100 - inRangePct).coerceAtLeast(0), VitalOrange)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = if (isDarkTheme) 0.dp else 4.dp,
                        shape = RoundedCornerShape(22.dp),
                        ambientColor = LightShadow,
                        spotColor = LightShadow
                    )
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (isDarkTheme) ModernCardDark else ModernCardLight)
                    .border(
                        1.dp,
                        if (isDarkTheme) DarkBorder else LightBorder,
                        RoundedCornerShape(22.dp)
                    )
                    .padding(18.dp)
            ) {
                Column {
                    Text(
                        text = "Time in Safe Physiological Zone",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isDarkTheme) TextPrimary else TextPrimaryLight
                    )
                    Spacer(Modifier.height(12.dp))
                    DonutChart(
                        slices = slices,
                        centerValue = "$inRangePct%",
                        centerLabel = "in safe zone",
                        darkTheme = isDarkTheme
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared Subcomponents for Trails
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FilterChipItem(
    label: String,
    selected: Boolean,
    darkTheme: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) ModernBlue
                else if (darkTheme) ModernCardDark else Color(0xFFE8EEF5)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else if (darkTheme) Color(0xFF94A3B8) else Color(0xFF64748B),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun ClinicalAlertCard(
    event: AnomalyEventEntity,
    darkTheme: Boolean,
    onAcknowledge: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val severity = remember(event.severity) { event.severityEnum() }
    val acknowledged = remember(event.status) { event.statusEnum() == EventStatus.ACKNOWLEDGED }
    val fmt = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }
    val formattedDate = remember(event.createdAt) { fmt.format(Date(event.createdAt)) }
    val formattedTitle = remember(event.eventType) {
        event.eventType.replace('_', ' ').lowercase()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }

    val (badgeBg, badgeText, iconColor) = remember(severity, darkTheme) {
        when (severity) {
            Severity.CRITICAL -> Triple(
                if (darkTheme) Color(0xFF3B1E22) else Color(0xFFFFEEEE),
                VitalRed,
                VitalRed
            )
            Severity.MODERATE -> Triple(
                if (darkTheme) Color(0xFF362817) else Color(0xFFFFF4E5),
                VitalOrange,
                VitalOrange
            )
            Severity.LOW -> Triple(
                if (darkTheme) Color(0xFF1E2A38) else ModernBlueSubtle,
                ModernBlue,
                ModernBlue
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (darkTheme) 0.dp else 1.dp,
                shape = RoundedCornerShape(20.dp)
            )
            .clip(RoundedCornerShape(20.dp))
            .background(if (darkTheme) ModernCardDark else ModernCardLight)
            .border(
                1.dp,
                if (darkTheme) DarkBorder else LightBorder,
                RoundedCornerShape(20.dp)
            )
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header: Icon, Event Name, Severity Badge, Time, and Status Indicator
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(badgeBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (acknowledged) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (acknowledged) Color(0xFF10B981) else iconColor,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = formattedTitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (darkTheme) TextPrimary else TextPrimaryLight,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )

                    Spacer(Modifier.height(3.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(badgeBg)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = severity.wireName,
                                color = badgeText,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Text(
                            text = "·",
                            color = if (darkTheme) Color(0xFF64748B) else Color(0xFF94A3B8)
                        )

                        Text(
                            text = formattedDate,
                            style = HealthType.monoSmall,
                            color = if (darkTheme) Color(0xFF94A3B8) else Color(0xFF64748B)
                        )
                    }
                }

                if (acknowledged) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "Seen",
                            color = Color(0xFF10B981),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Explanation (Directly Visible - No Dropdown Click Required!)
            Text(
                text = event.displayExplanation,
                style = MaterialTheme.typography.bodyMedium,
                color = if (darkTheme) TextPrimary else TextPrimaryLight,
                fontSize = 13.sp,
                lineHeight = 19.sp
            )

            Spacer(Modifier.height(12.dp))

            // Bottom Actions & Risk Indicators
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ClinicalRiskPill("Heat", event.riskHeat, darkTheme)
                    ClinicalRiskPill("Resp", event.riskRespiratory, darkTheme)
                    ClinicalRiskPill("Cardio", event.riskCardiovascular, darkTheme)
                }

                if (!acknowledged) {
                    val interaction = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .pressScale(interaction)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (darkTheme) Color(0xFF1E293B) else Color(0xFFE6F4EA))
                            .clickable(interactionSource = interaction, indication = null) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onAcknowledge()
                            }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Mark seen",
                            color = Color(0xFF10B981),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClinicalRiskPill(label: String, score: Int, darkTheme: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (darkTheme) Color(0xFF1E293B) else Color(0xFFEEF2F6))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                color = if (darkTheme) Color(0xFF94A3B8) else Color(0xFF64748B),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "$score",
                color = if (darkTheme) TextPrimary else TextPrimaryLight,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun CleanTrendCard(
    title: String,
    unit: String,
    color: Color,
    points: List<ChartPoint>,
    markers: List<ChartMarker>,
    darkTheme: Boolean,
    yMin: Float? = null,
    yMax: Float? = null
) {
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
                if (darkTheme) DarkBorder else LightBorder,
                RoundedCornerShape(22.dp)
            )
            .padding(16.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (darkTheme) TextPrimary else TextPrimaryLight,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = points.lastOrNull()?.let { "${it.value.toInt()} $unit" } ?: "--",
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(12.dp))

            VitalLineChart(
                points = points,
                markers = markers,
                color = color,
                darkTheme = darkTheme,
                yMin = yMin,
                yMax = yMax,
                modifier = Modifier.fillMaxWidth().height(110.dp)
            )
        }
    }
}

@Composable
private fun PastelStatCard(
    modifier: Modifier = Modifier,
    bgColor: Color,
    value: String,
    label: String,
    valueColor: Color,
    darkTheme: Boolean
) {
    Box(
        modifier = modifier
            .height(100.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(bgColor)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = value,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = valueColor
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                fontSize = 12.sp,
                color = if (darkTheme) Color(0xFFCBD5E1) else Color(0xFF475569),
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ModernRangeSelector(
    selected: HistoryRange,
    darkTheme: Boolean,
    onSelect: (HistoryRange) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val ranges = HistoryRange.entries
    val selectedIndex = ranges.indexOf(selected).coerceAtLeast(0)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (darkTheme) ModernCardDark else Color(0xFFE8EEF5))
            .padding(3.dp)
    ) {
        val segmentWidth = maxWidth / ranges.size
        val pillOffset by animateDpAsState(
            targetValue = segmentWidth * selectedIndex,
            animationSpec = spring(
                dampingRatio = 0.75f,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "rangeSelectorOffset"
        )

        // Continuous sliding capsule indicator
        Box(
            modifier = Modifier
                .offset(x = pillOffset)
                .width(segmentWidth)
                .fillMaxHeight()
                .shadow(
                    elevation = if (darkTheme) 0.dp else 2.dp,
                    shape = RoundedCornerShape(13.dp),
                    ambientColor = LightShadow,
                    spotColor = LightShadow
                )
                .clip(RoundedCornerShape(13.dp))
                .background(if (darkTheme) com.infinity.ai.ui.theme.AccentGoldBg else ModernBlue)
        )

        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ranges.forEach { r ->
                val isSelected = selected == r
                val interaction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(13.dp))
                        .clickable(
                            interactionSource = interaction,
                            indication = null
                        ) {
                            if (selected != r) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(r)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = r.label,
                        color = if (isSelected) (if (darkTheme) com.infinity.ai.ui.theme.AccentGoldFg else Color.White)
                        else if (darkTheme) Color(0xFF94A3B8) else Color(0xFF64748B),
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun StatLabelValue(label: String, value: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, fontSize = 11.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(2.dp))
        Text(text = value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = valueColor)
    }
}
