package com.gone.ai.circle

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.automirrored.filled.Chat
import com.gone.ai.ocr.ResultStatus
import com.gone.ai.ocr.TaskStatus
import com.gone.ai.ocr.isRunning
import com.gone.ai.ui.components.MarkdownText

private val Blue500    = Color(0xFF3B82F6)
private val Purple500  = Color(0xFF8B5CF6)
private val Green500   = Color(0xFF10B981)
private val Amber500   = Color(0xFFF59E0B)
private val Red500     = Color(0xFFEF4444)
private val DarkBg     = Color(0xFF0F1115)
private val DarkSurface = Color(0xFF161A22)
private val DarkGlass  = Color(0x1AFFFFFF)

/**
 * CircleLearnBottomSheetHost
 *
 * Root composable for CircleLearnActivity after OCR completes.
 * Reacts to CircleUiState — shows the right panel for each state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CircleLearnBottomSheetHost(
    vm          : CircleLearnViewModel,
    onDismiss   : () -> Unit,
    onOpenInApp : ((String?) -> Unit)? = null,   // null when called from Activity (already in app)
    onAskInApp  : ((title: String, text: String) -> Unit)? = null
) {
    val uiState     by vm.uiState.collectAsState()
    val ocrText     by vm.ocrText.collectAsState()
    val detection   by vm.detection.collectAsState()
    val action      by vm.action.collectAsState()
    val resultText  by vm.task.output.collectAsState()
    val taskStatus  by vm.task.status.collectAsState()
    val resultSaved by vm.task.saved.collectAsState()
    val savedBanner by vm.savedToVault.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            // Absorbs touches outside the sheet without appearing as a control to TalkBack.
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        // Bottom sheet panel
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(DarkBg)
                .navigationBarsPadding()
        ) {
            // Drag handle
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(modifier = Modifier
                    .width(40.dp).height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(0.2f)))
            }

            when (val s = uiState) {
                is CircleUiState.Idle, is CircleUiState.Processing ->
                    ProcessingPanel()

                is CircleUiState.OcrDone -> {
                    val chosen = action
                    if (chosen == null) {
                        ActionPanel(
                            ocrText   = ocrText,
                            detection = detection,
                            onAction  = { vm.runAction(it) },
                            onDismiss = onDismiss
                        )
                    } else {
                        ResultPanel(
                            resultText   = resultText,
                            actionLabel  = chosen.label,
                            status       = taskStatus,
                            saved        = resultSaved,
                            onStop       = { vm.stop() },
                            onSave       = { vm.saveResult() },
                            onRetry      = { vm.retry() },
                            onDismiss    = onDismiss,
                            onOpenInApp  = onOpenInApp,
                            onAskInApp   = onAskInApp?.let { ask -> { ask("Circle Learn: ${chosen.label}", resultText) } },
                            onRunAnother = { vm.chooseAnotherAction() }
                        )
                    }
                }

                is CircleUiState.Error ->
                    ErrorPanel(message = s.message, onDismiss = onDismiss, onRetry = { vm.reset() })
            }
        }

        // "Saved to Vault" banner
        AnimatedVisibility(
            visible = savedBanner,
            enter   = slideInVertically { -it } + fadeIn(),
            exit    = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp)
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Green500)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.CheckCircle, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Text("Saved to Library", style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
        }
    }
}

// ── Processing panel ──────────────────────────────────────────────────────────

@Composable
private fun ProcessingPanel() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        com.gone.ai.ui.components.WatchScanningLottieAnimation(
            modifier = Modifier.size(120.dp)
        )
        Text("Reading your selection…", style = MaterialTheme.typography.titleMedium,
            color = Color.White, textAlign = TextAlign.Center)
        Text("On-device OCR in progress", style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(0.7f))
        Spacer(Modifier.height(8.dp))
    }
}

// ── Action panel ──────────────────────────────────────────────────────────────

@Composable
private fun ActionPanel(
    ocrText  : String,
    detection: ContentTypeDetector.DetectionResult?,
    onAction : (CircleAction) -> Unit,
    onDismiss: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("What would you like to do?",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White, fontWeight = FontWeight.SemiBold)
                if (detection != null) {
                    Text("Detected: ${detection.type.name.lowercase().replaceFirstChar { it.uppercase() }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Blue500)
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, "Close", tint = Color.White.copy(0.6f))
            }
        }

        // OCR text preview
        if (ocrText.isNotBlank()) {
            Box(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkGlass)
                    .padding(12.dp)
            ) {
                Text(
                    if (ocrText.length > 150) ocrText.take(150) + "…" else ocrText,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(0.7f), lineHeight = 18.sp
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        // Primary actions (detected type specific)
        val primaryActions = detection?.primaryActions
            ?: listOf(CircleAction.EXPLAIN, CircleAction.SUMMARIZE, CircleAction.NOTES)

        Text("SUGGESTED", style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(0.4f), letterSpacing = 1.sp,
            modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            primaryActions.forEach { action ->
                PrimaryActionButton(action, onAction)
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("ALL ACTIONS", style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(0.4f), letterSpacing = 1.sp,
            modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(8.dp))

        // All actions grid
        val allActions = CircleAction.entries.filter { it != CircleAction.SAVE_TO_VAULT }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement   = Arrangement.spacedBy(8.dp),
            contentPadding        = PaddingValues(bottom = 16.dp)
        ) {
            items(allActions) { action ->
                GridActionButton(action, onAction)
            }
        }
    }
}

@Composable
private fun PrimaryActionButton(action: CircleAction, onClick: (CircleAction) -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Blue500, Purple500)))
            .clickable(role = Role.Button) { onClick(action) }
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(action.emoji, fontSize = 16.sp)
            Text(action.label, style = MaterialTheme.typography.labelLarge,
                color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun GridActionButton(action: CircleAction, onClick: (CircleAction) -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(DarkGlass)
            .border(0.5.dp, Color.White.copy(0.08f), RoundedCornerShape(14.dp))
            .clickable(role = Role.Button) { onClick(action) }
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(action.emoji, fontSize = 22.sp)
        Text(action.label, style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(0.8f), textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ── Result panel ──────────────────────────────────────────────────────────────

@Composable
private fun ResultPanel(
    resultText   : String,
    actionLabel  : String,
    status       : TaskStatus,
    saved        : Boolean,
    onStop       : () -> Unit,
    onSave       : () -> Unit,
    onRetry      : () -> Unit,
    onDismiss    : () -> Unit,
    onOpenInApp  : ((String?) -> Unit)? = null,
    onAskInApp   : (() -> Unit)? = null,
    onRunAnother : (() -> Unit)? = null
) {
    val isStreaming = status.isRunning
    val scroll = rememberScrollState()
    LaunchedEffect(resultText.length) {
        if (isStreaming) scroll.animateScrollTo(scroll.maxValue)
    }
    val statusLine = when (status) {
        is TaskStatus.Finished -> when (status.result) {
            ResultStatus.DONE -> null
            ResultStatus.PARTIAL -> "Cut short: the model stopped before finishing"
            ResultStatus.STOPPED -> "Stopped"
        }
        is TaskStatus.Failed -> status.message
        else -> null
    }

    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
        // Result header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isStreaming) {
                val inf = rememberInfiniteTransition(label = "dot")
                val a by inf.animateFloat(0.3f, 1f,
                    infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "a")
                Box(modifier = Modifier.size(8.dp).background(Blue500.copy(a), CircleShape))
                Spacer(Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(actionLabel, style = MaterialTheme.typography.titleMedium,
                    color = Color.White, fontWeight = FontWeight.SemiBold)
                statusLine?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = Amber500)
                }
            }

            if (isStreaming) {
                TextButton(onClick = onStop, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Default.Stop, null, tint = Red500, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Stop", color = Red500, style = MaterialTheme.typography.labelMedium)
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, "Close", tint = Color.White.copy(0.6f))
            }
        }

        // Result content
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(DarkGlass)
                .border(0.5.dp, Color.White.copy(0.08f), RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(scroll)) {
                when {
                    resultText.isEmpty() && isStreaming -> {
                        // Typing dots
                        val inf = rememberInfiniteTransition(label = "typing")
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            repeat(3) { i ->
                                val sc by inf.animateFloat(0.6f, 1f,
                                    infiniteRepeatable(tween(400, delayMillis = i * 130,
                                        easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "d$i")
                                Box(modifier = Modifier.size((6 * sc).dp)
                                    .background(Color.White.copy(0.5f), CircleShape))
                            }
                        }
                    }
                    resultText.isEmpty() && status is TaskStatus.Failed ->
                        TextButton(onClick = onRetry) { Text("Try again", color = Blue500) }
                    else -> MarkdownText(text = resultText, isDarkTheme = true, isStreaming = isStreaming, fontSize = 14.sp)
                }
            }
        }

        // Bottom actions
        if (!isStreaming) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (resultText.isNotBlank()) {
                    OutlinedButton(
                        onClick = onSave,
                        enabled = !saved,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        border = BorderStroke(1.dp, Green500),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(if (saved) Icons.Default.CheckCircle else Icons.Default.BookmarkAdd, null, tint = Green500,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (saved) "Saved" else "Save", color = Green500)
                    }
                }
                if (onRunAnother != null) {
                    Button(
                        onClick = onRunAnother,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Blue500)
                    ) {
                        Icon(Icons.Default.Refresh, null, tint = Color.White,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Another action", color = Color.White)
                    }
                }
            }
            if (onAskInApp != null && resultText.isNotBlank()) {
                TextButton(
                    onClick = onAskInApp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Chat, null,
                        tint = Color.White.copy(0.7f), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Ask G-one about this", color = Color.White.copy(0.8f),
                        style = MaterialTheme.typography.labelMedium)
                }
            }
            // "View in Library" — only shown when running as overlay (onOpenInApp != null)
            if (onOpenInApp != null) {
                TextButton(
                    onClick = { onOpenInApp("library") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp).heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.OpenInFull, null,
                        tint = Color.White.copy(0.5f), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("View in Library", color = Color.White.copy(0.5f),
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        } else {
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── Error panel ───────────────────────────────────────────────────────────────

@Composable
private fun ErrorPanel(message: String, onDismiss: () -> Unit, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(Icons.Default.ErrorOutline, null, tint = Red500, modifier = Modifier.size(48.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(0.8f), textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onDismiss,
                border = BorderStroke(1.dp, Color.White.copy(0.3f)),
                shape = RoundedCornerShape(12.dp)) {
                Text("Close", color = Color.White.copy(0.7f))
            }
            Button(onClick = onRetry, shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Blue500)) {
                Text("Try Again")
            }
        }
    }
}

// ── Standalone error screen (called before bottom sheet) ─────────────────────

@Composable
fun CircleErrorScreen(message: String, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.7f)),
        contentAlignment = Alignment.Center
    ) {
        ErrorPanel(message = message, onDismiss = onDismiss, onRetry = onDismiss)
    }
}
