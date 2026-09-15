package com.infinity.ai.ui.theme

import androidx.compose.ui.graphics.Color

// ── Official Token Palette (Updated Charcoal/Cream/Amber/Stone System) ────────
// 1. PRIMARY
val PrimaryBg           = Color(0xFF2C2C2C)   // #2c2c2c
val PrimaryFg           = Color(0xFFF8F8F7)   // #f8f8f7

// 2. SECONDARY
val SecondaryBg         = Color(0xFFF5F5F0)   // #f5f5f0
val SecondaryFg         = Color(0xFF3D3D38)   // #3d3d38

// 3. ACCENT
val AccentGoldBg        = Color(0xFFFFD061)   // #ffd061
val AccentGoldFg        = Color(0xFF2C2C2C)   // #2c2c2c

// 4. BASE
val BaseBg              = Color(0xFFF8F8F7)   // #f8f8f7
val BaseFg              = Color(0xFF1D1D1B)   // #1d1d1b

// 5. CARD & POPOVER
val CardBg              = Color(0xFFFFFFFF)   // #ffffff
val CardFg              = Color(0xFF1D1D1B)   // #1d1d1b
val PopoverBg           = Color(0xFFFFFFFF)   // #ffffff
val PopoverFg           = Color(0xFF1D1D1B)   // #1d1d1b

// 6. MUTED
val MutedBg             = Color(0xFFF6F3EE)   // #f6f3ee
val MutedFg             = Color(0xFF6E6E68)   // #6e6e68

// 7. DESTRUCTIVE
val DestructiveBg       = Color(0xFFDB3837)   // #db3837
val DestructiveFg       = Color(0xFFFFFFFF)   // #ffffff

// 8. BORDER & INPUT
val TokenBorder         = Color(0xFFE7E4DD)   // #e7e4dd
val TokenInput          = Color(0xFFE7E4DD)   // #e7e4dd
val TokenRing           = Color(0xFFFFD061)   // #ffd061

// 9. CHARTS
val Chart1              = Color(0xFFFFD061)   // #ffd061
val Chart2              = Color(0xFF8D8D85)   // #8d8d85
val Chart3              = Color(0xFFD3A473)   // #d3a473
val Chart4              = Color(0xFF5B5B55)   // #5b5b55
val Chart5              = Color(0xFFE0A315)   // #e0a315

// 10. SIDEBAR / NAV
val SidebarBg           = Color(0xFFF5F5F0)   // #f5f5f0
val SidebarFg           = Color(0xFF1D1D1B)   // #1d1d1b
val SidebarPrimary      = Color(0xFF2C2C2C)   // #2c2c2c
val SidebarPrimaryFg    = Color(0xFFF8F8F7)   // #f8f8f7
val SidebarAccent       = Color(0xFFFFD061)   // #ffd061
val SidebarAccentFg     = Color(0xFF2C2C2C)   // #2c2c2c
val SidebarBorder       = Color(0xFFE7E4DD)   // #e7e4dd
val SidebarRing         = Color(0xFFFFD061)   // #ffd061

// ── Application Surfaces & Backward Compatible Aliases ───────────────────────
val DarkBg              = Color(0xFF141414)   // Deep Charcoal Night
val DarkSurface         = Color(0xFF1D1D1D)
val DarkSurfaceElevated = Color(0xFF262626)
val DarkBorder          = Color(0xFF383838)
val DarkGlass           = Color(0x18FFFFFF)

val LightBg              = BaseBg
val LightSurface         = CardBg
val LightSurfaceElevated = SecondaryBg
val LightBorder          = TokenBorder
val LightBorderStrong    = Color(0xFFCEC9BF)
val LightGlass           = Color(0xF2F8F8F7)
val LightShadow          = Color(0x142C2C2C)

// Brand
val Blue500      = PrimaryBg          // Charcoal Primary
val Blue600      = Color(0xFF1D1D1D)
val Blue400      = Chart5             // Vibrant Deep Amber Gold
val Blue50       = SecondaryBg
val Blue100      = MutedBg
val BlueAlpha12  = Color(0x1F2C2C2C)

// Page Gradients
val GradStart = BaseBg
val GradMid   = Color(0xFFF3F3EE)
val GradEnd   = SecondaryBg
val OrbColor1 = Color(0xFFFFE59E)
val OrbColor2 = Color(0xFFFFD061)
val OrbColor3 = Color(0xFFF5F5F0)

// Text
val TextPrimary        = Color(0xFFF8F8F7)
val TextSecondary      = Color(0xFFA8A8A0)
val TextDisabled       = Color(0xFF60605A)
val TextPrimaryLight   = BaseFg
val TextSecondaryLight = MutedFg
val TextTertiary       = Color(0xFF94948C)

// Status
val SuccessGreen = Chart3
val ErrorRed     = DestructiveBg
val WarnAmber    = Chart1

// Modern Design Tokens
val ModernBgLight      = BaseBg
val ModernBgDark       = Color(0xFF141414)
val ModernCardLight    = CardBg
val ModernCardDark     = Color(0xFF1E1E1E)
val ModernBorderLight  = TokenBorder
val ModernBorderDark   = Color(0xFF333333)
val ModernBlue         = PrimaryBg
val ModernBlueDark     = Color(0xFF1A1A1A)
val ModernBlueSubtle   = SecondaryBg
val TrendGreenBg       = SecondaryBg
val TrendGreenText     = PrimaryBg
val VitalRed           = DestructiveBg
val VitalRedSubtle     = Color(0xFFFBECEC)
val VitalCyan          = Chart5
val VitalCyanSubtle    = Color(0xFFFFF8E6)
val VitalOrange        = Chart1
val VitalOrangeSubtle  = MutedBg
val GraphDarkBar       = PrimaryBg
val GraphCoralBar      = DestructiveBg
val GraphMutedBar      = TokenBorder
val GraphDarkMutedBar  = Color(0xFF3D3D3D)
val FloatingNavBg      = PrimaryBg
val FloatingNavActive  = AccentGoldBg

// Backward compatibility aliases for tokens
val ForestPrimary       = PrimaryBg
val ForestPrimaryFg     = PrimaryFg
val ForestSecondaryBg   = SecondaryBg
val ForestSecondaryFg   = SecondaryFg
val AccentPeachBg       = AccentGoldBg
val AccentPeachFg       = AccentGoldFg
