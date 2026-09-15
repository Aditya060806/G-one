package com.infinity.ai.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infinity.ai.ui.navigation.Screen
import com.infinity.ai.ui.theme.AccentGoldBg
import com.infinity.ai.ui.theme.AccentGoldFg
import com.infinity.ai.ui.theme.ForestPrimary
import com.infinity.ai.ui.theme.FontSans
import com.infinity.ai.ui.theme.PrimaryFg
import com.infinity.ai.ui.theme.TokenBorder
import com.infinity.ai.ui.theme.pressScale

/**
 * Floating Island Navigation Dock.
 *
 * Features a continuous, fluid sliding pill indicator that glides across tabs
 * with spring physics, perfectly centering the active icon while smoothly
 * fading and blending labels in and out.
 */
@Composable
fun FloatingIslandNav(
    items: List<Screen>,
    currentRoute: String?,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedIndex = remember(currentRoute, items) {
        val idx = items.indexOfFirst { it.route == currentRoute }
        if (idx >= 0) idx else 0
    }

    val haptic = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(66.dp)
                .shadow(
                    elevation = 14.dp,
                    shape = RoundedCornerShape(36.dp),
                    ambientColor = ForestPrimary.copy(alpha = 0.25f),
                    spotColor = ForestPrimary.copy(alpha = 0.35f)
                )
                .clip(RoundedCornerShape(36.dp))
                .background(ForestPrimary)
                .border(1.dp, TokenBorder.copy(alpha = 0.3f), RoundedCornerShape(36.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            val tabCount = items.size
            val tabWidth = maxWidth / tabCount
            val pillWidth = 52.dp
            val pillHeight = 44.dp

            val targetPillX = tabWidth * selectedIndex + (tabWidth - pillWidth) / 2
            val animatedPillX by animateDpAsState(
                targetValue = targetPillX,
                animationSpec = spring(
                    dampingRatio = 0.74f,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "navPillX"
            )

            // Smooth gliding active pill indicator
            Box(
                modifier = Modifier
                    .offset(x = animatedPillX, y = (maxHeight - pillHeight) / 2)
                    .size(width = pillWidth, height = pillHeight)
                    .shadow(
                        elevation = 6.dp,
                        shape = RoundedCornerShape(22.dp),
                        ambientColor = AccentGoldBg.copy(alpha = 0.3f),
                        spotColor = AccentGoldBg.copy(alpha = 0.45f)
                    )
                    .clip(RoundedCornerShape(22.dp))
                    .background(AccentGoldBg)
            )

            // Navigation items row
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { index, screen ->
                    val isSelected = selectedIndex == index
                    FloatingNavItem(
                        screen = screen,
                        isSelected = isSelected,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onNavigate(screen)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingNavItem(
    screen: Screen,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }

    val iconColor by animateColorAsState(
        targetValue = if (isSelected) AccentGoldFg else PrimaryFg.copy(alpha = 0.7f),
        animationSpec = tween(220),
        label = "navIconColor"
    )

    val textColor by animateColorAsState(
        targetValue = PrimaryFg.copy(alpha = 0.7f),
        animationSpec = tween(220),
        label = "navTextColor"
    )

    val iconScale by animateFloatAsState(
        targetValue = if (isSelected) 1.15f else 1.0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium),
        label = "iconScale"
    )

    val iconOffsetY by animateDpAsState(
        targetValue = if (isSelected) 0.dp else (-5).dp,
        animationSpec = spring(dampingRatio = 0.76f, stiffness = Spring.StiffnessMedium),
        label = "iconOffsetY"
    )

    val textAlpha by animateFloatAsState(
        targetValue = if (isSelected) 0f else 1f,
        animationSpec = tween(durationMillis = 180),
        label = "textAlpha"
    )

    val textScale by animateFloatAsState(
        targetValue = if (isSelected) 0.75f else 1.0f,
        animationSpec = tween(durationMillis = 180),
        label = "textScale"
    )

    val textOffsetY by animateDpAsState(
        targetValue = if (isSelected) 4.dp else 0.dp,
        animationSpec = tween(durationMillis = 180),
        label = "textOffsetY"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = { if (!isSelected) onClick() }
            )
            .pressScale(interaction),
        contentAlignment = Alignment.Center
    ) {
        // Centered Icon that smoothly animates scale, color, and vertical position
        Icon(
            imageVector = screen.icon,
            contentDescription = screen.label,
            tint = iconColor,
            modifier = Modifier
                .offset(y = iconOffsetY)
                .scale(iconScale)
                .size(20.dp)
        )

        // Text label that smoothly fades and morphs out when active
        if (textAlpha > 0.01f) {
            Text(
                text = screen.label,
                color = textColor,
                fontSize = 10.5.sp,
                fontFamily = FontSans,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier
                    .offset(y = 12.dp + textOffsetY)
                    .scale(textScale)
                    .alpha(textAlpha)
            )
        }
    }
}
