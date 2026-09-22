package com.gone.ai.health.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gone.ai.ui.theme.LightShadow
import com.gone.ai.ui.theme.ModernBorderDark
import com.gone.ai.ui.theme.ModernBorderLight
import com.gone.ai.ui.theme.ModernCardDark
import com.gone.ai.ui.theme.ModernCardLight
import com.gone.ai.ui.theme.TextPrimary
import com.gone.ai.ui.theme.TextPrimaryLight
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Additional chart types, all hand-drawn on Canvas.
 *
 * Same reasoning as the original set: there is exactly one render style to support and the
 * APK already carries a gigabyte of model weights, so a charting dependency would add
 * size and a second visual language for no benefit. Kept in a separate file from
 * HealthComponents because that file was already long enough to be hard to navigate.
 */

// ─────────────────────────────────────────────────────────────────────────────
// Radial gauge
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A 0–100 score as a swept arc with the value in the middle.
 *
 * Reads faster than a linear bar at small sizes because the value maps to an angle and to
 * arc length at once, and the fixed 270° span gives the eye a stable frame — a half-full
 * bar and a half-full arc are equally accurate, but the arc is recognisable at a glance
 * without reading the axis.
 *
 * The gap sits at the bottom rather than the top so the label underneath has somewhere to
 * live without colliding with the stroke.
 */
@Composable
fun RadialGauge(
    label: String,
    score: Int,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 104.dp
) {
    val clamped = score.coerceIn(0, 100)
    val target = clamped / 100f

    val swept by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(GaugeAnimMs, easing = FastOutSlowInEasing),
        label = "gaugeSweep"
    )
    val color = riskColor(clamped, darkTheme)
    val track = if (darkTheme) ModernBorderDark else ModernBorderLight

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(size),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = (this.size.minDimension * 0.11f)
                val inset = stroke / 2f
                val arcSize = Size(
                    this.size.width - stroke,
                    this.size.height - stroke
                )
                val topLeft = Offset(inset, inset)

                drawArc(
                    color = track,
                    startAngle = StartAngle,
                    sweepAngle = SweepSpan,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                drawArc(
                    color = color,
                    startAngle = StartAngle,
                    sweepAngle = SweepSpan * swept,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$clamped",
                    style = HealthType.vitalLarge,
                    color = if (darkTheme) TextPrimary else TextPrimaryLight
                )
                Text(
                    text = "of 100",
                    style = HealthType.monoSmall,
                    color = secondaryTextColor(darkTheme)
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = secondaryTextColor(darkTheme),
            textAlign = TextAlign.Center
        )
    }
}

private const val StartAngle = 135f
private const val SweepSpan = 270f
private const val GaugeAnimMs = 800

// ─────────────────────────────────────────────────────────────────────────────
// Bar chart
// ─────────────────────────────────────────────────────────────────────────────

/** One bar. [label] is drawn under the axis, so keep it to two or three characters. */
data class BarDatum(val label: String, val value: Int, val color: Color? = null)

/**
 * Vertical bars for counted things — events per day, readings per hour.
 *
 * A zero-count bar still draws a faint stub rather than nothing at all. An absent bar and
 * a bar with a value of zero would otherwise look identical, and "no events that day" is
 * a genuinely different statement from "no data for that day".
 */
@Composable
fun BarChart(
    data: List<BarDatum>,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
    barColor: Color = healthColors(darkTheme).Connected,
    height: Dp = 132.dp
) {
    if (data.isEmpty()) return

    val peak = max(1, data.maxOf { it.value })
    val grow by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(GoneBarAnimMs, easing = FastOutSlowInEasing),
        label = "barGrow"
    )

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().height(height),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            data.forEach { d ->
                val frac = (d.value.toFloat() / peak) * grow
                Column(
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    if (d.value > 0) {
                        Text(
                            text = "${d.value}",
                            style = HealthType.monoSmall,
                            color = secondaryTextColor(darkTheme)
                        )
                        Spacer(Modifier.height(3.dp))
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(frac.coerceAtLeast(0.001f))
                            .clip(BarShape)
                            .then(Modifier)
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawRoundRect(
                                color = d.color ?: barColor,
                                cornerRadius = CornerRadius(this.size.width * 0.32f)
                            )
                        }
                    }
                    if (d.value == 0) {
                        // The "counted, and it was none" stub.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .clip(BarShape)
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                drawRoundRect(
                                    color = (if (darkTheme) ModernBorderDark else ModernBorderLight),
                                    cornerRadius = CornerRadius(1.dp.toPx())
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            data.forEach { d ->
                Text(
                    text = d.label,
                    style = HealthType.monoSmall,
                    color = secondaryTextColor(darkTheme),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

private val BarShape = RoundedCornerShape(6.dp)
private const val GoneBarAnimMs = 700

// ─────────────────────────────────────────────────────────────────────────────
// Range band chart
// ─────────────────────────────────────────────────────────────────────────────

/** A day's spread for one vital. */
data class BandDatum(
    val label: String,
    val min: Float,
    val max: Float,
    val average: Float
)

/**
 * Daily min/max as a vertical band with the average marked inside it.
 *
 * This is the chart a line graph cannot draw. Averaging a day of heart-rate samples into
 * one point hides the two numbers that actually matter — how low it got and how high — and
 * a day that ran 52–148 looks identical to a flat 95 once averaged. The band shows the
 * spread; the dot shows the centre.
 */
@Composable
fun RangeBandChart(
    data: List<BandDatum>,
    unit: String,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
    color: Color = healthColors(darkTheme).Connected,
    height: Dp = 150.dp
) {
    if (data.isEmpty()) return

    val lo = data.minOf { it.min }
    val hi = data.maxOf { it.max }
    // Pad so bands never touch the frame edges, and guard the degenerate all-equal case
    // where hi == lo would divide by zero.
    val span = (hi - lo).takeIf { it > 0.01f } ?: 1f
    val padded = span * 0.15f
    val yMin = lo - padded
    val yMax = hi + padded

    val reveal by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(GoneBarAnimMs, easing = FastOutSlowInEasing),
        label = "bandReveal"
    )

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "${yMax.roundToInt()} $unit",
                style = HealthType.monoSmall,
                color = secondaryTextColor(darkTheme)
            )
            Text(
                "${yMin.roundToInt()} $unit",
                style = HealthType.monoSmall,
                color = secondaryTextColor(darkTheme)
            )
        }
        Spacer(Modifier.height(4.dp))

        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
            val slotWidth = this.size.width / data.size
            val bandWidth = (slotWidth * 0.34f).coerceAtMost(16.dp.toPx())

            fun yOf(v: Float): Float {
                val t = ((v - yMin) / (yMax - yMin)).coerceIn(0f, 1f)
                return this.size.height * (1f - t)
            }

            data.forEachIndexed { i, d ->
                val cx = slotWidth * (i + 0.5f)
                val yTop = yOf(d.max)
                val yBottom = yOf(d.min)

                // Grow the band from its own average outward, so the reveal reads as the
                // spread opening up rather than everything sliding down from the top.
                val yAvg = yOf(d.average)
                val top = yAvg + (yTop - yAvg) * reveal
                val bottom = yAvg + (yBottom - yAvg) * reveal

                drawRoundRect(
                    color = color.copy(alpha = 0.28f),
                    topLeft = Offset(cx - bandWidth / 2f, top),
                    size = Size(bandWidth, (bottom - top).coerceAtLeast(2f)),
                    cornerRadius = CornerRadius(bandWidth / 2f)
                )
                drawCircle(
                    color = color,
                    radius = 3.5.dp.toPx(),
                    center = Offset(cx, yAvg)
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            data.forEach { d ->
                Text(
                    text = d.label,
                    style = HealthType.monoSmall,
                    color = secondaryTextColor(darkTheme),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Donut
// ─────────────────────────────────────────────────────────────────────────────

/** One wedge. [value] is a raw count; proportions are computed from the total. */
data class DonutSlice(val label: String, val value: Int, val color: Color)

/**
 * Proportion donut, used for time-in-range.
 *
 * Wedges are separated by a small angular gap so adjacent slices stay distinguishable
 * without relying on colour alone — which matters both for colour-blind users and for the
 * common case of two similar greens sitting next to each other.
 */
@Composable
fun DonutChart(
    slices: List<DonutSlice>,
    centerValue: String,
    centerLabel: String,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 128.dp
) {
    val total = slices.sumOf { it.value }
    if (total <= 0) return

    val grow by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(GaugeAnimMs, easing = FastOutSlowInEasing),
        label = "donutGrow"
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = this.size.minDimension * 0.16f
                val inset = stroke / 2f
                val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
                var angle = -90f

                slices.forEach { s ->
                    val full = 360f * (s.value.toFloat() / total)
                    val gap = if (full > GapDegrees * 2) GapDegrees else 0f
                    drawArc(
                        color = s.color,
                        startAngle = angle,
                        sweepAngle = (full - gap) * grow,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Butt)
                    )
                    angle += full
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    centerValue,
                    style = HealthType.vitalMedium,
                    color = if (darkTheme) TextPrimary else TextPrimaryLight
                )
                Text(
                    centerLabel,
                    style = HealthType.monoSmall,
                    color = secondaryTextColor(darkTheme)
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            slices.forEach { s ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                    ) {
                        Canvas(Modifier.fillMaxSize()) { drawCircle(s.color) }
                    }
                    Text(
                        s.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = secondaryTextColor(darkTheme)
                    )
                    Text(
                        "${(100f * s.value / total).roundToInt()}%",
                        style = HealthType.monoSmall,
                        color = if (darkTheme) TextPrimary else TextPrimaryLight,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

private const val GapDegrees = 3f

// ─────────────────────────────────────────────────────────────────────────────
// Status ring (home hero)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The home screen's hero: nested arcs around the live heart rate.
 *
 * Three layers, each answering a different question without needing a label:
 *  - the outer sweep is the dominant risk score, so a rising number is visible as a
 *    lengthening arc before any rule fires;
 *  - the inner tick marks are the recent heart-rate series, giving the ring a sense of
 *    history rather than only "now";
 *  - the breathing halo is bound to the monitoring state, so a glance tells you whether
 *    the app is actually watching. That last one matters most: a beautiful dashboard that
 *    is silently not monitoring is worse than an ugly one that is.
 *
 * When idle the halo stops entirely rather than slowing down. An animation that keeps
 * moving while nothing is being measured is a lie about the app's state.
 */
@Composable
fun StatusRing(
    heartRate: Int?,
    dominantRisk: Int,
    stateColor: Color,
    stateLabel: String,
    active: Boolean,
    history: List<Float>,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 210.dp
) {
    val riskFraction by animateFloatAsState(
        targetValue = dominantRisk.coerceIn(0, 100) / 100f,
        animationSpec = tween(900, easing = FastOutSlowInEasing),
        label = "heroRisk"
    )
    val accent by animateColorAsState(stateColor, tween(600), label = "heroAccent")

    // One pulse cycle. Frozen at rest when not monitoring.
    val pulse by rememberInfiniteTransition(label = "heroPulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "heroPulseProgress"
    )
    val livePulse = if (active) pulse else 0f

    val track = if (darkTheme) ModernBorderDark else ModernBorderLight

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {

        Canvas(modifier = Modifier.fillMaxSize()) {
            val dim = this.size.minDimension
            val outerStroke = dim * 0.055f
            val center = Offset(this.size.width / 2f, this.size.height / 2f)

            // Breathing halo, outside everything else.
            if (active) {
                drawCircle(
                    color = accent.copy(alpha = 0.16f * (1f - livePulse)),
                    radius = (dim / 2f) * (0.86f + 0.14f * livePulse),
                    center = center
                )
            }

            // Risk sweep, 270° with the gap at the bottom.
            val arcInset = outerStroke / 2f + dim * 0.03f
            val arcSize = Size(this.size.width - arcInset * 2, this.size.height - arcInset * 2)
            val arcTopLeft = Offset(arcInset, arcInset)

            drawArc(
                color = track,
                startAngle = StartAngle,
                sweepAngle = SweepSpan,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = outerStroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = accent,
                startAngle = StartAngle,
                sweepAngle = SweepSpan * riskFraction,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = outerStroke, cap = StrokeCap.Round)
            )

            // Recent history as radial ticks inside the arc. Length encodes the value's
            // position within the series' own min..max, so a flat series shows short even
            // ticks rather than a misleadingly dramatic ring.
            if (history.size >= 2) {
                val lo = history.min()
                val hi = history.max()
                val span = (hi - lo).takeIf { it > 0.01f }
                val innerR = dim * 0.33f
                val maxTick = dim * 0.075f

                val recent = history.takeLast(48)
                val denom = (recent.size - 1).coerceAtLeast(1).toFloat()
                recent.forEachIndexed { i, v ->
                    val t = i / denom
                    val angleDeg = StartAngle + SweepSpan * t
                    val rad = Math.toRadians(angleDeg.toDouble())
                    val norm = if (span == null) 0.35f else ((v - lo) / span).coerceIn(0f, 1f)
                    val len = maxTick * (0.35f + 0.65f * norm)

                    val sx = center.x + (innerR * kotlin.math.cos(rad)).toFloat()
                    val sy = center.y + (innerR * kotlin.math.sin(rad)).toFloat()
                    val ex = center.x + ((innerR + len) * kotlin.math.cos(rad)).toFloat()
                    val ey = center.y + ((innerR + len) * kotlin.math.sin(rad)).toFloat()

                    drawLine(
                        color = accent.copy(alpha = 0.30f + 0.45f * norm),
                        start = Offset(sx, sy),
                        end = Offset(ex, ey),
                        strokeWidth = dim * 0.007f,
                        cap = StrokeCap.Round
                    )
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedVitalNumber(
                value = heartRate,
                style = HealthType.vitalHero,
                color = if (darkTheme) TextPrimary else TextPrimaryLight
            )
            Text(
                text = "bpm",
                style = HealthType.monoSmall,
                color = secondaryTextColor(darkTheme)
            )
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                ) {
                    Canvas(Modifier.fillMaxSize()) { drawCircle(accent) }
                }
                Text(
                    text = stateLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
