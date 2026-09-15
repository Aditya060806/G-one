package com.infinity.ai.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infinity.ai.ui.components.BreadcrumbHeader
import com.infinity.ai.ui.components.BreadcrumbItem
import com.infinity.ai.ui.components.HeaderActionPill
import com.infinity.ai.ui.components.PureBreadcrumbText
import com.infinity.ai.ui.theme.*

// ── Shared header used by OCR, Screenshot, Quiz screens ──────────────────────

@Composable
fun FeatureHeader(
    title          : String,
    isDarkTheme    : Boolean,
    uiState        : String,
    dotColor       : Color,
    onBack         : () -> Unit,
    onNavigateHome : () -> Unit = {},
    showReset      : Boolean,
    onReset        : () -> Unit,
    isScrolled     : Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Breadcrumb: Home / Tools / Sub-Feature (Sticky, background-less, border-less text)
        PureBreadcrumbText(
            items = listOf(
                BreadcrumbItem("Home", onNavigateHome),
                BreadcrumbItem("Tools", onBack),
                BreadcrumbItem(title)
            ),
            isDarkTheme = isDarkTheme
        )

        AnimatedVisibility(
            visible = !isScrolled,
            enter = fadeIn(tween(180)) + expandHorizontally(),
            exit = fadeOut(tween(140)) + shrinkHorizontally()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (showReset) {
                    HeaderActionPill(
                        icon = Icons.Default.Refresh,
                        label = "Reset",
                        darkTheme = isDarkTheme,
                        onClick = onReset
                    )
                }
                HeaderActionPill(
                    icon = Icons.Default.Settings,
                    label = "Setup",
                    darkTheme = isDarkTheme,
                    onClick = onBack
                )
            }
        }
    }
}

// ── Shared pick button ────────────────────────────────────────────────────────

@Composable
fun FeatureHeroIdleCard(
    title: String,
    subtitle: String,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier,
    lottieContent: @Composable () -> Unit,
    actionContent: @Composable () -> Unit,
    noteContent: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (isDarkTheme) 0.dp else 4.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = LightShadow,
                spotColor = LightShadow
            )
            .clip(RoundedCornerShape(24.dp))
            .background(if (isDarkTheme) ModernCardDark else ModernCardLight)
            .border(
                1.dp,
                if (isDarkTheme) ModernBorderDark else ModernBorderLight,
                RoundedCornerShape(24.dp)
            )
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(130.dp)
                .clip(CircleShape)
                .background(if (isDarkTheme) Color(0xFF1E293B).copy(alpha = 0.5f) else ModernBlueSubtle.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            lottieContent()
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isDarkTheme) TextSecondary else TextSecondaryLight,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )

        Spacer(Modifier.height(24.dp))

        actionContent()

        if (noteContent != null) {
            Spacer(Modifier.height(16.dp))
            noteContent()
        }
    }
}

@Composable
fun PickButton(
    label      : String,
    icon       : ImageVector,
    color      : Color,
    isDarkTheme: Boolean,
    modifier   : Modifier = Modifier,
    onClick    : () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    val isPressed by interaction.collectIsPressedAsState()
    val animatedElevation by animateDpAsState(
        targetValue = if (isPressed) 1.dp else (if (isDarkTheme) 0.dp else 4.dp),
        label = "pickElevation"
    )

    Column(
        modifier = modifier
            .pressScale(interaction, pressedScale = 0.96f)
            .shadow(
                elevation = animatedElevation,
                shape = RoundedCornerShape(20.dp),
                ambientColor = LightShadow,
                spotColor = LightShadow
            )
            .clip(RoundedCornerShape(20.dp))
            .background(if (isDarkTheme) Color(0xFF1E293B).copy(alpha = 0.6f) else Color.White)
            .border(
                1.dp,
                if (isDarkTheme) ModernBorderDark else ModernBorderLight,
                RoundedCornerShape(20.dp)
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            )
            .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(if (isDarkTheme) Color(0xFF282828) else ModernBlueSubtle, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
            fontWeight = FontWeight.Medium
        )
    }
}

// ── Shared streaming result card ──────────────────────────────────────────────

@Composable
fun StreamingResultCard(
    text          : String,
    isStreaming   : Boolean,
    isDarkTheme   : Boolean,
    scrollState   : ScrollState,
    onStop        : () -> Unit,
    modifier      : Modifier = Modifier,
    accentColor   : Color  = ModernBlue,
    streamingLabel: String = "Generating...",
    completeLabel : String = "Complete",
    completeColor : Color  = SuccessGreen
) {
    Column(modifier = modifier) {
        // Status row
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isStreaming) {
                val inf = rememberInfiniteTransition(label = "dot")
                val a by inf.animateFloat(
                    0.3f, 1f,
                    infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "a"
                )
                Box(modifier = Modifier.size(6.dp).background(accentColor.copy(a), CircleShape))
                Text(streamingLabel, style = MaterialTheme.typography.labelMedium, color = accentColor, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                val stopInteraction = remember { MutableInteractionSource() }
                val haptic = LocalHapticFeedback.current
                Box(
                    modifier = Modifier
                        .pressScale(stopInteraction, pressedScale = 0.96f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(ErrorRed.copy(0.12f))
                        .clickable(
                            interactionSource = stopInteraction,
                            indication = null,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onStop()
                            }
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.Stop, null, tint = ErrorRed, modifier = Modifier.size(14.dp))
                        Text("Stop", style = MaterialTheme.typography.labelMedium, color = ErrorRed, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Box(modifier = Modifier.size(6.dp).background(completeColor, CircleShape))
                Text(completeLabel, style = MaterialTheme.typography.labelMedium, color = completeColor, fontWeight = FontWeight.Medium)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .shadow(
                    elevation = if (isDarkTheme) 0.dp else 4.dp,
                    shape = RoundedCornerShape(24.dp),
                    ambientColor = LightShadow,
                    spotColor = LightShadow
                )
                .clip(RoundedCornerShape(24.dp))
                .background(if (isDarkTheme) ModernCardDark else ModernCardLight)
                .border(
                    1.dp,
                    if (isDarkTheme) ModernBorderDark else ModernBorderLight,
                    RoundedCornerShape(24.dp)
                )
                .padding(20.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(scrollState)
            ) {
                if (text.isEmpty() && isStreaming) {
                    val inf = rememberInfiniteTransition(label = "typing")
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(3) { i ->
                            val scale by inf.animateFloat(
                                0.6f, 1f,
                                infiniteRepeatable(
                                    tween(400, delayMillis = i * 130, easing = EaseInOut),
                                    RepeatMode.Reverse
                                ),
                                label = "d$i"
                            )
                            Box(
                                modifier = Modifier
                                    .size((6 * scale).dp)
                                    .background(if (isDarkTheme) TextSecondary else TextSecondaryLight, CircleShape)
                            )
                        }
                    }
                } else {
                    Text(
                        if (isStreaming && text.isNotEmpty()) "$text▍" else text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                        lineHeight = 24.sp
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

// ── Shared centered spinner / animation loader ────────────────────────────────

@Composable
fun CenteredSpinner(
    label: String,
    color: Color = ModernBlue,
    isDarkTheme: Boolean = false,
    isScanning: Boolean = false
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (isScanning) {
            com.infinity.ai.ui.components.WatchScanningLottieAnimation(
                modifier = Modifier.size(140.dp)
            )
        } else {
            com.infinity.ai.ui.components.LoadingLottieAnimation(
                modifier = Modifier.size(110.dp)
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ── Saved-to-Library banner ─────────────────────────────────────────────────

@Composable
fun SavedBanner(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter   = fadeIn() + slideInVertically { -it },
        exit    = fadeOut() + slideOutVertically { -it }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(SuccessGreen.copy(alpha = 0.92f))
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.CheckCircle, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Text("Saved to Knowledge Vault", style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ── Shared error state ────────────────────────────────────────────────────────

@Composable
fun FeatureErrorState(isDarkTheme: Boolean, message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .background(ErrorRed.copy(0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.ErrorOutline, null, tint = ErrorRed, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Something went wrong",
            style = MaterialTheme.typography.titleLarge,
            color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isDarkTheme) TextSecondary else TextSecondaryLight,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )
        Spacer(Modifier.height(28.dp))
        val retryInteraction = remember { MutableInteractionSource() }
        val haptic = LocalHapticFeedback.current
        val isPressed by retryInteraction.collectIsPressedAsState()
        val retryElevation by animateDpAsState(
            targetValue = if (isPressed) 1.dp else (if (isDarkTheme) 0.dp else 4.dp),
            label = "retryElevation"
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .pressScale(retryInteraction, pressedScale = 0.96f)
                .shadow(
                    elevation = retryElevation,
                    shape = RoundedCornerShape(16.dp),
                    ambientColor = LightShadow,
                    spotColor = LightShadow
                )
                .clip(RoundedCornerShape(16.dp))
                .background(if (isDarkTheme) AccentGoldBg else ModernBlue)
                .clickable(
                    interactionSource = retryInteraction,
                    indication = null,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onRetry()
                    }
                )
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Refresh, null, tint = if (isDarkTheme) AccentGoldFg else Color.White, modifier = Modifier.size(20.dp))
                Text(
                    "Try Again",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isDarkTheme) AccentGoldFg else Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
