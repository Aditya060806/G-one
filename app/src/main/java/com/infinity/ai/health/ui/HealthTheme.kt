package com.infinity.ai.health.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.infinity.ai.health.domain.Severity
import com.infinity.ai.health.service.MonitoringState
import com.infinity.ai.ui.theme.Blue400
import com.infinity.ai.ui.theme.Blue500
import com.infinity.ai.ui.theme.TextSecondary
import com.infinity.ai.ui.theme.TextSecondaryLight

/**
 * Health-specific design tokens.
 *
 * Kept in the health package rather than `ui.theme` so the shared theme does not have to
 * depend on the health domain. Only the semantics of *severity* are added here.
 *
 * WHY THERE ARE TWO PALETTES: these colours were originally tuned against a near-black
 * background, where a bright saturated amber reads clearly. On the light theme the same
 * amber (#F59E0B) sits at roughly 2.1:1 against white — well under the 4.5:1 WCAG AA
 * threshold for text, and genuinely hard to read in daylight. That is not an aesthetic
 * complaint in an app whose whole job is conveying a number that might matter. Each role
 * therefore has a darker light-mode variant chosen to clear 4.5:1 on white while keeping
 * the same hue, so the colour still *means* the same thing.
 */
interface HealthPalette {
    /** In range. Nothing to do. */
    val Normal: Color

    /** Sustained-but-not-critical. Worth noticing, not worth alarm. */
    val Warning: Color

    /**
     * CRITICAL ONLY.
     *
     * THE COLOUR DISCIPLINE THAT MAKES THIS WORK: red appears nowhere else in the app —
     * not in icons, not in headers, not decoratively. If red only ever means "a real
     * critical health event", then the moment it appears it carries genuine weight. Spend
     * it on branding and users learn to scroll past it, which is precisely the failure
     * mode that gets someone hurt.
     */
    val Critical: Color

    /** Device connected / streaming. Also the general interactive accent. */
    val Connected: Color

    /**
     * Device disconnected. Deliberately a muted neutral, NOT amber or red — a wearable
     * out of range is not itself a health emergency, and colouring it like one would
     * cry wolf every time the user walks away from their phone.
     */
    val Disconnected: Color

    /**
     * System fault — Bluetooth lost, database failure, model crash.
     *
     * Desaturated and distinct from [Critical] on purpose. Conflating "the app broke"
     * with "your oxygen is dangerously low" in one visual language would be a real
     * design defect in a health app: the user cannot tell whether to seek help or
     * restart the app.
     */
    val SystemFault: Color

    /** Informational, below the warning band. */
    val Info: Color

    /** Mid risk band, between [Normal] and [Warning]. */
    val Caution: Color

    /** The analysing state. Distinct from every severity so it cannot be misread as one. */
    val Analysing: Color
}

/** Dark-mode palette mapped to the design system tokens. */
object HealthColors : HealthPalette {
    override val Normal       = Color(0xFF68D391)
    override val Warning      = com.infinity.ai.ui.theme.AccentGoldBg
    override val Critical     = com.infinity.ai.ui.theme.DestructiveBg
    override val Connected    = com.infinity.ai.ui.theme.AccentGoldBg
    override val Disconnected = com.infinity.ai.ui.theme.TextSecondary
    override val SystemFault  = com.infinity.ai.ui.theme.DestructiveBg
    override val Info         = com.infinity.ai.ui.theme.Chart5
    override val Caution      = com.infinity.ai.ui.theme.Chart3
    override val Analysing    = com.infinity.ai.ui.theme.AccentGoldBg
}

/**
 * Light-mode palette mapped directly to the design system tokens.
 */
object HealthColorsLight : HealthPalette {
    override val Normal       = com.infinity.ai.ui.theme.Chart3
    override val Warning      = com.infinity.ai.ui.theme.Chart4
    override val Critical     = com.infinity.ai.ui.theme.DestructiveBg
    override val Connected    = com.infinity.ai.ui.theme.ForestPrimary
    override val Disconnected = com.infinity.ai.ui.theme.MutedFg
    override val SystemFault  = com.infinity.ai.ui.theme.DestructiveBg.copy(alpha = 0.8f)
    override val Info         = com.infinity.ai.ui.theme.Chart5
    override val Caution      = com.infinity.ai.ui.theme.Chart2
    override val Analysing    = com.infinity.ai.ui.theme.Chart5
}

/** The palette for the current theme. */
fun healthColors(darkTheme: Boolean): HealthPalette =
    if (darkTheme) HealthColors else HealthColorsLight

/** Severity → colour. The only sanctioned mapping. */
fun severityColor(severity: Severity, darkTheme: Boolean): Color {
    val c = healthColors(darkTheme)
    return when (severity) {
        Severity.LOW      -> c.Info
        Severity.MODERATE -> c.Warning
        Severity.CRITICAL -> c.Critical
    }
}

/**
 * Colour for a 0–100 risk score.
 *
 * Bands rather than a gradient so the reading is categorical and unambiguous. Note the
 * top band stops short of [HealthPalette.Critical]: a high score is a *risk*, not a
 * confirmed critical event, and only a fired rule earns red.
 */
fun riskColor(score: Int, darkTheme: Boolean): Color {
    val c = healthColors(darkTheme)
    return when {
        score >= 70 -> c.Warning
        score >= 40 -> c.Caution
        else        -> c.Normal
    }
}

/**
 * Colour for the pipeline's own state.
 *
 * `ERROR` maps to [HealthPalette.SystemFault], never to [HealthPalette.Critical], keeping
 * "the app is broken" visually separate from "you have a health emergency".
 */
fun monitoringStateColor(state: MonitoringState, darkTheme: Boolean): Color {
    val c = healthColors(darkTheme)
    return when (state) {
        MonitoringState.IDLE             -> c.Disconnected
        MonitoringState.MONITORING       -> c.Normal
        MonitoringState.READING          -> c.Connected
        MonitoringState.ANALYZING        -> c.Analysing
        MonitoringState.ANOMALY_DETECTED -> c.Warning
        MonitoringState.AI_EXPLAINING    -> c.Connected
        MonitoringState.ERROR            -> c.SystemFault
    }
}

fun monitoringStateLabel(state: MonitoringState): String = when (state) {
    MonitoringState.IDLE             -> "Not monitoring"
    MonitoringState.MONITORING       -> "Monitoring"
    MonitoringState.READING          -> "Reading"
    MonitoringState.ANALYZING        -> "Analysing"
    MonitoringState.ANOMALY_DETECTED -> "Anomaly detected"
    MonitoringState.AI_EXPLAINING    -> "Explaining"
    MonitoringState.ERROR            -> "System problem"
}

/**
 * Typography for live measurements.
 *
 * `fontFeatureSettings = "tnum"` selects tabular figures, so every digit occupies the
 * same advance width. Without it a heart rate ticking 78 → 111 physically reshuffles the
 * layout on almost every update, which reads as instability rather than as live data.
 * Small detail; it is most of the difference between "prototype" and "instrument".
 */
object HealthType {

    val vitalHero = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 38.sp,
        lineHeight = 42.sp,
        letterSpacing = (-1).sp,
        fontFeatureSettings = "tnum"
    )

    val vitalLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.6).sp,
        fontFeatureSettings = "tnum"
    )

    val vitalMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.3).sp,
        fontFeatureSettings = "tnum"
    )

    /** Timestamps and counters, where alignment matters more than size. */
    val monoSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontFamily = FontFamily.Monospace
    )

    /** Section headers. Wide tracking so short all-caps labels read as structure. */
    val sectionLabel = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.4.sp
    )
}

/** Secondary text colour for the current theme. */
@Composable
@ReadOnlyComposable
fun secondaryTextColor(darkTheme: Boolean): Color =
    if (darkTheme) TextSecondary else TextSecondaryLight

/** Surface colour that works on both themes without a conditional at every call site. */
@Composable
@ReadOnlyComposable
fun healthSurface(): Color = MaterialTheme.colorScheme.surface
