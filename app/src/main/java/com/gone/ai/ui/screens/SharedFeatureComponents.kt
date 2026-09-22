package com.gone.ai.ui.screens

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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.gone.ai.ocr.ResultStatus
import com.gone.ai.ocr.TaskStatus
import com.gone.ai.ocr.isRunning
import com.gone.ai.ui.components.MarkdownBlocks
import com.gone.ai.ui.components.MarkdownText
import com.gone.ai.ui.components.TextActions
import com.gone.ai.ui.components.BreadcrumbItem
import com.gone.ai.ui.components.HeaderActionPill
import com.gone.ai.ui.components.PureBreadcrumbText
import com.gone.ai.ui.theme.*

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
            isDarkTheme = isDarkTheme,
            // The breadcrumb gives way, so the action beside it keeps its label on one line.
            modifier = Modifier.weight(1f, fill = false)
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
                        label = "Start over",
                        darkTheme = isDarkTheme,
                        onClick = onReset
                    )
                }
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
            .clickable(role = Role.Button,
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

// ── Document result ────────────────────────────────────────────────────────────

/** What a finished answer offers. Copy is always there. */
data class ResultActions(
    /** Subject line when the answer is shared. */
    val shareSubject: String,
    /** Save to the Vault; null when this tool does not save. */
    val onSave: (() -> Unit)? = null,
    /** Open chat with this answer attached. */
    val onAskAssistant: (() -> Unit)? = null
)

/**
 * The answer area every document tool shares: streaming text, how it ended, and what can
 * be done with it. A failure before any text replaces the card with a retry.
 */
@Composable
fun DocumentResultSection(
    text: String,
    status: TaskStatus,
    saved: Boolean,
    isDarkTheme: Boolean,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    actions: ResultActions,
    modifier: Modifier = Modifier
) {
    val scroll = rememberScrollState()
    val isStreaming = status.isRunning
    LaunchedEffect(text.length) { if (isStreaming) scroll.animateScrollTo(scroll.maxValue) }

    when {
        status is TaskStatus.Failed && text.isBlank() ->
            InlineErrorCard(message = status.message, isDarkTheme = isDarkTheme, onRetry = onRetry, modifier = modifier)
        text.isBlank() && !isStreaming -> Unit
        else -> StreamingResultCard(
            text = text,
            status = status,
            saved = saved,
            isDarkTheme = isDarkTheme,
            scrollState = scroll,
            onStop = onStop,
            actions = actions,
            modifier = modifier
        )
    }
}

@Composable
fun StreamingResultCard(
    text          : String,
    status        : TaskStatus,
    saved         : Boolean,
    isDarkTheme   : Boolean,
    scrollState   : ScrollState,
    onStop        : () -> Unit,
    actions       : ResultActions,
    modifier      : Modifier = Modifier
) {
    val isStreaming = status.isRunning
    val (label, labelColor) = when (status) {
        is TaskStatus.Preparing -> (status.stage ?: "Reading the text…") to ModernBlue
        is TaskStatus.Streaming -> (status.stage?.let { "$it…" } ?: "Writing…") to ModernBlue
        is TaskStatus.Finished -> when (status.result) {
            ResultStatus.DONE -> "Complete" to SuccessGreen
            ResultStatus.PARTIAL -> "Cut short: the model stopped before finishing" to WarnAmber
            ResultStatus.STOPPED -> "Stopped" to WarnAmber
        }
        is TaskStatus.Failed -> "Stopped by an error: ${status.message}" to ErrorRed
        TaskStatus.Idle -> "" to ModernBlue
    }

    Column(modifier = modifier) {
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
                Box(modifier = Modifier.size(6.dp).background(labelColor.copy(a), CircleShape))
            } else {
                Box(modifier = Modifier.size(6.dp).background(labelColor, CircleShape))
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (labelColor == ModernBlue && isDarkTheme) TextPrimary else labelColor,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            if (isStreaming) {
                val stopInteraction = remember { MutableInteractionSource() }
                val haptic = LocalHapticFeedback.current
                Box(
                    modifier = Modifier
                        .pressScale(stopInteraction, pressedScale = 0.96f)
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(ErrorRed.copy(0.12f))
                        .clickable(
                            interactionSource = stopInteraction,
                            indication = null,
                            role = Role.Button,
                            onClickLabel = "Stop writing",
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onStop()
                            }
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.Stop, null, tint = ErrorRed, modifier = Modifier.size(14.dp))
                        Text("Stop", style = MaterialTheme.typography.labelMedium, color = ErrorRed, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
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
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(scrollState)) {
                if (text.isEmpty() && isStreaming) {
                    val inf = rememberInfiniteTransition(label = "typing")
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.semantics { contentDescription = "Writing" }
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
                } else if (isStreaming) {
                    MarkdownText(text = text, isDarkTheme = isDarkTheme, isStreaming = true, fontSize = 14.5.sp)
                } else {
                    SelectionContainer {
                        MarkdownText(text = text, isDarkTheme = isDarkTheme, fontSize = 14.5.sp)
                    }
                }
            }
        }

        if (!isStreaming && text.isNotBlank()) {
            ResultActionsRow(text = text, saved = saved, isDarkTheme = isDarkTheme, actions = actions)
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Copy, share, save and "Ask G-one" for a finished answer. */
@Composable
internal fun ResultActionsRow(text: String, saved: Boolean, isDarkTheme: Boolean, actions: ResultActions) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ResultActionPill(Icons.Default.ContentCopy, "Copy", isDarkTheme) {
            TextActions.copy(context, actions.shareSubject, MarkdownBlocks.plainText(text))
        }
        ResultActionPill(Icons.Default.Share, "Share", isDarkTheme) {
            TextActions.share(context, actions.shareSubject, MarkdownBlocks.plainText(text))
        }
        actions.onSave?.let { save ->
            if (saved) {
                ResultActionPill(Icons.Default.CheckCircle, "Saved to Vault", isDarkTheme, enabled = false) {}
            } else {
                ResultActionPill(Icons.Default.BookmarkAdd, "Save to Vault", isDarkTheme, onClick = save)
            }
        }
        actions.onAskAssistant?.let { ask ->
            ResultActionPill(Icons.AutoMirrored.Filled.Chat, "Ask G-one", isDarkTheme, onClick = ask)
        }
    }
}

@Composable
private fun ResultActionPill(
    icon: ImageVector,
    label: String,
    isDarkTheme: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val tint = when {
        !enabled -> SuccessGreen
        isDarkTheme -> TextPrimary
        else -> TextPrimaryLight
    }
    Row(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (isDarkTheme) ModernCardDark else ModernCardLight)
            .border(1.dp, if (isDarkTheme) ModernBorderDark else ModernBorderLight, RoundedCornerShape(22.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun InlineErrorCard(message: String, isDarkTheme: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(ErrorRed.copy(alpha = 0.08f))
            .border(1.dp, ErrorRed.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(18.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isDarkTheme) TextPrimary else TextPrimaryLight
            )
        }
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Try again", fontWeight = FontWeight.SemiBold)
        }
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
            com.gone.ai.ui.components.WatchScanningLottieAnimation(
                modifier = Modifier.size(140.dp)
            )
        } else {
            com.gone.ai.ui.components.LoadingLottieAnimation(
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
                .clickable(role = Role.Button,
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
