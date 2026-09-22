package com.gone.ai.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gone.ai.ocr.ResultStatus
import com.gone.ai.ocr.TaskStatus
import com.gone.ai.ocr.isRunning
import com.gone.ai.quiz.QuizParser
import com.gone.ai.ui.theme.*
import com.gone.ai.viewmodel.QuizUiState
import com.gone.ai.viewmodel.QuizViewModel

@Composable
fun QuizScreen(
    isDarkTheme: Boolean,
    bottomPadding: Dp,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit = {},
    onAskAssistant: (title: String, text: String) -> Unit = { _, _ -> },
    vm: QuizViewModel = viewModel()
) {
    val uiState    by vm.uiState.collectAsState()
    val quizText   by vm.task.output.collectAsState()
    val taskStatus by vm.task.status.collectAsState()
    val saved      by vm.task.saved.collectAsState()
    val sourceText by vm.sourceText.collectAsState()
    val truncated  by vm.truncationNotice.collectAsState()

    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { vm.generateFromImage(it) } }

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
                title       = "Quiz Generator",
                isDarkTheme = isDarkTheme,
                uiState     = when (uiState) {
                    is QuizUiState.Idle       -> "Ready"
                    is QuizUiState.Extracting -> "Reading image..."
                    is QuizUiState.Quiz       -> if (taskStatus.isRunning) "Generating..." else "Done"
                    is QuizUiState.Error      -> "Error"
                },
                dotColor    = when (uiState) {
                    is QuizUiState.Error      -> ErrorRed
                    is QuizUiState.Extracting -> WarnAmber
                    is QuizUiState.Quiz       -> if (taskStatus.isRunning) ModernBlue else SuccessGreen
                    else                      -> if (isDarkTheme) TextSecondary else TextSecondaryLight
                },
                onBack         = onNavigateBack,
                onNavigateHome = onNavigateHome,
                showReset      = uiState != QuizUiState.Idle,
                onReset        = { vm.reset() }
            )
            if (truncated && uiState == QuizUiState.Quiz) {
                TruncationNotice("Your text is long. The questions come from its first part only.", isDarkTheme)
            }

            when (uiState) {
                is QuizUiState.Idle -> QuizIdleState(
                    isDarkTheme   = isDarkTheme,
                    onGenerate    = { text -> vm.generateFromText(text) },
                    onPickImage   = { imageLauncher.launch("image/*") }
                )
                is QuizUiState.Extracting -> CenteredSpinner("Reading image...", WarnAmber, isDarkTheme, isScanning = true)
                is QuizUiState.Quiz -> QuizResultBody(
                    quizText    = quizText,
                    sourceText  = sourceText,
                    taskStatus  = taskStatus,
                    saved       = saved,
                    isDarkTheme = isDarkTheme,
                    onStop      = { vm.stop() },
                    onRetry     = { vm.retry() },
                    actions     = ResultActions(
                        shareSubject   = "G-one quiz",
                        onSave         = { vm.saveResult() },
                        onAskAssistant = { onAskAssistant("Quiz", quizText) }
                    )
                )
                is QuizUiState.Error -> FeatureErrorState(
                    isDarkTheme = isDarkTheme,
                    message     = (uiState as QuizUiState.Error).message,
                    onRetry     = { vm.reset() }
                )
            }
        }
    }
}

@Composable
private fun QuizIdleState(
    isDarkTheme : Boolean,
    onGenerate  : (String) -> Unit,
    onPickImage : () -> Unit
) {
    var inputText by rememberSaveable { mutableStateOf("") }
    val scroll = rememberScrollState()
    val limit = QuizViewModel.MAX_INPUT_CHARS

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FeatureHeroIdleCard(
            title = "Quiz Generator",
            subtitle = "Paste study material or a health topic, or pick a photo of a page.\nG-one writes 5 practice questions you can answer here.",
            isDarkTheme = isDarkTheme,
            lottieContent = {
                com.gone.ai.ui.components.DoctorLottieAnimation(
                    modifier = Modifier.size(125.dp)
                )
            },
            actionContent = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isDarkTheme) Color(0xFF0F1420) else Color(0xFFF1F5F9))
                            .border(
                                1.dp,
                                if (isDarkTheme) ModernBorderDark else ModernBorderLight,
                                RoundedCornerShape(16.dp)
                            )
                            .padding(16.dp)
                    ) {
                        Column {
                            BasicTextField(
                                value = inputText,
                                onValueChange = { inputText = it.take(limit) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 90.dp)
                                    .semantics { contentDescription = "Text to write questions from" },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                                    lineHeight = 22.sp
                                ),
                                cursorBrush = SolidColor(if (isDarkTheme) TextPrimary else ModernBlue),
                                maxLines = 10,
                                decorationBox = { inner ->
                                    if (inputText.isEmpty()) {
                                        Text(
                                            "Type or paste notes, a topic, or medical text…",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (isDarkTheme) TextSecondary else TextSecondaryLight,
                                            lineHeight = 20.sp
                                        )
                                    }
                                    inner()
                                }
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (inputText.length >= limit) "${inputText.length} characters · limit reached"
                                else "${inputText.length} characters",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (inputText.length >= limit) WarnAmber
                                else if (isDarkTheme) TextSecondary else TextSecondaryLight,
                                modifier = Modifier.align(Alignment.End)
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        PickButton(
                            label = "From Image",
                            icon = Icons.Default.Image,
                            color = ModernBlue,
                            isDarkTheme = isDarkTheme,
                            modifier = Modifier.weight(1f),
                            onClick = onPickImage
                        )

                        val generateInteraction = remember { MutableInteractionSource() }
                        val haptic = LocalHapticFeedback.current
                        val isPressed by generateInteraction.collectIsPressedAsState()
                        val genElevation by animateDpAsState(
                            targetValue = if (isPressed) 1.dp else (if (isDarkTheme) 0.dp else 4.dp),
                            label = "quizGenElevation"
                        )
                        val isEnabled = inputText.isNotBlank()

                        Box(
                            modifier = Modifier
                                .weight(1.2f)
                                .pressScale(generateInteraction, pressedScale = 0.96f)
                                .shadow(
                                    elevation = genElevation,
                                    shape = RoundedCornerShape(20.dp),
                                    ambientColor = LightShadow,
                                    spotColor = LightShadow
                                )
                                .clip(RoundedCornerShape(20.dp))
                                .background(
                                    if (isDarkTheme) AccentGoldBg.copy(alpha = if (isEnabled) 1f else 0.35f)
                                    else ModernBlue.copy(alpha = if (isEnabled) 1f else 0.35f)
                                )
                                .clickable(
                                    enabled = isEnabled,
                                    interactionSource = generateInteraction,
                                    indication = null,
                                    role = Role.Button,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onGenerate(inputText)
                                    }
                                )
                                .padding(vertical = 18.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            val fg = if (isDarkTheme) AccentGoldFg else Color.White
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.AutoAwesome, null, tint = fg, modifier = Modifier.size(20.dp))
                                Text(
                                    "Generate Quiz",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = fg,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
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
                            Icons.Default.School, null,
                            tint = if (isDarkTheme) TextSecondary else ModernBlue,
                            modifier = Modifier.size(18.dp).padding(top = 1.dp)
                        )
                        Text(
                            "Each question has four options. Tap one to see if it is right, with a short reason from your text. " +
                                "Questions are written by the on-device model, so check anything important.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isDarkTheme) TextSecondary else TextSecondaryLight,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun QuizResultBody(
    quizText    : String,
    sourceText  : String,
    taskStatus  : TaskStatus,
    saved       : Boolean,
    isDarkTheme : Boolean,
    onStop      : () -> Unit,
    onRetry     : () -> Unit,
    actions     : ResultActions
) {
    val finished = taskStatus as? TaskStatus.Finished
    val questions = remember(quizText, finished) {
        if (finished?.result == ResultStatus.DONE) QuizParser.parse(quizText) else emptyList()
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (sourceText.isNotBlank()) {
            ExtractedTextPreview(sourceText, isDarkTheme, label = "SOURCE TEXT")
            Spacer(Modifier.height(12.dp))
        }
        if (questions.isNotEmpty()) {
            QuizPlayer(
                questions   = questions,
                isDarkTheme = isDarkTheme,
                modifier    = Modifier.weight(1f),
                footer      = {
                    Column {
                        ResultActionsRow(text = quizText, saved = saved, isDarkTheme = isDarkTheme, actions = actions)
                    }
                }
            )
        } else {
            DocumentResultSection(
                text        = quizText,
                status      = taskStatus,
                saved       = saved,
                isDarkTheme = isDarkTheme,
                onStop      = onStop,
                onRetry     = onRetry,
                actions     = actions,
                modifier    = Modifier.weight(1f, fill = false)
            )
        }
    }
}
