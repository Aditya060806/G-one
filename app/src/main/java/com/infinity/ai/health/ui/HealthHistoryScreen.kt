package com.infinity.ai.health.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.infinity.ai.health.data.VitalsReadingEntity
import com.infinity.ai.health.data.severityEnum
import com.infinity.ai.health.domain.Severity
import com.infinity.ai.ui.components.GradientBackground
import com.infinity.ai.ui.theme.GoneCard
import com.infinity.ai.ui.theme.GoneMotion
import com.infinity.ai.ui.theme.GoneRadius
import com.infinity.ai.ui.theme.goneSurface
import com.infinity.ai.ui.theme.StaggeredEntrance
import com.infinity.ai.ui.theme.TextPrimary
import com.infinity.ai.ui.theme.TextPrimaryLight
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Vitals history, in three views.
 *
 * Charts are hand-rolled Canvas, matching the rest of the app's rendering. Series are
 * downsampled for drawing — an hour at the 5 s sample interval is 720 points, more than a
 * phone-width chart can resolve — using stride sampling so transient spikes survive
 * rather than being averaged away.
 *
 * WHY THREE TABS: a single scroll of six charts made every question equally slow to
 * answer. The tabs split it by the question being asked — "how did the numbers move"
 * (Trends), "when did things fire" (Events), and "how much of the time was I fine"
 * (Insights) — and each view then only has to be good at one of those.
 */
@Composable
fun HealthHistoryScreen(
    isDarkTheme: Boolean,
    bottomPadding: Dp,
    vm: HealthViewModel = viewModel()
) {
    val history by vm.history.collectAsState()
    val events by vm.allEvents.collectAsState()
    val range by vm.range.collectAsState()

    var tab by rememberSaveable { mutableIntStateOf(0) }

    val cutoff = remember(range, history) {
        val newest = history.lastOrNull()?.timestamp ?: System.currentTimeMillis()
        if (range == HistoryRange.ALL) Long.MIN_VALUE else newest - range.millis
    }
    val windowed = remember(cutoff, history) { history.filter { it.timestamp >= cutoff } }

    val markers = remember(events, cutoff, isDarkTheme) {
        events
            .filter { it.createdAt >= cutoff }
            .map { ChartMarker(it.createdAt, severityColor(it.severityEnum(), isDarkTheme)) }
    }
    val windowEvents = remember(events, cutoff) { events.filter { it.createdAt >= cutoff } }

    GradientBackground(darkTheme = isDarkTheme, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            Text(
                "History",
                style = MaterialTheme.typography.titleLarge,
                color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Stored on this device only",
                style = MaterialTheme.typography.bodySmall,
                color = secondaryTextColor(isDarkTheme)
            )

            Spacer(Modifier.height(14.dp))

            RangeSelector(
                selected = range,
                darkTheme = isDarkTheme,
                onSelect = { vm.setRange(it) }
            )

            Spacer(Modifier.height(12.dp))

            HealthTabRow(
                tabs = listOf(
                    HealthTab("Trends"),
                    HealthTab("Events", windowEvents.size),
                    HealthTab("Insights")
                ),
                selectedIndex = tab,
                darkTheme = isDarkTheme,
                onSelect = { tab = it }
            )

            Spacer(Modifier.height(16.dp))

            if (windowed.size < 2) {
                HealthEmptyState(
                    title = "Not enough data yet",
                    body = "Once monitoring has been running for a minute or two, trends " +
                        "will appear here with any events marked on the timeline.",
                    darkTheme = isDarkTheme,
                    modifier = Modifier.height(300.dp)
                )
            } else {
                // Cross-fade between views. No slide: the tabs are peers, and a directional
                // slide would imply an order they do not have.
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        fadeIn(tween(GoneMotion.Medium)) togetherWith
                            fadeOut(tween(GoneMotion.Quick))
                    },
                    label = "historyTab"
                ) { current ->
                    when (current) {
                        0 -> TrendsView(windowed, markers, isDarkTheme)
                        1 -> EventsView(windowed, windowEvents, isDarkTheme)
                        else -> InsightsView(windowed, isDarkTheme, vm)
                    }
                }
            }

            Spacer(Modifier.height(bottomPadding + 28.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Trends
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TrendsView(
    windowed: List<VitalsReadingEntity>,
    markers: List<ChartMarker>,
    darkTheme: Boolean
) {
    val target = 140
    val palette = healthColors(darkTheme)

    Column {
        StaggeredEntrance(0) {
            ChartCard(
                title = "Heart rate",
                unit = "bpm",
                darkTheme = darkTheme,
                points = windowed
                    .mapNotNull { r -> r.heartRate?.let { ChartPoint(r.timestamp, it.toFloat()) } }
                    .downsample(target),
                markers = markers,
                color = palette.Connected
            )
        }

        Spacer(Modifier.height(12.dp))

        StaggeredEntrance(1) {
            ChartCard(
                title = "Blood oxygen",
                unit = "%",
                darkTheme = darkTheme,
                points = windowed
                    .mapNotNull { r -> r.spo2?.let { ChartPoint(r.timestamp, it.toFloat()) } }
                    .downsample(target),
                markers = markers,
                color = Color(0xFF06B6D4),
                // Pinned range: SpO2 only matters in a narrow band, and auto-scaling
                // would make a 96→95 wobble look like a cliff.
                yMin = 85f,
                yMax = 100f
            )
        }

        Spacer(Modifier.height(12.dp))

        StaggeredEntrance(2) {
            ChartCard(
                title = "Body temperature",
                unit = "°C",
                darkTheme = darkTheme,
                points = windowed
                    .mapNotNull { r -> r.bodyTempC?.let { ChartPoint(r.timestamp, it) } }
                    .downsample(target),
                markers = markers,
                color = Color(0xFFF59E0B),
                yMin = 35.5f,
                yMax = 40.5f
            )
        }

        if (windowed.any { it.aqi != null }) {
            Spacer(Modifier.height(12.dp))
            StaggeredEntrance(3) {
                ChartCard(
                    title = "Air quality index",
                    unit = "AQI",
                    darkTheme = darkTheme,
                    points = windowed
                        .mapNotNull { r -> r.aqi?.let { ChartPoint(r.timestamp, it.toFloat()) } }
                        .downsample(target),
                    markers = markers,
                    color = Color(0xFF8B5CF6)
                )
            }
        }

        if (markers.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(modifier = Modifier.size(7.dp).background(palette.Warning, CircleShape))
                Text(
                    "Marks show where an event was detected",
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryTextColor(darkTheme)
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "${windowed.size} readings · ${markers.size} events",
            style = HealthType.monoSmall,
            color = secondaryTextColor(darkTheme)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Events
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EventsView(
    windowed: List<VitalsReadingEntity>,
    windowEvents: List<com.infinity.ai.health.data.AnomalyEventEntity>,
    darkTheme: Boolean
) {
    val bars = remember(windowed, windowEvents) {
        val buckets = timeBuckets(windowed.first().timestamp, windowed.last().timestamp, BucketCount)
        buckets.map { b ->
            BarDatum(b.label, windowEvents.count { it.createdAt >= b.start && it.createdAt < b.end })
        }
    }

    val slices = remember(windowEvents, darkTheme) {
        listOf(
            DonutSlice("Low", windowEvents.count { it.severityEnum() == Severity.LOW },
                severityColor(Severity.LOW, darkTheme)),
            DonutSlice("Moderate", windowEvents.count { it.severityEnum() == Severity.MODERATE },
                severityColor(Severity.MODERATE, darkTheme)),
            DonutSlice("Critical", windowEvents.count { it.severityEnum() == Severity.CRITICAL },
                severityColor(Severity.CRITICAL, darkTheme))
        ).filter { it.value > 0 }
    }

    Column {
        StaggeredEntrance(0) {
            SectionCard("When events fired", darkTheme) {
                if (windowEvents.isEmpty()) {
                    Text(
                        "No events in this window. The engine was watching and found " +
                            "nothing worth raising.",
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryTextColor(darkTheme)
                    )
                } else {
                    BarChart(data = bars, darkTheme = darkTheme)
                }
            }
        }

        if (slices.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            StaggeredEntrance(1) {
                SectionCard("Severity mix", darkTheme) {
                    DonutChart(
                        slices = slices,
                        centerValue = "${windowEvents.size}",
                        centerLabel = "events",
                        darkTheme = darkTheme
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Insights
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun InsightsView(
    windowed: List<VitalsReadingEntity>,
    darkTheme: Boolean,
    vm: HealthViewModel
) {
    val bands = remember(windowed) {
        timeBuckets(windowed.first().timestamp, windowed.last().timestamp, BucketCount)
            .mapNotNull { b ->
                val values = windowed
                    .filter { it.timestamp >= b.start && it.timestamp < b.end }
                    .mapNotNull { it.heartRate?.toFloat() }
                if (values.isEmpty()) null
                else BandDatum(b.label, values.min(), values.max(), values.average().toFloat())
            }
    }

    // Distribution by the SAME severity helpers the tiles use, so this panel cannot
    // disagree with what the dashboard showed.
    val slices = remember(windowed, darkTheme) {
        val withHr = windowed.filter { it.heartRate != null }
        val moderate = withHr.count { vm.heartRateSeverity(it.heartRate) == Severity.MODERATE }
        val critical = withHr.count { vm.heartRateSeverity(it.heartRate) == Severity.CRITICAL }
        val normal = withHr.size - moderate - critical
        listOf(
            DonutSlice("In range", normal, healthColors(darkTheme).Normal),
            DonutSlice("Out of range", moderate, healthColors(darkTheme).Warning),
            DonutSlice("Critical", critical, healthColors(darkTheme).Critical)
        ).filter { it.value > 0 }
    }

    val inRangePct = remember(slices) {
        val total = slices.sumOf { it.value }
        if (total == 0) 0 else (100f * (slices.firstOrNull()?.value ?: 0) / total).toInt()
    }

    Column {
        StaggeredEntrance(0) {
            SectionCard("Heart rate spread", darkTheme) {
                Text(
                    "Each band is the range the heart rate covered in that slice of time. " +
                        "The dot is the average.",
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryTextColor(darkTheme)
                )
                Spacer(Modifier.height(12.dp))
                if (bands.isEmpty()) {
                    Text(
                        "No heart-rate readings in this window.",
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryTextColor(darkTheme)
                    )
                } else {
                    RangeBandChart(data = bands, unit = "bpm", darkTheme = darkTheme)
                }
            }
        }

        if (slices.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            StaggeredEntrance(1) {
                SectionCard("Time in range", darkTheme) {
                    DonutChart(
                        slices = slices,
                        centerValue = "$inRangePct%",
                        centerLabel = "in range",
                        darkTheme = darkTheme
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Bucketing
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Buckets are derived from the data's own span rather than being fixed calendar units.
 *
 * The reading history is capped at 2,000 samples — about 2.8 hours at the 5 s interval —
 * and the range selector tops out at 3 hours. Grouping by day would therefore put
 * everything in one bar. Slicing the actual span into a fixed number of buckets always
 * produces a readable chart regardless of how long monitoring has been running.
 */
private data class TimeBucket(val start: Long, val end: Long, val label: String)

private const val BucketCount = 7

private fun timeBuckets(from: Long, to: Long, count: Int): List<TimeBucket> {
    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    val span = (to - from).coerceAtLeast(1L)
    val step = (span / count).coerceAtLeast(1L)
    return (0 until count).map { i ->
        val start = from + step * i
        // Last bucket absorbs any rounding remainder, and is inclusive of the final
        // sample — otherwise the newest reading falls outside every bucket.
        val end = if (i == count - 1) to + 1 else start + step
        TimeBucket(start, end, fmt.format(Date(start)))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared pieces
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SectionCard(
    title: String,
    darkTheme: Boolean,
    content: @Composable () -> Unit
) {
    GoneCard(darkTheme = darkTheme) {
        Text(
            title.uppercase(),
            style = HealthType.sectionLabel,
            color = secondaryTextColor(darkTheme)
        )
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/**
 * Segmented range control with a sliding pill.
 *
 * The pill animates between positions rather than snapping, which is a small cue that the
 * same chart is being re-scoped rather than replaced.
 */
@Composable
private fun RangeSelector(
    selected: HistoryRange,
    darkTheme: Boolean,
    onSelect: (HistoryRange) -> Unit
) {
    val ranges = HistoryRange.entries
    val index = ranges.indexOf(selected).coerceAtLeast(0)
    val segmentWidth = 66.dp
    val offset by animateDpAsState(
        targetValue = segmentWidth * index,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "rangePill"
    )

    Box(
        modifier = Modifier
            .then(goneSurface(darkTheme, corner = GoneRadius.Pill))
            .padding(3.dp)
    ) {
        Box(
            modifier = Modifier
                .offset(x = offset)
                .width(segmentWidth)
                .height(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(healthColors(darkTheme).Connected.copy(alpha = 0.16f))
        )
        Row {
            ranges.forEach { r ->
                Box(
                    modifier = Modifier
                        .width(segmentWidth)
                        .height(32.dp)
                        .clickable { onSelect(r) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        r.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (r == selected) healthColors(darkTheme).Connected
                        else secondaryTextColor(darkTheme),
                        fontWeight = if (r == selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun ChartCard(
    title: String,
    unit: String,
    darkTheme: Boolean,
    points: List<ChartPoint>,
    markers: List<ChartMarker>,
    color: Color,
    yMin: Float? = null,
    yMax: Float? = null
) {
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    GoneCard(darkTheme = darkTheme) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = secondaryTextColor(darkTheme),
                letterSpacing = 1.2.sp,
                modifier = Modifier.weight(1f)
            )
            if (points.isNotEmpty()) {
                Text(
                    text = formatRange(points, unit),
                    style = HealthType.monoSmall,
                    color = color
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        VitalLineChart(
            points = points,
            markers = markers,
            color = color,
            darkTheme = darkTheme,
            yMin = yMin,
            yMax = yMax,
            modifier = Modifier.fillMaxWidth().height(104.dp)
        )

        if (points.size >= 2) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    fmt.format(Date(points.first().timestamp)),
                    style = HealthType.monoSmall,
                    color = secondaryTextColor(darkTheme)
                )
                Text(
                    fmt.format(Date(points.last().timestamp)),
                    style = HealthType.monoSmall,
                    color = secondaryTextColor(darkTheme)
                )
            }
        }
    }
}

/** "min–max unit", so the y-axis needs no labels. */
private fun formatRange(points: List<ChartPoint>, unit: String): String {
    val min = points.minOf { it.value }
    val max = points.maxOf { it.value }
    val decimals = if (unit == "°C") 1 else 0
    return String.format(Locale.US, "%.${decimals}f–%.${decimals}f %s", min, max, unit)
}
