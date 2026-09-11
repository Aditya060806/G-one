package com.infinity.ai.ui.theme

import androidx.compose.ui.graphics.Color

// ── Dark backgrounds ───────────────────────────────────────────────────────────
val DarkBg              = Color(0xFF0A0E1A)
val DarkSurface         = Color(0xFF111827)
val DarkSurfaceElevated = Color(0xFF1A2235)
val DarkBorder          = Color(0xFF1E2D45)
val DarkGlass           = Color(0x18FFFFFF)

// ── Light backgrounds ──────────────────────────────────────────────────────────
// Carries a faint cool tint rather than being pure grey. Cards are pure white, so the
// tint is what separates a card from the page — on a flat #FFFFFF background a white
// card is invisible without a heavy border, which is what made the old light theme
// look like a wireframe.
val LightBg              = Color(0xFFF4F7FC)
val LightSurface         = Color(0xFFFFFFFF)
val LightSurfaceElevated = Color(0xFFFAFCFF)
val LightBorder          = Color(0xFFE3E9F4)
/** For emphasis and selected states, where [LightBorder] is too faint to register. */
val LightBorderStrong    = Color(0xFFC9D6EA)
val LightGlass           = Color(0xFAFFFFFF)
/**
 * Blue-tinted rather than neutral black.
 *
 * A pure-black shadow over a cool background reads as grey sludge. Tinting the shadow
 * toward the background hue is what makes soft elevation look intentional on light UI.
 */
val LightShadow          = Color(0x141B3A6B)

// ── Brand — professional blue ──────────────────────────────────────────────────
val Blue500      = Color(0xFF4F8CFF)   // primary
val Blue600      = Color(0xFF3A7BF7)   // pressed
val Blue400      = Color(0xFF6FA8FF)   // secondary / lighter
val Blue50       = Color(0xFFEEF4FF)   // tint background
val Blue100      = Color(0xFFDBEAFF)   // softer tint
val BlueAlpha12  = Color(0x1F4F8CFF)

// ── Light page gradient ───────────────────────────────────────────────────────
// Top-down, near-white to a slightly deeper tint. Subtle on purpose: enough to give the
// page depth and to stop long scrolls feeling like flat paper, not enough to compete
// with the content. The dark theme already had a three-stop gradient; light was a single
// flat fill, which is most of why the two themes felt unequal in quality.
val GradStart = Color(0xFFFFFFFF)
val GradMid   = Color(0xFFF4F7FC)
val GradEnd   = Color(0xFFE8EFFA)
val OrbColor1 = Color(0xFF93C5FD)
val OrbColor2 = Color(0xFF60A5FA)
val OrbColor3 = Color(0xFFBFDBFE)

// ── Text ───────────────────────────────────────────────────────────────────────
val TextPrimary        = Color(0xFFE8EDF5)   // dark-mode primary
val TextSecondary      = Color(0xFF7A8BA8)   // dark-mode secondary
val TextDisabled       = Color(0xFF3D4E65)
val TextPrimaryLight   = Color(0xFF0F172A)   // light-mode primary
val TextSecondaryLight = Color(0xFF64748B)   // light-mode secondary
val TextTertiary       = Color(0xFF94A3B8)   // extra subtle

// ── Status ─────────────────────────────────────────────────────────────────────
val SuccessGreen = Color(0xFF10B981)
val ErrorRed     = Color(0xFFEF4444)
val WarnAmber    = Color(0xFFF59E0B)
