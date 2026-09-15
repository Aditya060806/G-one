package com.infinity.ai.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.viewmodel.compose.viewModel
import com.infinity.ai.ui.theme.*
import com.infinity.ai.viewmodel.QuizUiState
import com.infinity.ai.viewmodel.QuizViewModel

@Composable
fun QuizScreen(
    isDarkTheme: Boolean,
    bottomPadding: Dp,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit = {},
    vm: QuizViewModel = viewModel()
) {
    val uiState    by vm.uiState.collectAsState()
    val quizText   by vm.quizText.collectAsState()
    val sourceText by vm.sourceText.collectAsState()
    val showSavedBanner by vm.showSavedBanner.collectAsState()

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
            SavedBanner(showSavedBanner)
            FeatureHeader(
                title       = "Quiz Generator",
                isDarkTheme = isDarkTheme,
                uiState     = when (uiState) {
                    is QuizUiState.Idle       -> "Ready"
                    is QuizUiState.Extracting -> "Reading image..."
                    is QuizUiState.Generating -> "Generating..."
                    is QuizUiState.Done       -> "Done"
                    is QuizUiState.Error      -> "Error"
                },
                dotColor    = when (uiState) {
                    is QuizUiState.Error      -> ErrorRed
                    is QuizUiState.Done       -> SuccessGreen
                    is QuizUiState.Generating -> ModernBlue
                    is QuizUiState.Extracting -> WarnAmber
                    else                      -> if (isDarkTheme) TextSecondary else TextSecondaryLight
                },
                onBack         = onNavigateBack,
                onNavigateHome = onNavigateHome,
                showReset      = uiState != QuizUiState.Idle,
                onReset        = { vm.reset() }
            )

            when (uiState) {
                is QuizUiState.Idle -> QuizIdleState(
                    isDarkTheme   = isDarkTheme,
                    onGenerate    = { text -> vm.generateFromText(text) },
                    onPickImage   = { imageLauncher.launch("image/*") }
                )
                is QuizUiState.Extracting -> CenteredSpinner("Reading image...", WarnAmber, isDarkTheme, isScanning = true)
                is QuizUiState.Generating, is QuizUiState.Done -> {
                    QuizResultBody(
                        quizText    = quizText,
                        isGenerating = uiState is QuizUiState.Generating,
                        isDarkTheme = isDarkTheme,
                        onStop      = { vm.stop() }
                    )
                }
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
    var inputText by remember { mutableStateOf("") }
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FeatureHeroIdleCard(
            title = "Quiz Generator",
            subtitle = "Paste study material, medical topics, or pick an image.\nThe AI will formulate 5 tailored practice questions.",
            isDarkTheme = isDarkTheme,
            lottieContent = {
                com.infinity.ai.ui.components.DoctorLottieAnimation(
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
                                onValueChange = { if (it.length <= 800) inputText = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 90.dp),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                                    lineHeight = 22.sp
                                ),
                                cursorBrush = SolidColor(ModernBlue),
                                maxLines = 8,
                                decorationBox = { inner ->
                                    if (inputText.isEmpty()) {
                                        Text(
                                            "Type or paste medical text, notes, or concepts (up to 800 characters)…",
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
                                "${inputText.length}/800",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (inputText.length >= 800) WarnAmber
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
                                .background(if (isEnabled) ModernBlue else ModernBlue.copy(alpha = 0.35f))
                                .clickable(
                                    enabled = isEnabled,
                                    interactionSource = generateInteraction,
                                    indication = null,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onGenerate(inputText)
                                    }
                                )
                                .padding(vertical = 18.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(20.dp))
                                Text(
                                    "Generate Quiz",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White,
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
                            tint = ModernBlue,
                            modifier = Modifier.size(18.dp).padding(top = 1.dp)
                        )
                        Text(
                            "Questions include multiple-choice answers, correct answer explanations, and clinical reasoning.",
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
    isGenerating: Boolean,
    isDarkTheme : Boolean,
    onStop      : () -> Unit
) {
    val scroll = rememberScrollState()
    LaunchedEffect(quizText.length) { if (isGenerating) scroll.animateScrollTo(scroll.maxValue) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        StreamingResultCard(
            text        = quizText,
            isStreaming = isGenerating,
            isDarkTheme = isDarkTheme,
            scrollState = scroll,
            onStop      = onStop,
            modifier    = Modifier.weight(1f),
            accentColor = ModernBlue,
            streamingLabel = "Generating quiz..."
        )
    }
}

