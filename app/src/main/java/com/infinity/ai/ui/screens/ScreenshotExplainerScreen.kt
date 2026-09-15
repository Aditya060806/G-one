package com.infinity.ai.ui.screens

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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.infinity.ai.ui.components.GlassCard
import com.infinity.ai.ui.components.GradientBackground
import com.infinity.ai.ui.theme.*
import com.infinity.ai.viewmodel.ScreenshotAction
import com.infinity.ai.viewmodel.ScreenshotExplainerViewModel
import com.infinity.ai.viewmodel.ScreenshotUiState

@Composable
fun ScreenshotExplainerScreen(
    isDarkTheme: Boolean,
    bottomPadding: Dp,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit = {},
    vm: ScreenshotExplainerViewModel = viewModel()
) {
    val uiState       by vm.uiState.collectAsState()
    val extractedText by vm.extractedText.collectAsState()
    val resultText    by vm.resultText.collectAsState()
    val showSavedBanner by vm.showSavedBanner.collectAsState()

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
            SavedBanner(showSavedBanner)
            FeatureHeader(
                title       = "Screenshot Explainer",
                isDarkTheme = isDarkTheme,
                uiState     = when (uiState) {
                    is ScreenshotUiState.Idle       -> "Ready"
                    is ScreenshotUiState.Extracting -> "Reading..."
                    is ScreenshotUiState.TextReady  -> "Ready"
                    is ScreenshotUiState.Processing -> "Processing..."
                    is ScreenshotUiState.Done       -> "Done"
                    is ScreenshotUiState.Error      -> "Error"
                },
                dotColor    = when (uiState) {
                    is ScreenshotUiState.Error      -> ErrorRed
                    is ScreenshotUiState.Done       -> SuccessGreen
                    is ScreenshotUiState.Processing -> ModernBlue
                    is ScreenshotUiState.Extracting -> WarnAmber
                    else                            -> if (isDarkTheme) TextSecondary else TextSecondaryLight
                },
                onBack         = onNavigateBack,
                onNavigateHome = onNavigateHome,
                showReset      = uiState != ScreenshotUiState.Idle,
                onReset        = { vm.reset() }
            )

            when (uiState) {
                is ScreenshotUiState.Idle -> ScreenshotIdleState(
                    isDarkTheme = isDarkTheme,
                    onPick      = { galleryLauncher.launch("image/*") }
                )
                is ScreenshotUiState.Extracting -> CenteredSpinner("Reading screenshot...", WarnAmber, isDarkTheme, isScanning = true)
                is ScreenshotUiState.TextReady,
                is ScreenshotUiState.Processing,
                is ScreenshotUiState.Done -> {
                    ScreenshotReadyBody(
                        extractedText = extractedText,
                        resultText    = resultText,
                        isProcessing  = uiState is ScreenshotUiState.Processing,
                        isDarkTheme   = isDarkTheme,
                        onAction      = { vm.runAction(it) },
                        onStop        = { vm.stop() },
                        onNewImage    = {
                            vm.reset()
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
            subtitle = "Pick a screenshot of an error, code, chart, or any text.\nThe AI will explain it instantly — fully offline.",
            isDarkTheme = isDarkTheme,
            lottieContent = {
                com.infinity.ai.ui.components.WatchScanningLottieAnimation(
                    modifier = Modifier.size(110.dp)
                )
            },
            actionContent = {
                PickButton(
                    label = "Choose Screenshot",
                    icon = Icons.Default.ScreenShare,
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
                            "Works great with:",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isDarkTheme) TextSecondary else TextSecondaryLight,
                            fontWeight = FontWeight.SemiBold
                        )
                        listOf(
                            "Android Studio errors & stack traces",
                            "Code snippets & terminal output",
                            "Medical reports, ECGs & graphs",
                            "Technical documentation & study notes"
                        ).forEach { item ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.size(4.dp).background(ModernBlue, CircleShape))
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
    isProcessing : Boolean,
    isDarkTheme  : Boolean,
    onAction     : (ScreenshotAction) -> Unit,
    onStop       : () -> Unit,
    onNewImage   : () -> Unit
) {
    val scroll = rememberScrollState()
    LaunchedEffect(resultText.length) { if (isProcessing) scroll.animateScrollTo(scroll.maxValue) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Box(
            modifier = Modifier
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
                .padding(20.dp)
        ) {
            Column {
                Text(
                    "EXTRACTED TEXT",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isDarkTheme) TextSecondary else TextSecondaryLight,
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (extractedText.length > 200) extractedText.take(200) + "…" else extractedText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                    lineHeight = 18.sp
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        if (!isProcessing) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ScreenshotAction.entries.forEach { action ->
                    Box(
                        modifier = Modifier
                            .shadow(
                                elevation = if (isDarkTheme) 0.dp else 2.dp,
                                shape = RoundedCornerShape(20.dp),
                                ambientColor = LightShadow,
                                spotColor = LightShadow
                            )
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isDarkTheme) Color(0xFF1E293B) else ModernBlueSubtle)
                            .border(1.dp, ModernBlue.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                            .clickable { onAction(action) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            action.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = ModernBlue,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onNewImage, modifier = Modifier.align(Alignment.End)) {
                Icon(
                    Icons.Default.AddPhotoAlternate, null,
                    modifier = Modifier.size(16.dp), tint = ModernBlue
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "New Screenshot",
                    style = MaterialTheme.typography.labelMedium,
                    color = ModernBlue,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (resultText.isNotBlank() || isProcessing) {
            Spacer(Modifier.height(8.dp))
            StreamingResultCard(
                text        = resultText,
                isStreaming = isProcessing,
                isDarkTheme = isDarkTheme,
                scrollState = scroll,
                onStop      = onStop,
                modifier    = Modifier.weight(1f),
                accentColor = ModernBlue
            )
        }
    }
}
