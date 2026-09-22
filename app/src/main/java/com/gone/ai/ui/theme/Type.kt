package com.gone.ai.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── Mandatory Typography Families ─────────────────────────────────────────────
// Sans-Serif: Sora
val FontSans = FontFamily.SansSerif
// Serif: Georgia
val FontSerif = FontFamily.Serif
// Mono: Cascadia Code
val FontMono = FontFamily.Monospace

val Typography = Typography(
    headlineLarge  = TextStyle(fontFamily = FontSerif, fontWeight = FontWeight.Bold,     fontSize = 32.sp, lineHeight = 40.sp,  letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontFamily = FontSerif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 34.sp,  letterSpacing = (-0.5).sp),
    headlineSmall  = TextStyle(fontFamily = FontSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp,  letterSpacing = (-0.3).sp),
    titleLarge     = TextStyle(fontFamily = FontSans,  fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp,  letterSpacing = (-0.2).sp),
    titleMedium    = TextStyle(fontFamily = FontSans,  fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp,  letterSpacing = (-0.1).sp),
    titleSmall     = TextStyle(fontFamily = FontSans,  fontWeight = FontWeight.Medium,   fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge      = TextStyle(fontFamily = FontSans,  fontWeight = FontWeight.Normal,   fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium     = TextStyle(fontFamily = FontSans,  fontWeight = FontWeight.Normal,   fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall      = TextStyle(fontFamily = FontSans,  fontWeight = FontWeight.Normal,   fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge     = TextStyle(fontFamily = FontSans,  fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp,  letterSpacing = 0.1.sp),
    labelMedium    = TextStyle(fontFamily = FontSans,  fontWeight = FontWeight.Medium,   fontSize = 12.sp, lineHeight = 16.sp,  letterSpacing = 0.2.sp),
    labelSmall     = TextStyle(fontFamily = FontMono,  fontWeight = FontWeight.Medium,   fontSize = 11.sp, lineHeight = 16.sp,  letterSpacing = 0.6.sp)
)
