package com.infinity.ai.health.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infinity.ai.health.data.AnomalyEventEntity
import com.infinity.ai.health.data.anomalyType
import com.infinity.ai.health.domain.Severity
import com.infinity.ai.ui.theme.GoneMotion
import com.infinity.ai.ui.theme.GoneRadius
import com.infinity.ai.ui.theme.goneSurface
import com.infinity.ai.ui.theme.pressScale
import com.infinity.ai.ui.theme.DarkBorder
import com.infinity.ai.ui.theme.DarkSurface
import com.infinity.ai.ui.theme.LightBorder
import com.infinity.ai.ui.theme.LightSurface
import com.infinity.ai.ui.theme.TextPrimary
import com.infinity.ai.ui.theme.TextPrimaryLight
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Shared health UI primitives.
 *
 * All drawing is hand-rolled Canvas, matching the approach already used by `AiBodyOrb`
 * and `WaveformAnimation`. No charting library: one rendering style across the whole app
 * is worth more than the few hours a dependency would save, and it keeps the APK — which
 * already carries a 1 GB model — from growing further.
 */

// ── Numbers ───────────────────────────────────────────────────────────────────

/**
 * A live integer reading that interpolates to its new value.
 *
 * WHY INTERPOLATE AT ALL: a label that silently swaps 78 for 84 reads as a static field
 * that occasionally changes. Ticking between them over ~400 ms reads as a measurement
 * being taken. The data is genuinely live, so it should look it.
 *
 * Paired with tabular figures from [HealthType], so the digits change without the layout
 * shifting underneath them.
 */
@Composable
fun AnimatedVitalNumber(
    value: Int?,
    modifier: Modifier = Modifier,
    style: TextStyle = HealthType.vitalLarge,
    color: Color = Color.Unspecified,
    suffix: String = "",
    placeholder: String = "––"
) {
    if (value == null) {
        Text(placeholder, modifier = modifier, style = style, color = color)
        return
    }
    val animated by animateFloatAsState(
        targetValue = value.toFloat(),
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "vitalInt"
    )
    Text(
        text = "${animated.roundToInt()}$suffix",
        modifier = modifier,
        style = style,
        color = color
    )
}

/** Decimal variant, for body temperature. One decimal place, always a decimal point. */
@Composable
fun AnimatedVitalDecimal(
    value: Float?,
    modifier: Modifier = Modifier,
    style: TextStyle = HealthType.vitalLarge,
    color: Color = Color.Unspecified,
    suffix: String = "",
    placeholder: String = "––"
) {
    if (value == null) {
        Text(placeholder, modifier = modifier, style = style, color = color)
        return
    }
    val animated by animateFloatAsState(
        targetValue = value,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "vitalDecimal"
    )
    Text(
        // Locale.US so a comma-decimal locale cannot turn 37.4 into "37,4".
        text = String.format(java.util.Locale.US, "%.1f%s", animated, suffix),
        modifier = modifier,
        style = style,
        color = color
    )
}

// ── Accents and glow ──────────────────────────────────────────────────────────

/**
 * Left-edge severity bar.
 *
 * Colouring a thin edge rather than the whole card keeps the screen calm even when a
 * value sits in the warning band, while still being unambiguous at a glance. A grid of
 * fully amber cards reads as panic; a grid of calm cards with one amber edge reads as
 * information.
 */
@Composable
fun SeverityAccent(
    color: Color,
    modifier: Modifier = Modifier,
    width: Dp = 3.dp,
    corner: Dp = 18.dp
) {
    Box(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .background(
                Brush.verticalGradient(listOf(color, color.copy(alpha = 0.45f))),
                RoundedCornerShape(topStart = corner, bottomStart = corner)
            )
    )
}

/**
 * Soft outer glow, used only for warning and critical states.
 *
 * Simulated with a few concentric translucent rounded rects behind the content rather
 * than a real blur, which keeps it cheap and available on every API level. Glow reads as
 * "actively significant" in a way a flat border change does not.
 */
fun Modifier.severityGlow(
    color: Color,
    active: Boolean,
    corner: Dp = 18.dp,
    layers: Int = 4
): Modifier = if (!active) this else this.drawBehind {
    val r = corner.toPx()
    repeat(layers) { i ->
        val spread = (i + 1) * 3.dp.toPx()
        drawRoundRect(
            color = color.copy(alpha = 0.16f / (i + 1)),
            topLeft = Offset(-spread, -spread),
            size = Size(size.width + spread * 2, size.height + spread * 2),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r + spread)
        )
    }
}

// ── Connection badge ──────────────────────────────────────────────────────────

/**
 * Monitoring status pill with a breathing dot when live.
 *
 * The ~2 s breathing rhythm deliberately echoes the orb's idle pulse, so the app reads as
 * one coherent organism rather than a theme applied to unrelated screens.
 */
@Composable
fun MonitoringBadge(
    label: String,
    color: Color,
    live: Boolean,
    darkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    val inf = rememberInfiniteTransition(label = "badge")
    val pulse by inf.animateFloat(
        initialValue = if (live) 0.45f else 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(2000, easing = EaseInOut), RepeatMode.Reverse
        ),
        label = "badgePulse"
    )
    val dotAlpha = if (live) pulse else 1f

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (darkTheme) DarkSurface else LightSurface)
            .border(1.dp, if (darkTheme) DarkBorder else LightBorder, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(color.copy(alpha = dotAlpha), CircleShape)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (darkTheme) TextPrimary else TextPrimaryLight,
            fontWeight = FontWeight.Medium
        )
    }
}

// ── Sparkline ─────────────────────────────────────────────────────────────────

/**
 * Tiny inline trend shape. No axes, no labels — only the silhouette of where a vital has
 * been. Enough to tell "steady" from "sliding" without reading a number.
 */
@Composable
fun Sparkline(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val min = values.min()
        val max = values.max()
        val span = (max - min).takeIf { it > 0.0001f } ?: 1f
        val stepX = size.width / (values.size - 1)

        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            // Inset vertically so the stroke is never clipped at the extremes.
            val y = size.height - ((v - min) / span) * (size.height * 0.82f) - size.height * 0.09f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

// ── Vital tile ────────────────────────────────────────────────────────────────

/**
 * One measurement, its trend, and its severity, as a dashboard tile.
 *
 * [icon] is tinted with the severity accent, giving the tile a second, non-colour channel
 * for identification — useful both for scanning and for anyone who cannot separate the
 * amber and green by hue alone.
 *
 * Press feedback comes from a shared [MutableInteractionSource] so the scale animation and
 * the click observe the same gesture stream.
 */
@Composable
fun VitalTile(
    label: String,
    unit: String,
    severity: Severity?,
    darkTheme: Boolean,
    history: List<Float>,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val palette = healthColors(darkTheme)
    val accent = severity?.let { severityColor(it, darkTheme) } ?: palette.Normal
    val warn = severity != null && severity != Severity.LOW

    val glowColor by animateColorAsState(accent, tween(600), label = "tileGlow")
    val interaction = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .then(if (onClick != null) Modifier.pressScale(interaction) else Modifier)
            // severityGlow draws outside its own bounds, so it must precede the surface —
            // goneSurface clips, and a clipped glow is no glow at all.
            .severityGlow(glowColor, active = warn, corner = GoneRadius.Card)
            .then(goneSurface(darkTheme, corner = GoneRadius.Card))
            .then(
                if (onClick != null)
                    Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
                else Modifier
            )
    ) {
        SeverityAccent(color = accent, corner = 18.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    text = label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = secondaryTextColor(darkTheme),
                    letterSpacing = 1.sp
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                content()
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = secondaryTextColor(darkTheme),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }
            if (history.size >= 2) {
                Sparkline(
                    values = history,
                    color = accent.copy(alpha = 0.55f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                )
            } else {
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

// ── Risk meter ────────────────────────────────────────────────────────────────

/**
 * One 0–100 risk axis.
 *
 * These are shown whether or not any rule has fired, which is the whole early-warning
 * mechanism: a bar creeping up over an afternoon is visible long before a threshold is
 * crossed.
 */
@Composable
fun RiskMeter(
    label: String,
    score: Int,
    darkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    val animated by animateFloatAsState(
        targetValue = score.coerceIn(0, 100) / 100f,
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "risk"
    )
    val color by animateColorAsState(riskColor(score, darkTheme), tween(500), label = "riskColor")

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = secondaryTextColor(darkTheme)
            )
            Text(
                text = "$score",
                style = HealthType.monoSmall,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (darkTheme) DarkBorder else LightBorder)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animated)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
            )
        }
    }
}

// ── Live waveform ─────────────────────────────────────────────────────────────

/**
 * Animated bar strip standing in for a live signal trace.
 *
 * Evolved from Infinity's voice-input `WaveformAnimation` — geometrically it is the same
 * idea, a horizontal strip conveying continuous activity. Idle renders flat rather than
 * animating, because a waveform moving while nothing is connected would be a lie.
 */
@Composable
fun LiveWaveform(
    active: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    bars: Int = 32
) {
    if (!active) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(bars) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(color.copy(alpha = 0.3f))
                )
            }
        }
        return
    }

    val inf = rememberInfiniteTransition(label = "wave")
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(bars) { i ->
            val height by inf.animateFloat(
                initialValue = 2f,
                targetValue = (4 + (i * 7) % 17).toFloat(),
                animationSpec = infiniteRepeatable(
                    tween(360 + (i * 23) % 420, easing = EaseInOut),
                    RepeatMode.Reverse
                ),
                label = "bar$i"
            )
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(height.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(color.copy(alpha = 0.85f))
            )
        }
    }
}

// ── Line chart ────────────────────────────────────────────────────────────────

/** A point on a chart: when it was measured, and what was measured. */
data class ChartPoint(val timestamp: Long, val value: Float)

/** An event to mark on the timeline. */
data class ChartMarker(val timestamp: Long, val color: Color)

/**
 * Time-series chart with anomaly markers.
 *
 * The line traces in left-to-right on first appearance using `PathMeasure.getSegment` —
 * the same technique the orb uses for its arcs. Data that draws itself reads as presented
 * rather than dumped, and it costs one animation.
 *
 * X positions come from real timestamps, not from list indices, so a gap in the data
 * shows as a gap rather than being silently compressed away.
 */
@Composable
fun VitalLineChart(
    points: List<ChartPoint>,
    markers: List<ChartMarker>,
    color: Color,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
    /** Optional fixed y-range; falls back to the data's own range. */
    yMin: Float? = null,
    yMax: Float? = null
) {
    val progress by animateFloatAsState(
        targetValue = if (points.size >= 2) 1f else 0f,
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "chartDraw"
    )
    val gridColor = if (darkTheme) DarkBorder else LightBorder

    Canvas(modifier = modifier) {
        // Baseline grid, drawn even when empty so the chart has presence before data.
        repeat(4) { i ->
            val y = size.height * (i / 3f)
            drawLine(
                color = gridColor.copy(alpha = 0.5f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f
            )
        }
        if (points.size < 2) return@Canvas

        val tMin = points.first().timestamp
        val tMax = points.last().timestamp
        val tSpan = (tMax - tMin).takeIf { it > 0L } ?: 1L

        val dataMin = yMin ?: points.minOf { it.value }
        val dataMax = yMax ?: points.maxOf { it.value }
        // Pad a flat series so it renders as a centre line instead of collapsing.
        val vSpan = (dataMax - dataMin).takeIf { abs(it) > 0.0001f } ?: 1f

        fun xOf(t: Long) = ((t - tMin).toFloat() / tSpan) * size.width
        fun yOf(v: Float) =
            size.height - ((v - dataMin) / vSpan) * (size.height * 0.84f) - size.height * 0.08f

        val path = Path()
        points.forEachIndexed { i, p ->
            val x = xOf(p.timestamp)
            val y = yOf(p.value)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        // Trace the line in. NB: this local must not be named `android`, or it shadows
        // the package name and `android.graphics.PathMeasure` fails to resolve.
        val nativePath = path.asAndroidPath()
        val measure = android.graphics.PathMeasure(nativePath, false)
        val total = measure.length
        if (total > 0f) {
            val dst = android.graphics.Path()
            measure.getSegment(0f, total * progress, dst, true)
            drawPath(
                path = dst.asComposePath(),
                color = color,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        // Latest-value dot, once the trace has arrived.
        if (progress > 0.98f) {
            val last = points.last()
            drawCircle(
                color = color,
                radius = 3.dp.toPx(),
                center = Offset(xOf(last.timestamp), yOf(last.value))
            )
        }

        // Event markers on the timeline. Drawn as vertical ticks plus a dot so they read
        // as "something happened at this moment", not as part of the series.
        markers.forEach { m ->
            if (m.timestamp < tMin || m.timestamp > tMax) return@forEach
            val x = xOf(m.timestamp)
            drawLine(
                color = m.color.copy(alpha = 0.35f),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1.5.dp.toPx()
            )
            drawCircle(
                color = m.color,
                radius = 3.5.dp.toPx(),
                center = Offset(x, size.height * 0.08f)
            )
        }
    }
}

// ── Check morph ───────────────────────────────────────────────────────────────

/**
 * Checkmark that draws itself on when [checked] becomes true.
 *
 * Used on acknowledge. A mundane state change becomes a small moment of closure, which
 * matters emotionally for a caregiver who has just dealt with a health alert.
 */
@Composable
fun AnimatedCheck(
    checked: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "check"
    )
    Canvas(modifier = modifier) {
        if (progress <= 0.01f) return@Canvas
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.20f, h * 0.52f)
            lineTo(w * 0.43f, h * 0.74f)
            lineTo(w * 0.80f, h * 0.28f)
        }
        val ap = path.asAndroidPath()
        val measure = android.graphics.PathMeasure(ap, false)
        val dst = android.graphics.Path()
        measure.getSegment(0f, measure.length * progress, dst, true)
        drawPath(
            path = dst.asComposePath(),
            color = color,
            style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

// ── Spinner ───────────────────────────────────────────────────────────────────

/**
 * Indeterminate ring, for the brief window before the first sample lands.
 *
 * When [active] is false the sweeping segment is not drawn at all, leaving only the static
 * track. A ring that keeps spinning while nothing is being measured claims the app is busy
 * when it is idle, which is the one thing a monitoring indicator must never do.
 */
@Composable
fun PulseRing(color: Color, modifier: Modifier = Modifier, active: Boolean = true) {
    val inf = rememberInfiniteTransition(label = "ring")
    val sweep by inf.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "sweep"
    )
    Canvas(modifier = modifier) {
        drawArc(
            color = color.copy(alpha = 0.25f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )
        if (active) {
            drawArc(
                color = color,
                startAngle = sweep,
                sweepAngle = 90f,
                useCenter = false,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

/**
 * Human-readable name for a stored event.
 *
 * Lives here rather than in either screen because the dashboard and the alerts list both
 * need it, and it previously sat as a helper inside the dashboard file — which meant
 * rewriting that screen silently broke the alerts list. Shared display helpers belong in
 * the shared component file.
 *
 * Falls back to the raw `eventType` string rather than a placeholder: if a future rule
 * writes a wire name this build does not know, showing the unknown identifier is more
 * useful to whoever is debugging it than the word "Unknown".
 */
fun AnomalyEventEntity.anomalyLabel(): String =
    anomalyType()?.label ?: eventType

/** Shared empty-state block, so every screen says "nothing yet" the same way. */
@Composable
fun HealthEmptyState(
    title: String,
    body: String,
    darkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val accent = healthColors(darkTheme).Connected
        Box(
            modifier = Modifier
                .size(58.dp)
                .background(accent.copy(alpha = 0.10f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            PulseRing(
                color = accent.copy(alpha = 0.55f),
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = if (darkTheme) TextPrimary else TextPrimaryLight,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = secondaryTextColor(darkTheme),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tabs
// ─────────────────────────────────────────────────────────────────────────────

/**
 * One tab. [count] is optional and renders as a trailing badge when non-null.
 *
 * A count of zero still shows, deliberately — "Active 0" is reassuring information, and
 * hiding it would make the badge appear and disappear as events arrive, which is a more
 * distracting layout change than a stable zero.
 */
data class HealthTab(val label: String, val count: Int? = null)

/**
 * Segmented tab row with a pill that slides between positions.
 *
 * Equal-width segments rather than content-width ones. It costs a little horizontal
 * efficiency, but it means the indicator's travel is a pure function of the selected
 * index, with no measurement pass and no chance of the pill and the label disagreeing
 * mid-animation — which is the usual way hand-rolled tab indicators go wrong.
 *
 * The pill animates with a mildly bouncy spring while the label colour cross-fades on a
 * shorter tween, so the movement leads and the colour settles behind it.
 */
@Composable
fun HealthTabRow(
    tabs: List<HealthTab>,
    selectedIndex: Int,
    darkTheme: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (tabs.isEmpty()) return

    val palette = healthColors(darkTheme)
    val safeIndex = selectedIndex.coerceIn(0, tabs.lastIndex)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .then(goneSurface(darkTheme, corner = GoneRadius.Pill))
    ) {
        val segment: Dp = maxWidth / tabs.size

        val offset by animateDpAsState(
            targetValue = segment * safeIndex,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
            label = "tabPill"
        )

        Box(
            modifier = Modifier
                .offset(x = offset)
                .width(segment)
                .fillMaxHeight()
                .padding(3.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(palette.Connected.copy(alpha = 0.15f))
        )

        Row(modifier = Modifier.fillMaxSize()) {
            tabs.forEachIndexed { i, tab ->
                val selected = i == safeIndex
                val labelColor by animateColorAsState(
                    targetValue = if (selected) palette.Connected else secondaryTextColor(darkTheme),
                    animationSpec = tween(GoneMotion.Quick),
                    label = "tabLabel$i"
                )

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelect(i) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = labelColor,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1
                    )
                    if (tab.count != null) {
                        Spacer(Modifier.width(5.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (selected) palette.Connected.copy(alpha = 0.18f)
                                    else (if (darkTheme) DarkBorder else LightBorder)
                                )
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "${tab.count}",
                                style = HealthType.monoSmall,
                                color = labelColor,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Section header
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A section label, optionally with a subtitle and a trailing action.
 *
 * Replaces the hand-rolled `Text(..., letterSpacing = 1.4.sp)` that was repeated at every
 * section across four screens, each free to drift a little in size or tracking. One
 * definition means the sections actually line up as a system.
 */
@Composable
fun SectionHeader(
    title: String,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title.uppercase(),
                style = HealthType.sectionLabel,
                color = secondaryTextColor(darkTheme)
            )
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryTextColor(darkTheme)
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelMedium,
                color = healthColors(darkTheme).Connected,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }
    }
}
