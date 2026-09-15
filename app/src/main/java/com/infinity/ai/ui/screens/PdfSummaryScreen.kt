package com.infinity.ai.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.infinity.ai.ui.components.BreadcrumbHeader
import com.infinity.ai.ui.components.BreadcrumbItem
import com.infinity.ai.ui.components.HeaderActionPill
import com.infinity.ai.ui.components.PureBreadcrumbText
import com.infinity.ai.ui.theme.*
import com.infinity.ai.viewmodel.PdfSummarizeUiState
import com.infinity.ai.viewmodel.PdfSummarizeViewModel

@Composable
fun PdfSummaryScreen(
    isDarkTheme: Boolean,
    bottomPadding: Dp,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit = {},
    viewModel: PdfSummarizeViewModel = viewModel()
) {
    val uiState         by viewModel.uiState.collectAsState()
    val summaryText     by viewModel.summaryText.collectAsState()
    val extractProgress by viewModel.extractionProgress.collectAsState()
    val showSavedBanner by viewModel.showSavedBanner.collectAsState()
    val statusLabel     by viewModel.statusLabel.collectAsState()
    val tokenCount      by viewModel.tokenCount.collectAsState()

    // PDF file picker — filters for PDF MIME type
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.summarize(it) }
    }

    val summaryScrollState = rememberScrollState()
    val isScrolled by remember {
        derivedStateOf { summaryScrollState.value > 10 }
    }

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
            // ── Header (Sticky breadcrumb text, non-sticky action pills) ──────
            FeatureHeader(
                title          = "PDF Summary",
                isDarkTheme    = isDarkTheme,
                uiState        = when (uiState) {
                    is PdfSummarizeUiState.Idle        -> "Ready"
                    is PdfSummarizeUiState.Extracting  -> "Reading..."
                    is PdfSummarizeUiState.Summarizing -> "Summarizing..."
                    is PdfSummarizeUiState.Done        -> "Done"
                    is PdfSummarizeUiState.Partial     -> "Partial"
                    is PdfSummarizeUiState.Error       -> "Error"
                },
                dotColor       = when (uiState) {
                    is PdfSummarizeUiState.Error       -> ErrorRed
                    is PdfSummarizeUiState.Done        -> SuccessGreen
                    is PdfSummarizeUiState.Partial     -> WarnAmber
                    is PdfSummarizeUiState.Summarizing -> ModernBlue
                    is PdfSummarizeUiState.Extracting  -> WarnAmber
                    else                               -> if (isDarkTheme) TextSecondary else TextSecondaryLight
                },
                onBack         = onNavigateBack,
                onNavigateHome = onNavigateHome,
                showReset      = uiState !is PdfSummarizeUiState.Idle,
                onReset        = { viewModel.reset() },
                isScrolled     = isScrolled
            )

            // ── Body ──────────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                when (uiState) {
                    is PdfSummarizeUiState.Idle  -> {
                        FeatureHeroIdleCard(
                            title = "Summarize a PDF",
                            subtitle = "Pick any text-based PDF. The on-device AI will\ngenerate a concise summary — fully offline.",
                            isDarkTheme = isDarkTheme,
                            lottieContent = {
                                com.infinity.ai.ui.components.WatchScanningLottieAnimation(
                                    modifier = Modifier.size(110.dp)
                                )
                            },
                            actionContent = {
                                PickButton(
                                    label = "Choose PDF File",
                                    icon = Icons.Default.FolderOpen,
                                    color = ModernBlue,
                                    isDarkTheme = isDarkTheme,
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { filePicker.launch("application/pdf") }
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
                                    Row(
                                        verticalAlignment = Alignment.Top,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Info, null,
                                            tint = WarnAmber,
                                            modifier = Modifier.size(18.dp).padding(top = 1.dp)
                                        )
                                        Text(
                                            "Works best with text-based PDFs (reports, articles, clinical notes). " +
                                            "Scanned image PDFs can be parsed using the OCR tool.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (isDarkTheme) TextSecondary else TextSecondaryLight,
                                            lineHeight = 18.sp
                                        )
                                    }
                                }
                            }
                        )
                    }

                    is PdfSummarizeUiState.Extracting -> {
                        CenteredSpinner(
                            label = "Reading PDF (${(extractProgress * 100).toInt()}%)...",
                            color = ModernBlue,
                            isDarkTheme = isDarkTheme,
                            isScanning = true
                        )
                    }

                    is PdfSummarizeUiState.Summarizing,
                    is PdfSummarizeUiState.Done,
                    is PdfSummarizeUiState.Partial -> {
                        val isStreaming = uiState is PdfSummarizeUiState.Summarizing
                        val isPartial = uiState is PdfSummarizeUiState.Partial
                        LaunchedEffect(summaryText.length) {
                            if (isStreaming) summaryScrollState.animateScrollTo(summaryScrollState.maxValue)
                        }
                        StreamingResultCard(
                            text           = summaryText,
                            isStreaming    = isStreaming,
                            isDarkTheme    = isDarkTheme,
                            scrollState    = summaryScrollState,
                            onStop         = { viewModel.stop() },
                            modifier       = Modifier.fillMaxSize(),
                            accentColor    = ModernBlue,
                            streamingLabel = if (tokenCount > 0) "$statusLabel ($tokenCount tokens)" else statusLabel,
                            completeLabel  = if (isPartial) "Summary partially completed" else "Summary complete",
                            completeColor  = if (isPartial) WarnAmber else SuccessGreen
                        )
                    }

                    is PdfSummarizeUiState.Error -> {
                        FeatureErrorState(
                            isDarkTheme = isDarkTheme,
                            message     = (uiState as PdfSummarizeUiState.Error).message,
                            onRetry     = {
                                viewModel.reset()
                                filePicker.launch("application/pdf")
                            }
                        )
                    }
                }
            }
        }
    }
}

