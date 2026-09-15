package com.infinity.ai.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infinity.ai.ai.state.AIInferenceState
import com.infinity.ai.ui.components.*
import com.infinity.ai.ui.theme.*

@Composable
fun VoiceScreen(
    isDarkTheme: Boolean,
    orbState: OrbState,
    aiState: AIInferenceState,
    onSetListening: () -> Unit,
    onSetIdle: () -> Unit,
    onDismiss: () -> Unit
) {
    val dark = isDarkTheme
    val isListening = aiState is AIInferenceState.Thinking || aiState is AIInferenceState.Responding
    val transcript  = if (aiState is AIInferenceState.Responding) aiState.partialText else ""

    val bgModifier = if (dark) {
        Modifier.background(ModernBgDark)
    } else {
        Modifier.background(ModernBgLight)
    }

    Box(modifier = Modifier.fillMaxSize().then(bgModifier)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(20.dp))

            // ── Top bar ───────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .shadow(
                            elevation = if (dark) 0.dp else 3.dp,
                            shape = CircleShape,
                            ambientColor = LightShadow,
                            spotColor = LightShadow
                        )
                        .clip(CircleShape)
                        .background(if (dark) ModernCardDark else ModernCardLight)
                        .border(
                            1.dp,
                            if (dark) ModernBorderDark else ModernBorderLight,
                            CircleShape
                        )
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = if (dark) TextSecondary else TextSecondaryLight,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Icon(
                    Icons.Default.MonitorHeart,
                    contentDescription = null,
                    tint = ModernBlue,
                    modifier = Modifier.size(24.dp)
                )

                AnimatedVisibility(visible = aiState is AIInferenceState.Responding) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .shadow(
                                elevation = if (dark) 0.dp else 2.dp,
                                shape = RoundedCornerShape(24.dp),
                                ambientColor = LightShadow,
                                spotColor = LightShadow
                            )
                            .clip(RoundedCornerShape(24.dp))
                            .background(if (dark) ModernCardDark else ModernCardLight)
                            .border(
                                1.dp,
                                if (dark) ModernBorderDark else ModernBorderLight,
                                RoundedCornerShape(24.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            "Speaking",
                            style = MaterialTheme.typography.labelSmall,
                            color = ModernBlue,
                            fontWeight = FontWeight.SemiBold
                        )
                        WaveformAnimation(
                            isActive = true,
                            modifier = Modifier.height(16.dp).width(44.dp),
                            color = ModernBlue
                        )
                    }
                }
                if (aiState !is AIInferenceState.Responding) {
                    Spacer(Modifier.size(40.dp))
                }
            }

            Spacer(Modifier.weight(0.25f))

            AiBodyOrb(orbState = orbState, isDarkTheme = dark, size = 220.dp)

            Spacer(Modifier.height(28.dp))

            // ── Status label ──────────────────────────────────────────
            AnimatedContent(
                targetState = aiState,
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                label = "voiceStatus"
            ) { state ->
                Text(
                    text = when (state) {
                        is AIInferenceState.Idle       -> "Tap mic to speak"
                        is AIInferenceState.Loading    -> "Loading model..."
                        is AIInferenceState.Thinking   -> "Processing..."
                        is AIInferenceState.Responding -> "G-one is responding"
                        is AIInferenceState.Error      -> "Something went wrong"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = when (state) {
                        is AIInferenceState.Error -> ErrorRed
                        is AIInferenceState.Idle  -> if (dark) TextSecondary else TextSecondaryLight
                        else -> ModernBlue
                    },
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── Transcript card ───────────────────────────────────────
            AnimatedVisibility(
                visible = transcript.isNotEmpty(),
                enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 3 },
                exit  = fadeOut(tween(300))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(
                            elevation = if (dark) 0.dp else 6.dp,
                            shape = RoundedCornerShape(24.dp),
                            ambientColor = LightShadow,
                            spotColor = LightShadow
                        )
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (dark) ModernCardDark else ModernCardLight)
                        .border(
                            1.dp,
                            if (dark) ModernBorderDark else ModernBorderLight,
                            RoundedCornerShape(24.dp)
                        )
                        .padding(20.dp)
                ) {
                    Text(
                        text = transcript,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (dark) TextPrimary else TextPrimaryLight,
                        textAlign = TextAlign.Center,
                        lineHeight = 26.sp
                    )
                }
            }

            Spacer(Modifier.weight(0.35f))

            // ── Bottom controls ───────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 28.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ControlButton(
                    icon = Icons.Default.Close,
                    isDarkTheme = dark,
                    tint = if (dark) TextSecondary else TextSecondaryLight,
                    onClick = onSetIdle
                )

                Box(
                    modifier = Modifier.width(120.dp).height(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isListening) {
                        WaveformAnimation(
                            isActive = true,
                            modifier = Modifier.fillMaxSize(),
                            color = ModernBlue
                        )
                    }
                }

                val micSrc = remember { MutableInteractionSource() }
                val micPressed by micSrc.collectIsPressedAsState()
                val micScale by animateFloatAsState(
                    if (micPressed) 0.94f else 1f,
                    spring(Spring.DampingRatioMediumBouncy),
                    label = "micScale"
                )

                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .scale(micScale)
                        .shadow(
                            elevation = if (dark) 0.dp else 6.dp,
                            shape = CircleShape,
                            ambientColor = LightShadow,
                            spotColor = LightShadow
                        )
                        .clip(CircleShape)
                        .background(
                            if (isListening)
                                Brush.linearGradient(listOf(ModernBlue, Color(0xFF6366F1)))
                            else
                                Brush.linearGradient(listOf(
                                    if (dark) ModernCardDark else ModernCardLight,
                                    if (dark) Color(0xFF1E293B) else Color(0xFFF8FAFC)
                                ))
                        )
                        .border(
                            1.dp,
                            if (!isListening)
                                (if (dark) ModernBorderDark else ModernBorderLight)
                            else Color.Transparent,
                            CircleShape
                        )
                        .clickable(
                            interactionSource = micSrc,
                            indication = null
                        ) {
                            if (isListening) onSetIdle() else onSetListening()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isListening) Icons.Default.Pause else Icons.Default.Mic,
                        contentDescription = if (isListening) "Pause" else "Speak",
                        tint = if (isListening) Color.White else ModernBlue,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isDarkTheme: Boolean,
    tint: Color,
    onClick: () -> Unit
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.94f else 1f,
        spring(Spring.DampingRatioMediumBouncy),
        label = "ctrlBtn"
    )

    Box(
        modifier = Modifier
            .size(48.dp)
            .scale(scale)
            .shadow(
                elevation = if (isDarkTheme) 0.dp else 3.dp,
                shape = CircleShape,
                ambientColor = LightShadow,
                spotColor = LightShadow
            )
            .clip(CircleShape)
            .background(if (isDarkTheme) ModernCardDark else ModernCardLight)
            .border(
                1.dp,
                if (isDarkTheme) ModernBorderDark else ModernBorderLight,
                CircleShape
            )
            .clickable(
                interactionSource = src,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}
