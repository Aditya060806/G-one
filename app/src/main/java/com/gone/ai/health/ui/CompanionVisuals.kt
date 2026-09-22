package com.gone.ai.health.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

internal fun companionAccent(dark: Boolean) = if (dark) Color(0xFFFFD061) else Color(0xFF986000)
private fun companionTrack(dark: Boolean) = if (dark) Color(0xFF383838) else Color(0xFFEDEAE3)

/** Start when the graphic scrolls into view, not while Home composes it below the fold. */
@Composable
private fun visualVisibility(): Pair<Boolean, Modifier> {
    var visible by remember { mutableStateOf(false) }
    val view = LocalView.current
    return visible to Modifier.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        visible = bounds.height > 40f && bounds.width > 0f && bounds.bottom > 0f && bounds.top < view.height
    }
}

@Composable
private fun animatedReveal(target: Float, visible: Boolean): Float {
    val value by animateFloatAsState(if (visible) target else 0f, tween(850, easing = FastOutSlowInEasing), label = "Recorded value reveal")
    return value
}

@Composable
internal fun AqiDial(value: Int?, dark: Boolean) {
    val (visible, visibility) = visualVisibility()
    val amount = animatedReveal((value?.toFloat()?.div(500f) ?: 0f).coerceIn(0f, 1f), visible)
    val track = companionTrack(dark)
    val accent = when {
        value == null -> track
        value > 200 -> Color(0xFFB15A67)
        value > 100 -> if (dark) Color(0xFFFFB578) else Color(0xFFB76329)
        else -> companionAccent(dark)
    }
    Box(Modifier.fillMaxWidth().height(190.dp).then(visibility).clearAndSetSemantics {
        contentDescription = value?.let { "$it US AQI. Gauge scale zero to 500; values above 500 exceed the scale." } ?: "No air quality reading"
    }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(190.dp)) {
            val inset = 15.dp.toPx()
            val diameter = size.width - inset * 2
            val origin = Offset(inset, inset)
            val arcSize = Size(diameter, diameter)
            drawArc(track, 135f, 270f, false, origin, arcSize, style = Stroke(12.dp.toPx(), cap = StrokeCap.Round))
            // Small ticks provide a stable scale while the actual indicator eases into place.
            for (tick in 0..10) {
                val angle = Math.toRadians((135 + tick * 27).toDouble())
                val radius = diameter / 2 + 12.dp.toPx()
                val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                drawLine(track, center + direction * radius, center + direction * (radius + 4.dp.toPx()), 1.5.dp.toPx())
            }
            if (value != null) {
                drawArc(accent, 135f, (270f * amount).coerceAtLeast(0.5f), false, origin, arcSize, style = Stroke(12.dp.toPx(), cap = StrokeCap.Round))
                val angle = Math.toRadians((135 + 270 * amount).toDouble())
                val point = center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * (diameter / 2)
                drawCircle(accent, 8.dp.toPx(), point)
                drawCircle(if (dark) Color(0xFF222222) else Color.White, 3.dp.toPx(), point)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value?.toString() ?: "—", fontSize = 42.sp, fontWeight = FontWeight.Bold)
            Text("US AQI", style = MaterialTheme.typography.labelMedium, color = companionAccent(dark))
        }
        Row(Modifier.align(Alignment.BottomCenter).width(138.dp).padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0", style = MaterialTheme.typography.labelSmall)
            Text("500+", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun CompanionPill(text: String, dark: Boolean) {
    Text(text, color = companionAccent(dark), style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.background(companionAccent(dark).copy(alpha = 0.10f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
}

/** Seven real calendar slots. Missing entries get a dash, never a fabricated zero reading. */
@Composable
internal fun WellnessWeekBars(values: Map<LocalDate, Float>, today: LocalDate, unit: String, maxValue: Float, dark: Boolean) {
    val accent = companionAccent(dark)
    val track = companionTrack(dark)
    val (visible, visibility) = visualVisibility()
    val dates = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val upper = maxOf(maxValue, values.values.maxOrNull() ?: maxValue)
    Column(Modifier.fillMaxWidth().then(visibility).clearAndSetSemantics {
        contentDescription = dates.joinToString("; ") { day -> "$day: ${values[day]?.let { String.format(Locale.US, "%.1f %s", it, unit) } ?: "not logged"}" }
    }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            dates.forEach { date ->
                val value = values[date]
                val fraction = animatedReveal(((value ?: 0f) / upper).coerceIn(0f, 1f), visible)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(value?.let { if (unit == "hours") String.format(Locale.US, "%.1f", it) else it.toInt().toString() } ?: "—", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(6.dp))
                    Canvas(Modifier.fillMaxWidth().height(78.dp)) {
                        val width = size.width.coerceAtMost(20.dp.toPx())
                        val left = (size.width - width) / 2
                        if (value == null) {
                            drawLine(track, Offset(left, size.height - 2.dp.toPx()), Offset(left + width, size.height - 2.dp.toPx()), 3.dp.toPx(), cap = StrokeCap.Round)
                        } else {
                            val height = (size.height * fraction).coerceAtLeast(3.dp.toPx())
                            drawRoundRect(track, Offset(left, 0f), Size(width, size.height), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
                            drawRoundRect(if (date == today) accent else accent.copy(alpha = 0.55f), Offset(left, size.height - height), Size(width, height), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (date == today) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("Last 7 days · $unit · — not logged", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun StressLevelVisual(level: Int?, dark: Boolean) {
    val (visible, visibility) = visualVisibility()
    val progress = animatedReveal(level?.toFloat() ?: 0f, visible)
    val track = companionTrack(dark)
    val accent = companionAccent(dark)
    Column(Modifier.then(visibility).clearAndSetSemantics { contentDescription = level?.let { "Self-reported stress $it out of 5" } ?: "No stress check-in today" }) {
        Canvas(Modifier.fillMaxWidth().height(64.dp)) {
            val gap = 8.dp.toPx()
            val width = (size.width - gap * 4) / 5
            for (i in 0..4) {
                val height = size.height * (0.42f + i * 0.145f)
                val offset = Offset(i * (width + gap), size.height - height)
                val rect = Size(width, height)
                val radius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx())
                drawRoundRect(track, offset, rect, radius)
                val fill = (progress - i).coerceIn(0f, 1f)
                if (fill > 0f) drawRoundRect(accent.copy(alpha = 0.40f + 0.12f * i), Offset(offset.x, size.height - height * fill), Size(width, height * fill), radius)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("1 · Very low", style = MaterialTheme.typography.labelSmall)
            Text("5 · Very high", style = MaterialTheme.typography.labelSmall)
        }
    }
}
