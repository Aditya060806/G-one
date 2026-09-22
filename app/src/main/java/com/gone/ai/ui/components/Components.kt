package com.gone.ai.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gone.ai.ui.theme.*

@Composable
fun GradientBackground(
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val bgModifier = if (darkTheme)
        modifier.background(Brush.verticalGradient(listOf(Color(0xFF0A0E1A), DarkBg, Color(0xFF0C1120))))
    else
        modifier.background(Brush.verticalGradient(listOf(GradStart, GradMid, GradEnd)))
    Box(
        modifier = bgModifier,
        content  = content
    )
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    darkTheme: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .shadow(
                elevation = if (darkTheme) 0.dp else 4.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = LightShadow,
                spotColor = LightShadow
            )
            .clip(RoundedCornerShape(24.dp))
            .background(if (darkTheme) ModernCardDark else ModernCardLight)
            .border(1.dp, if (darkTheme) DarkBorder else LightBorder, RoundedCornerShape(24.dp))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(18.dp),
        content = content
    )
}

@Composable
fun WaveformAnimation(
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    color: Color = Blue500
) {
    if (!isActive) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(20) {
                Box(
                    modifier = Modifier
                        .width(2.5.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(color.copy(alpha = 0.35f))
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
        repeat(20) { i ->
            val height by inf.animateFloat(
                initialValue = 3f,
                targetValue = if (isActive) (5 + (i % 5) * 5).toFloat() else 3f,
                animationSpec = infiniteRepeatable(
                    tween(300 + i * 30, easing = EaseInOut),
                    RepeatMode.Reverse
                ),
                label = "bar$i"
            )
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height(height.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}

@Composable
fun AITaskCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    iconBg: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (darkTheme) DarkSurface else LightSurface)
            .border(1.dp, if (darkTheme) DarkBorder else LightBorder, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(Blue50, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Blue500, modifier = Modifier.size(19.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (darkTheme) TextPrimary else TextPrimaryLight,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(1.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (darkTheme) TextSecondary else TextSecondaryLight
            )
        }
        Icon(
            Icons.Default.ChevronRight, null,
            tint = Blue500.copy(alpha = 0.6f),
            modifier = Modifier.size(16.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Universal Breadcrumb Header ("Home / Feature / Sub-feature")
// ─────────────────────────────────────────────────────────────────────────────

data class BreadcrumbItem(
    val title: String,
    val onClick: (() -> Unit)? = null
)

/**
 * Background-less, border-less, simple text breadcrumb row.
 * Can be pinned sticky on top without container cards or borders.
 */
@Composable
fun PureBreadcrumbText(
    items: List<BreadcrumbItem>,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        items.forEachIndexed { index, item ->
            val isLast = index == items.size - 1
            if (item.onClick != null && !isLast) {
                val interaction = remember { MutableInteractionSource() }
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = ModernBlue,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .pressScale(interaction)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button,
                            interactionSource = interaction,
                            indication = null,
                            onClick = item.onClick
                        )
                        .padding(vertical = 4.dp, horizontal = 2.dp)
                )
            } else {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                    fontWeight = if (isLast) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.SemiBold,
                    fontSize = if (isLast) 19.sp else 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (isLast) Modifier.semantics { heading() } else Modifier
                )
            }

            if (!isLast) {
                Text(
                    text = "  /  ",
                    modifier = Modifier.clearAndSetSemantics { },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isDarkTheme) Color(0xFF64748B) else Color(0xFF94A3B8),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
fun BreadcrumbHeader(
    items: List<BreadcrumbItem>,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        PureBreadcrumbText(items = items, isDarkTheme = isDarkTheme)

        if (trailingContent != null) {
            Spacer(Modifier.width(8.dp))
            trailingContent()
        }
    }
}

/**
 * Standard Header Action Pill button used consistently across all screens in G-one.
 * Features rounded capsule styling, press animation, haptic feedback, icon, label, and badge.
 */
@Composable
fun HeaderActionPill(
    icon: ImageVector,
    label: String,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
    badgeCount: Int = 0,
    iconTint: Color? = null,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .pressScale(interaction, pressedScale = 0.95f)
            .shadow(
                elevation = if (darkTheme) 0.dp else 2.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = LightShadow,
                spotColor = LightShadow
            )
            .clip(RoundedCornerShape(16.dp))
            .background(if (darkTheme) ModernCardDark else ModernCardLight)
            .border(
                1.dp,
                if (badgeCount > 0) VitalRed.copy(alpha = 0.5f) else if (darkTheme) DarkBorder else LightBorder,
                RoundedCornerShape(16.dp)
            )
            .clickable(role = Role.Button, interactionSource = interaction, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .semantics(mergeDescendants = true) {
                if (badgeCount > 0) stateDescription = "$badgeCount active"
            }
            .padding(horizontal = 9.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint ?: (if (badgeCount > 0) VitalRed else if (darkTheme) AccentGoldBg else ModernBlue),
                    modifier = Modifier.size(17.dp)
                )
                if (badgeCount > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 3.dp, y = (-2).dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(VitalRed)
                    )
                }
            }
            Text(
                text = label,
                maxLines = 1,
                softWrap = false,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (badgeCount > 0) VitalRed else if (darkTheme) TextPrimary else TextPrimaryLight
            )
        }
    }
}
