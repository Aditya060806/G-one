package com.infinity.ai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val Dark = darkColorScheme(
    primary          = AccentGoldBg,
    onPrimary        = AccentGoldFg,
    background       = DarkBg,
    onBackground     = TextPrimary,
    surface          = DarkSurface,
    onSurface        = TextPrimary,
    surfaceVariant   = DarkSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline          = DarkBorder,
    outlineVariant   = DarkBorder,
    secondary        = AccentGoldBg,
    onSecondary      = AccentGoldFg,
    error            = DestructiveBg,
    onError          = DestructiveFg
)

private val Light = lightColorScheme(
    primary          = PrimaryBg,
    onPrimary        = PrimaryFg,
    background       = BaseBg,
    onBackground     = BaseFg,
    surface          = CardBg,
    onSurface        = CardFg,
    surfaceVariant   = SecondaryBg,
    onSurfaceVariant = MutedFg,
    outline          = TokenBorder,
    outlineVariant   = TokenBorder,
    secondary        = SecondaryBg,
    onSecondary      = SecondaryFg,
    error            = DestructiveBg,
    onError          = DestructiveFg
)

@Composable
fun GoneTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) Dark else Light,
        typography = Typography,
        content = content
    )
}
