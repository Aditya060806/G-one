package com.gone.ai.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gone.ai.ocr.TaskStatus
import com.gone.ai.ocr.isRunning
import com.gone.ai.ui.theme.*
import com.gone.ai.viewmodel.PdfSummarizeUiState
import com.gone.ai.viewmodel.PdfSummarizeViewModel

@Composable
fun PdfSummaryScreen(
    isDarkTheme: Boolean,
    bottomPadding: Dp,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit = {},
    onAskAssistant: (title: String, text: String) -> Unit = { _, _ -> },
    /** A PDF shared from another app, opened as soon as the screen appears. */
    initialUri: Uri? = null,
    onInitialUriTaken: () -> Unit = {},
    viewModel: PdfSummarizeViewModel = viewModel()
) {
    LaunchedEffect(initialUri) {
        initialUri?.let {
            viewModel.open(it)
            onInitialUriTaken()
        }
    }

    val uiState     by viewModel.uiState.collectAsState()
    val summaryText by viewModel.task.output.collectAsState()
    val taskStatus  by viewModel.task.status.collectAsState()
    val saved       by viewModel.task.saved.collectAsState()
    val truncated   by viewModel.task.truncated.collectAsState()

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.open(it) }
    }
    val pickPdf = { filePicker.launch(arrayOf("application/pdf")) }

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
                title          = "PDF Summary",
                isDarkTheme    = isDarkTheme,
                uiState        = when (uiState) {
                    is PdfSummarizeUiState.Idle    -> "Ready"
                    is PdfSummarizeUiState.Reading -> "Reading..."
                    is PdfSummarizeUiState.Read    -> "Choose a summary"
                    is PdfSummarizeUiState.Summary -> if (taskStatus.isRunning) "Summarizing..." else "Done"
                    is PdfSummarizeUiState.Error   -> "Error"
                },
                dotColor       = when (uiState) {
                    is PdfSummarizeUiState.Error   -> ErrorRed
                    is PdfSummarizeUiState.Summary -> if (taskStatus.isRunning) ModernBlue else SuccessGreen
                    is PdfSummarizeUiState.Reading -> WarnAmber
                    else                           -> if (isDarkTheme) TextSecondary else TextSecondaryLight
                },
                onBack         = onNavigateBack,
                onNavigateHome = onNavigateHome,
                showReset      = uiState !is PdfSummarizeUiState.Idle,
                onReset        = { viewModel.reset() }
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                when (val state = uiState) {
                    is PdfSummarizeUiState.Idle -> PdfIdleState(isDarkTheme, onPick = pickPdf)

                    is PdfSummarizeUiState.Reading -> ReadingProgress(state, isDarkTheme)

                    is PdfSummarizeUiState.Read -> SummaryChoice(
                        document = state,
                        isDarkTheme = isDarkTheme,
                        onQuick = { viewModel.summarize(PdfSummarizeViewModel.Mode.QUICK) },
                        onWhole = { viewModel.summarize(PdfSummarizeViewModel.Mode.WHOLE) }
                    )

                    is PdfSummarizeUiState.Summary -> Column(modifier = Modifier.fillMaxSize()) {
                        SummaryHeading(state, taskStatus, truncated, isDarkTheme, onChange = { viewModel.chooseAgain() })
                        DocumentResultSection(
                            text        = summaryText,
                            status      = taskStatus,
                            saved       = saved,
                            isDarkTheme = isDarkTheme,
                            onStop      = { viewModel.stop() },
                            onRetry     = { viewModel.retry() },
                            actions     = ResultActions(
                                shareSubject   = "Summary of ${state.document.fileName}",
                                onSave         = { viewModel.saveResult() },
                                onAskAssistant = { onAskAssistant("Summary of ${state.document.fileName}", summaryText) }
                            ),
                            modifier    = Modifier.weight(1f, fill = false)
                        )
                    }

                    is PdfSummarizeUiState.Error -> FeatureErrorState(
                        isDarkTheme = isDarkTheme,
                        message     = state.message,
                        onRetry     = {
                            viewModel.reset()
                            pickPdf()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PdfIdleState(isDarkTheme: Boolean, onPick: () -> Unit) {
    FeatureHeroIdleCard(
        title = "Summarize a PDF",
        subtitle = "Pick a PDF — a report, an article, clinical notes.\nG-one summarises it on this phone, without internet.",
        isDarkTheme = isDarkTheme,
        lottieContent = {
            com.gone.ai.ui.components.WatchScanningLottieAnimation(
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
                onClick = onPick
            )
        },
        noteContent = {
            NoteBox(
                isDarkTheme,
                "Works with typed PDFs and with scans. Scanned pages are read as images, which takes " +
                    "longer, and handwriting may not be read. Password-protected PDFs need unlocking first."
            )
        }
    )
}

@Composable
private fun ReadingProgress(state: PdfSummarizeUiState.Reading, isDarkTheme: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        com.gone.ai.ui.components.WatchScanningLottieAnimation(modifier = Modifier.size(140.dp))
        Spacer(Modifier.height(14.dp))
        Text(
            state.fileName,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(6.dp))
        val label = if (state.totalPages == 0) "Opening the file…"
        else "Reading page ${(state.pagesRead + 1).coerceAtMost(state.totalPages)} of ${state.totalPages}"
        Text(label, style = MaterialTheme.typography.bodySmall, color = if (isDarkTheme) TextSecondary else TextSecondaryLight)
        if (state.totalPages > 0) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { state.pagesRead.toFloat() / state.totalPages },
                modifier = Modifier.fillMaxWidth(0.6f),
                color = if (isDarkTheme) AccentGoldBg else ModernBlue
            )
        }
        if (state.readingImages) {
            Spacer(Modifier.height(10.dp))
            Text(
                "This page is a scan, so it is being read as an image.",
                style = MaterialTheme.typography.labelSmall,
                color = if (isDarkTheme) TextSecondary else TextSecondaryLight,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SummaryChoice(
    document: PdfSummarizeUiState.Read,
    isDarkTheme: Boolean,
    onQuick: () -> Unit,
    onWhole: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            document.fileName,
            style = MaterialTheme.typography.titleMedium,
            color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
            fontWeight = FontWeight.Bold
        )
        Text(
            buildString {
                append(
                    if (document.pagesRead < document.totalPages) "Read ${document.pagesRead} of ${document.totalPages} pages"
                    else "${document.totalPages} page${if (document.totalPages == 1) "" else "s"}"
                )
                if (document.imagePages > 0) append(" · ${document.imagePages} read as images")
                append(". This is too long for one summary, so choose how to summarise it.")
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (isDarkTheme) TextSecondary else TextSecondaryLight
        )
        ChoiceCard(
            icon = Icons.Default.Bolt,
            title = "Quick summary",
            body = "Summarises the first part of the document. Usually under a minute.",
            isDarkTheme = isDarkTheme,
            onClick = onQuick
        )
        ChoiceCard(
            icon = Icons.AutoMirrored.Filled.MenuBook,
            title = "Whole document",
            body = "Reads all ${document.parts} parts, then combines them into one summary. " +
                "Takes several minutes; you can stop at any time.",
            isDarkTheme = isDarkTheme,
            onClick = onWhole
        )
        if (!document.coversWholeFile) {
            NoteBox(
                isDarkTheme,
                "This document is very long. Summaries cover the first ${document.pagesRead} " +
                    "page${if (document.pagesRead == 1) "" else "s"}, up to ${document.parts} parts."
            )
        }
    }
}

@Composable
private fun ChoiceCard(icon: ImageVector, title: String, body: String, isDarkTheme: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (isDarkTheme) ModernCardDark else ModernCardLight)
            .border(1.dp, if (isDarkTheme) ModernBorderDark else ModernBorderLight, RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = if (isDarkTheme) AccentGoldBg else ModernBlue, modifier = Modifier.size(24.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (isDarkTheme) TextPrimary else TextPrimaryLight, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = if (isDarkTheme) TextSecondary else TextSecondaryLight, lineHeight = 18.sp)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = if (isDarkTheme) TextSecondary else TextSecondaryLight)
    }
}

@Composable
private fun SummaryHeading(
    state: PdfSummarizeUiState.Summary,
    status: TaskStatus,
    truncated: Boolean,
    isDarkTheme: Boolean,
    onChange: () -> Unit
) {
    val document = state.document
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                document.fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val scope = when {
                document.parts <= 1 && truncated -> "Summary of the first part (the rest did not fit)"
                document.parts <= 1 -> "Summary of the document"
                state.mode == PdfSummarizeViewModel.Mode.QUICK -> "Quick summary of the first part"
                else -> "Whole document, ${document.parts} parts"
            }
            Text(scope, style = MaterialTheme.typography.labelSmall, color = if (isDarkTheme) TextSecondary else TextSecondaryLight)
        }
        if (document.parts > 1 && !status.isRunning) {
            TextButton(onClick = onChange, modifier = Modifier.heightIn(min = 48.dp)) { Text("Change") }
        }
    }
}

@Composable
private fun NoteBox(isDarkTheme: Boolean, text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isDarkTheme) Color(0xFF1E293B).copy(alpha = 0.4f) else ModernBlueSubtle.copy(alpha = 0.4f))
            .border(1.dp, if (isDarkTheme) ModernBorderDark else ModernBorderLight, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Info, null, tint = WarnAmber, modifier = Modifier.size(18.dp).padding(top = 1.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = if (isDarkTheme) TextSecondary else TextSecondaryLight, lineHeight = 18.sp)
        }
    }
}
