package com.gone.ai.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gone.ai.ui.components.GradientBackground
import com.gone.ai.ui.theme.Blue500
import com.gone.ai.ui.theme.TextPrimary
import com.gone.ai.ui.theme.TextPrimaryLight
import com.gone.ai.ui.theme.TextSecondary
import com.gone.ai.ui.theme.TextSecondaryLight
import kotlinx.coroutines.delay

/**
 * Launch screen.
 *
 * Was a static ∞ glyph on a hardcoded dark background — wrong mark for a health app and
 * wrong background for a light-first one. Now it draws an ECG trace on, which does two
 * useful things beyond looking better: it tells the user what kind of app this is before
 * a single word is read, and it gives the model-extraction wait something honest to sit
 * behind on first launch.
 *
 * The trace is drawn with [PathMeasure.getSegment] rather than animated dash phase,
 * because a segment reveal genuinely draws the line on once instead of looping a dash
 * pattern along a line that is already fully visible.
 */
@Composable
fun SplashScreen(isDarkTheme: Boolean, onNavigate: () -> Unit) {
    val trace = remember { Animatable(0f) }
    val fade  = remember { Animatable(0f) }

    // The trace finishes before the wordmark starts, rather than both running together —
    // staggering them makes the launch feel authored instead of merely animated.
    LaunchedEffect(Unit) {
        trace.animateTo(1f, tween(1000, easing = EaseOutCubic))
        fade.animateTo(1f, tween(420, easing = EaseOut))
        delay(650)
        onNavigate()
    }

    // Expanding rings behind the mark. Kept very low alpha — this is a launch screen, not
    // a light show, and it is on screen for barely two seconds.
    val ring by rememberInfiniteTransition(label = "ring").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ringProgress"
    )

    GradientBackground(darkTheme = isDarkTheme, modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {

                Box(contentAlignment = Alignment.Center) {

                    Canvas(modifier = Modifier.size(190.dp)) {
                        val r = size.minDimension / 2f
                        // Two rings, offset in phase so there is always one mid-flight.
                        listOf(ring, (ring + 0.5f) % 1f).forEach { p ->
                            drawCircle(
                                color = Blue500.copy(alpha = 0.22f * (1f - p)),
                                radius = r * (0.45f + 0.55f * p),
                                style = Stroke(width = 1.5.dp.toPx())
                            )
                        }
                    }

                    Canvas(modifier = Modifier.width(150.dp).height(74.dp)) {
                        val full = ecgPath(size.width, size.height)
                        val measure = PathMeasure().apply { setPath(full, false) }
                        val shown = Path()
                        measure.getSegment(0f, measure.length * trace.value, shown, true)

                        drawPath(
                            path = shown,
                            color = Blue500,
                            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                        )

                        // A dot riding the leading edge of the trace, so the eye has
                        // something to follow rather than watching a line lengthen.
                        if (trace.value in 0.02f..0.995f) {
                            val pos = measure.getPosition(measure.length * trace.value)
                            drawCircle(Blue500, radius = 4.dp.toPx(), center = pos)
                            drawCircle(
                                Blue500.copy(alpha = 0.25f),
                                radius = 10.dp.toPx(),
                                center = pos
                            )
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                Text(
                    "G-one",
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                    color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                    modifier = Modifier.alpha(fade.value)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Personal health companion",
                    fontSize = 13.sp,
                    color = if (isDarkTheme) TextSecondary else TextSecondaryLight,
                    modifier = Modifier.alpha(fade.value * 0.9f)
                )

                Spacer(Modifier.height(28.dp))
                Box(
                    modifier = Modifier.alpha(fade.value),
                    contentAlignment = Alignment.Center
                ) {
                    com.gone.ai.ui.components.LoadingLottieAnimation(
                        modifier = Modifier.size(72.dp)
                    )
                }
            }
        }
    }
}

/**
 * A single idealised heartbeat: baseline, P wave, QRS complex, T wave, baseline.
 *
 * Proportional to the canvas so it scales with the layout. The QRS spike is deliberately
 * the tallest feature by a wide margin, which is what makes the shape read as an ECG
 * rather than as a generic zigzag.
 */
private fun ecgPath(w: Float, h: Float): Path {
    val mid = h / 2f
    return Path().apply {
        moveTo(0f, mid)
        lineTo(w * 0.26f, mid)
        // P wave
        lineTo(w * 0.31f, mid - h * 0.11f)
        lineTo(w * 0.36f, mid)
        // QRS complex
        lineTo(w * 0.42f, mid + h * 0.15f)
        lineTo(w * 0.50f, mid - h * 0.45f)
        lineTo(w * 0.57f, mid + h * 0.28f)
        lineTo(w * 0.62f, mid)
        // T wave
        lineTo(w * 0.72f, mid - h * 0.15f)
        lineTo(w * 0.80f, mid)
        lineTo(w, mid)
    }
}
