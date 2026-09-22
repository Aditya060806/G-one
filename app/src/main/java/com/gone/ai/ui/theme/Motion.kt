package com.gone.ai.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Shared motion tokens.
 *
 * Lives in `ui.theme` rather than `health.ui` because navigation, chat and the tool
 * screens all need it, and none of those should have to depend on the health package to
 * animate a card.
 *
 * The point of centralising durations is consistency: when every surface picks its own
 * 300ms-ish number the app feels subtly unsynchronised, and that reads as low quality
 * even when no single screen looks wrong.
 */
object GoneMotion {

    /** Taps, toggles, colour swaps. Fast enough to feel like direct manipulation. */
    const val Quick = 180

    /** The default. Entrances, tab changes, most state transitions. */
    const val Medium = 320

    /** Chart draw-ins and anything crossing a large distance. */
    const val Slow = 620

    /** Delay between consecutive items in a staggered list entrance. */
    const val StaggerStep = 55

    /**
     * Decelerating curve for things entering the screen.
     *
     * Fast out of the gate then easing to rest, which is how physical objects arrive.
     * Standard Material emphasised-decelerate control points.
     */
    val EaseOutSoft: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Symmetric curve for elements moving between two on-screen positions. */
    val EaseInOutSoft: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    /** For sliding indicators and pills. Slight overshoot reads as responsive. */
    val SpringBouncy: AnimationSpec<Float> =
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)

    /** For press feedback. No overshoot — a button that wobbles feels broken. */
    val SpringSnappy: AnimationSpec<Float> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh)
}

/**
 * Scale-on-press feedback driven by an existing [InteractionSource].
 *
 * Takes the source rather than creating one so the caller's `clickable` and this modifier
 * observe the same interactions — creating a second source here would produce a modifier
 * that never animates, which is a genuinely easy mistake to ship.
 */
@Composable
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.97f
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = GoneMotion.SpringSnappy,
        label = "pressScale"
    )
    return this.scale(scale)
}

/**
 * Sweeping highlight for loading placeholders.
 *
 * NOTE ON STRUCTURE: the infinite transition is created unconditionally and only the
 * *drawing* is gated on [active]. Returning early before `rememberInfiniteTransition`
 * would change the number of remembered slots between recompositions when `active`
 * flips, which corrupts Compose's slot table. Cheap to always run, incorrect not to.
 */
@Composable
fun Modifier.shimmer(active: Boolean, darkTheme: Boolean): Modifier {
    val sweep by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1250, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerSweep"
    )

    val highlight = if (darkTheme) Color.White.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.65f)

    return this.drawWithContent {
        drawContent()
        if (!active) return@drawWithContent
        // Travel from fully off the left to fully off the right so there is no visible
        // jump when the animation restarts.
        val span = size.width * 0.6f
        val x = -span + sweep * (size.width + 2 * span)
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(Color.Transparent, highlight, Color.Transparent),
                start = Offset(x, 0f),
                end = Offset(x + span, size.height)
            )
        )
    }
}

/**
 * Fade-and-rise entrance, offset by [index] so a list arrives as a cascade.
 *
 * Deliberately capped: past roughly ten items the accumulated delay becomes a wait rather
 * than a flourish, so the stagger stops growing and later items simply arrive together.
 */
@Composable
fun StaggeredEntrance(
    index: Int,
    modifier: Modifier = Modifier,
    riseDp: Int = 16,
    content: @Composable () -> Unit
) {
    var shown by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay((index.coerceAtMost(10) * GoneMotion.StaggerStep).toLong())
        shown = true
    }

    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(GoneMotion.Medium, easing = GoneMotion.EaseOutSoft),
        label = "entrance$index"
    )

    Box(
        modifier = modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * riseDp.dp.toPx()
        }
    ) {
        content()
    }
}
