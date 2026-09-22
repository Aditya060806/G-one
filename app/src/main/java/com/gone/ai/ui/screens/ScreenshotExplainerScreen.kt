package com.gone.ai.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gone.ai.ocr.TaskStatus
import com.gone.ai.ocr.isRunning
import com.gone.ai.ui.theme.*
import com.gone.ai.viewmodel.ScreenshotAction
import com.gone.ai.viewmodel.ScreenshotExplainerViewModel
import com.gone.ai.viewmodel.ScreenshotUiState

@Composable
fun ScreenshotExplainerScreen(
    isDarkTheme: Boolean,
    bottomPadding: Dp,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit = {},
    onAskAssistant: (title: String, text: String) -> Unit = { _, _ -> },
    vm: ScreenshotExplainerViewModel = viewModel()
) {
    val uiState       by vm.uiState.collectAsState()
    val extractedText by vm.extractedText.collectAsState()
    val resultText    by vm.task.output.collectAsState()
    val taskStatus    by vm.task.status.collectAsState()
    val saved         by vm.task.saved.collectAsState()
    val lastAction    by vm.lastAction.collectAsState()
    val truncated     by vm.truncationNotice.collectAsState()

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { vm.analyzeScreenshot(it) } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isDarkTheme) ModernBgDark else ModernBgLight)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(bottom = bottomPadding)
        ) {
            FeatureHeader(
                title       = "Screenshot Explainer",
                isDarkTheme = isDarkTheme,
                uiState     = when (uiState) {
                    is ScreenshotUiState.Idle       -> "Ready"
                    is ScreenshotUiState.Extracting -> "Reading..."
                    is ScreenshotUiState.TextReady  -> if (taskStatus.isRunning) "Processing..." else "Ready"
                    is ScreenshotUiState.Error      -> "Error"
                },
                dotColor    = when (uiState) {
                    is ScreenshotUiState.Error      -> ErrorRed
                    is ScreenshotUiState.Extracting -> WarnAmber
                    is ScreenshotUiState.TextReady  -> if (taskStatus.isRunning) ModernBlue else SuccessGreen
                    else                            -> if (isDarkTheme) TextSecondary else TextSecondaryLight
                },
                onBack         = onNavigateBack,
                onNavigateHome = onNavigateHome,
                showReset      = uiState != ScreenshotUiState.Idle,
                onReset        = { vm.reset() }
            )
            if (truncated && uiState == ScreenshotUiState.TextReady) {
                TruncationNotice("This screenshot has a lot of text. Only its first part fits in one answer.", isDarkTheme)
            }

            when (uiState) {
                is ScreenshotUiState.Idle -> ScreenshotIdleState(
                    isDarkTheme = isDarkTheme,
                    onPick      = { galleryLauncher.launch("image/*") }
                )
                is ScreenshotUiState.Extracting -> CenteredSpinner("Reading screenshot...", WarnAmber, isDarkTheme, isScanning = true)
                is ScreenshotUiState.TextReady -> {
                    val title = "Screenshot – ${lastAction?.label ?: "Result"}"
                    ScreenshotReadyBody(
                        extractedText = extractedText,
                        resultText    = resultText,
                        taskStatus    = taskStatus,
                        saved         = saved,
                        isDarkTheme   = isDarkTheme,
                        onAction      = { vm.runAction(it) },
                        onStop        = { vm.stop() },
                        onRetry       = { vm.retry() },
                        actions       = ResultActions(
                            shareSubject   = title,
                            onSave         = { vm.saveResult() },
                            onAskAssistant = { onAskAssistant(title, resultText) }
                        ),
                        onNewImage    = {
                            galleryLauncher.launch("image/*")
                        }
                    )
                }
                is ScreenshotUiState.Error -> FeatureErrorState(
                    isDarkTheme = isDarkTheme,
                    message     = (uiState as ScreenshotUiState.Error).message,
                    onRetry     = {
                        vm.reset()
                        galleryLauncher.launch("image/*")
                    }
                )
            }
        }
    }
}

@Composable
private fun ScreenshotIdleState(isDarkTheme: Boolean, onPick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        FeatureHeroIdleCard(
            title = "Screenshot Explainer",
            subtitle = "Pick a screenshot of a report, a health portal or any text.\nG-one explains it on this phone, without internet.",
            isDarkTheme = isDarkTheme,
            lottieContent = {
                com.gone.ai.ui.components.WatchScanningLottieAnimation(
                    modifier = Modifier.size(110.dp)
                )
            },
            actionContent = {
                PickButton(
                    label = "Choose Screenshot",
                    icon = Icons.Default.Screenshot,
                    color = ModernBlue,
                    isDarkTheme = isDarkTheme,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onPick
                )
            },
            noteContent = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isDarkTheme) Color(0xFF1E293B).copy(alpha = 0.4f) else ModernBlueSubtle.copy(alpha = 0.4f))
                        .border(
                            1.dp,
                            if (isDarkTheme) ModernBorderDark else ModernBorderLight,
                            RoundedCornerShape(16.dp)
                        )
                        .padding(14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Works well with:",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isDarkTheme) TextSecondary else TextSecondaryLight,
                            fontWeight = FontWeight.SemiBold
                        )
                        listOf(
                            "Lab results from a hospital or lab app",
                            "Pharmacy labels and prescriptions",
                            "Appointment letters and discharge notes",
                            "Any printed text you want explained simply"
                        ).forEach { item ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.size(4.dp).background(if (isDarkTheme) TextSecondary else ModernBlue, CircleShape))
                                Text(
                                    item,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isDarkTheme) TextPrimary else TextPrimaryLight
                                )
                            }
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun ScreenshotReadyBody(
    extractedText: String,
    resultText   : String,
    taskStatus   : TaskStatus,
    saved        : Boolean,
    isDarkTheme  : Boolean,
    onAction     : (ScreenshotAction) -> Unit,
    onStop       : () -> Unit,
    onRetry      : () -> Unit,
    actions      : ResultActions,
    onNewImage   : () -> Unit
) {
    val isProcessing = taskStatus.isRunning

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        ExtractedTextPreview(extractedText, isDarkTheme)

        Spacer(Modifier.height(12.dp))

        if (!isProcessing) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ScreenshotAction.entries.forEach { action ->
                    ActionChip(action.label, isDarkTheme) { onAction(action) }
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onNewImage, modifier = Modifier.align(Alignment.End)) {
                Icon(
                    Icons.Default.AddPhotoAlternate, null,
                    modifier = Modifier.size(16.dp), tint = if (isDarkTheme) TextPrimary else ModernBlue
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "New Screenshot",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isDarkTheme) TextPrimary else ModernBlue,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        DocumentResultSection(
            text        = resultText,
            status      = taskStatus,
            saved       = saved,
            isDarkTheme = isDarkTheme,
            onStop      = onStop,
            onRetry     = onRetry,
            actions     = actions,
            modifier    = Modifier.weight(1f, fill = false).padding(top = 8.dp)
        )
    }
}
