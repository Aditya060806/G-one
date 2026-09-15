package com.infinity.ai.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.infinity.ai.ui.theme.*
import com.infinity.ai.viewmodel.OcrAction
import com.infinity.ai.viewmodel.OcrUiState
import com.infinity.ai.viewmodel.OcrViewModel
import java.io.File

@Composable
fun OcrScreen(
    isDarkTheme: Boolean,
    bottomPadding: Dp,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit = {},
    vm: OcrViewModel = viewModel()
) {
    val uiState           by vm.uiState.collectAsState()
    val extractedText     by vm.extractedText.collectAsState()
    val resultText        by vm.resultText.collectAsState()
    val showSavedBanner   by vm.showSavedBanner.collectAsState()
    val truncationNotice  by vm.truncationNotice.collectAsState()
    val context           = LocalContext.current

    // ── Camera URI ────────────────────────────────────────────────────────────
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok -> if (ok) cameraUri?.let { vm.extractText(it) } }

    val cameraPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val file = File.createTempFile("ocr_", ".jpg", context.cacheDir)
            val uri  = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            cameraUri = uri
            cameraLauncher.launch(uri)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { vm.extractText(it) } }

    val onCamera: () -> Unit = {
        val hasCam = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        if (hasCam) {
            val file = File.createTempFile("ocr_", ".jpg", context.cacheDir)
            val uri  = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            cameraUri = uri
            cameraLauncher.launch(uri)
        } else {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }
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
            if (truncationNotice) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(WarnAmber.copy(alpha = 0.15f))
                        .border(1.dp, WarnAmber.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        "Large document detected. Summarizing first section for speed.",
                        style = MaterialTheme.typography.labelSmall,
                        color = WarnAmber,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            FeatureHeader(
                title       = "OCR Scanner",
                isDarkTheme = isDarkTheme,
                uiState     = when (uiState) {
                    is OcrUiState.Idle       -> "Ready"
                    is OcrUiState.Extracting -> "Reading..."
                    is OcrUiState.TextReady  -> "Text extracted"
                    is OcrUiState.Processing -> "Processing..."
                    is OcrUiState.Done       -> "Done"
                    is OcrUiState.Partial    -> "Partial"
                    is OcrUiState.Error      -> "Error"
                },
                dotColor    = when (uiState) {
                    is OcrUiState.Error      -> ErrorRed
                    is OcrUiState.Done       -> SuccessGreen
                    is OcrUiState.Partial    -> WarnAmber
                    is OcrUiState.Processing -> ModernBlue
                    is OcrUiState.Extracting -> WarnAmber
                    else                     -> if (isDarkTheme) TextSecondary else TextSecondaryLight
                },
                onBack         = onNavigateBack,
                onNavigateHome = onNavigateHome,
                showReset      = uiState != OcrUiState.Idle,
                onReset        = { vm.reset() }
            )

            when (uiState) {
                is OcrUiState.Idle -> OcrIdleState(
                    isDarkTheme = isDarkTheme,
                    onGallery   = { galleryLauncher.launch("image/*") },
                    onCamera    = onCamera
                )
                is OcrUiState.Extracting -> CenteredSpinner("Reading image...", WarnAmber, isDarkTheme, isScanning = true)
                is OcrUiState.TextReady, is OcrUiState.Processing, is OcrUiState.Done, is OcrUiState.Partial -> {
                    val isProcessing = uiState is OcrUiState.Processing
                    val isPartial    = uiState is OcrUiState.Partial
                    OcrReadyBody(
                        extractedText = extractedText,
                        resultText    = resultText,
                        isProcessing  = isProcessing,
                        isPartial     = isPartial,
                        isDarkTheme   = isDarkTheme,
                        onAction      = { vm.runAction(it) },
                        onStop        = { vm.stop() },
                        onNewImage    = {
                            vm.reset()
                            galleryLauncher.launch("image/*")
                        }
                    )
                }
                is OcrUiState.Error -> FeatureErrorState(
                    isDarkTheme = isDarkTheme,
                    message     = (uiState as OcrUiState.Error).message,
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
private fun OcrIdleState(isDarkTheme: Boolean, onGallery: () -> Unit, onCamera: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        FeatureHeroIdleCard(
            title = "OCR Scanner",
            subtitle = "Extract text from any image or document, then summarize,\nexplain, or convert it using on-device AI.",
            isDarkTheme = isDarkTheme,
            lottieContent = {
                com.infinity.ai.ui.components.WatchScanningLottieAnimation(
                    modifier = Modifier.size(110.dp)
                )
            },
            actionContent = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PickButton(
                        label = "Gallery",
                        icon = Icons.Default.PhotoLibrary,
                        color = ModernBlue,
                        isDarkTheme = isDarkTheme,
                        modifier = Modifier.weight(1f),
                        onClick = onGallery
                    )
                    PickButton(
                        label = "Camera",
                        icon = Icons.Default.CameraAlt,
                        color = SuccessGreen,
                        isDarkTheme = isDarkTheme,
                        modifier = Modifier.weight(1f),
                        onClick = onCamera
                    )
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
                            Icons.Default.DocumentScanner, null,
                            tint = ModernBlue,
                            modifier = Modifier.size(18.dp).padding(top = 1.dp)
                        )
                        Text(
                            "OCR runs locally via on-device vision models. Scanned prescriptions, lab reports, and textbooks work instantly without internet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isDarkTheme) TextSecondary else TextSecondaryLight,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        )
    }
}

@Composable
private fun OcrReadyBody(
    extractedText: String,
    resultText   : String,
    isProcessing : Boolean,
    isPartial    : Boolean,
    isDarkTheme  : Boolean,
    onAction     : (OcrAction) -> Unit,
    onStop       : () -> Unit,
    onNewImage   : () -> Unit
) {
    val scroll = rememberScrollState()
    LaunchedEffect(resultText.length) { if (isProcessing) scroll.animateScrollTo(scroll.maxValue) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // Extracted text preview
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
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
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

        // Action chips
        if (!isProcessing) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OcrAction.entries.forEach { action ->
                    ActionChip(action.label, isDarkTheme) { onAction(action) }
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onNewImage,
                modifier = Modifier.align(Alignment.End)
            ) {
                Icon(
                    Icons.Default.AddPhotoAlternate, null,
                    modifier = Modifier.size(16.dp),
                    tint = ModernBlue
                )
                Spacer(Modifier.width(4.dp))
                Text("New Image", style = MaterialTheme.typography.labelMedium, color = ModernBlue, fontWeight = FontWeight.SemiBold)
            }
        }

        // Result
        if (resultText.isNotBlank() || isProcessing) {
            Spacer(Modifier.height(8.dp))
            StreamingResultCard(
                text           = resultText,
                isStreaming    = isProcessing,
                isDarkTheme    = isDarkTheme,
                scrollState    = scroll,
                onStop         = onStop,
                modifier       = Modifier.weight(1f),
                accentColor    = ModernBlue,
                streamingLabel = "Generating...",
                completeLabel  = if (isPartial) "Partial summary generated" else "Complete",
                completeColor  = if (isPartial) WarnAmber else SuccessGreen
            )
        }
    }
}

@Composable
private fun ActionChip(label: String, isDarkTheme: Boolean, onClick: () -> Unit) {
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
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = ModernBlue,
            fontWeight = FontWeight.SemiBold
        )
    }
}

