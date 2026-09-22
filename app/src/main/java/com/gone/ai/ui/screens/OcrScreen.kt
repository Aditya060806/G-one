package com.gone.ai.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gone.ai.ocr.TaskStatus
import com.gone.ai.ocr.isRunning
import com.gone.ai.ui.components.AppSettings
import com.gone.ai.ui.theme.*
import com.gone.ai.viewmodel.OcrAction
import com.gone.ai.viewmodel.OcrUiState
import com.gone.ai.viewmodel.OcrViewModel
import java.io.File

@Composable
fun OcrScreen(
    isDarkTheme: Boolean,
    bottomPadding: Dp,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit = {},
    onAskAssistant: (title: String, text: String) -> Unit = { _, _ -> },
    /** An image shared from another app, read as soon as the screen appears. */
    initialUri: Uri? = null,
    onInitialUriTaken: () -> Unit = {},
    vm: OcrViewModel = viewModel()
) {
    LaunchedEffect(initialUri) {
        initialUri?.let {
            vm.extractText(it)
            onInitialUriTaken()
        }
    }

    val uiState           by vm.uiState.collectAsState()
    val extractedText     by vm.extractedText.collectAsState()
    val resultText        by vm.task.output.collectAsState()
    val taskStatus        by vm.task.status.collectAsState()
    val saved             by vm.task.saved.collectAsState()
    val lastAction        by vm.lastAction.collectAsState()
    val truncationNotice  by vm.truncationNotice.collectAsState()
    val context           = LocalContext.current
    var cameraBlocked     by remember { mutableStateOf(false) }

    // ── Camera URI ────────────────────────────────────────────────────────────
    var cameraUriText by rememberSaveable { mutableStateOf<String?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok -> if (ok) cameraUriText?.let { vm.extractText(Uri.parse(it)) } }

    fun launchCamera() {
        val file = File.createTempFile("ocr_", ".jpg", context.cacheDir)
        val uri  = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        cameraUriText = uri.toString()
        cameraLauncher.launch(uri)
    }

    val cameraPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        when {
            granted -> launchCamera()
            AppSettings.isBlockedAfterDenial(context, Manifest.permission.CAMERA) -> cameraBlocked = true
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { vm.extractText(it) } }

    val onCamera: () -> Unit = {
        val hasCam = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        if (hasCam) launchCamera() else cameraPermLauncher.launch(Manifest.permission.CAMERA)
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
            FeatureHeader(
                title       = "OCR Scanner",
                isDarkTheme = isDarkTheme,
                uiState     = when (uiState) {
                    is OcrUiState.Idle       -> "Ready"
                    is OcrUiState.Extracting -> "Reading..."
                    is OcrUiState.TextReady  -> if (taskStatus.isRunning) "Processing..." else "Text extracted"
                    is OcrUiState.Error      -> "Error"
                },
                dotColor    = when (uiState) {
                    is OcrUiState.Error      -> ErrorRed
                    is OcrUiState.Extracting -> WarnAmber
                    is OcrUiState.TextReady  -> if (taskStatus.isRunning) ModernBlue else SuccessGreen
                    else                     -> if (isDarkTheme) TextSecondary else TextSecondaryLight
                },
                onBack         = onNavigateBack,
                onNavigateHome = onNavigateHome,
                showReset      = uiState != OcrUiState.Idle,
                onReset        = { vm.reset() }
            )
            if (truncationNotice && uiState == OcrUiState.TextReady) {
                TruncationNotice(
                    "This is a long text. Only its first part fits in one answer.",
                    isDarkTheme
                )
            }

            when (uiState) {
                is OcrUiState.Idle -> OcrIdleState(
                    isDarkTheme = isDarkTheme,
                    onGallery   = { galleryLauncher.launch("image/*") },
                    onCamera    = onCamera
                )
                is OcrUiState.Extracting -> CenteredSpinner("Reading image...", WarnAmber, isDarkTheme, isScanning = true)
                is OcrUiState.TextReady -> {
                    val title = "OCR – ${lastAction?.label ?: "Result"}"
                    OcrReadyBody(
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

    if (cameraBlocked) {
        AlertDialog(
            onDismissRequest = { cameraBlocked = false },
            title = { Text("Camera access is off") },
            text = { Text("To photograph a page, allow the camera for G-one in Settings. You can still pick a photo from the gallery.") },
            confirmButton = {
                TextButton(onClick = {
                    cameraBlocked = false
                    AppSettings.open(context)
                }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = { cameraBlocked = false }) { Text("Not now") } }
        )
    }
}

@Composable
internal fun TruncationNotice(message: String, isDarkTheme: Boolean) {
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
            message,
            style = MaterialTheme.typography.labelSmall,
            color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
            fontWeight = FontWeight.Medium
        )
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
                com.gone.ai.ui.components.WatchScanningLottieAnimation(
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
                            "Text is read on this phone, without internet. Printed lab reports, prescriptions and notes work best; handwriting may not be read.",
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
    taskStatus   : TaskStatus,
    saved        : Boolean,
    isDarkTheme  : Boolean,
    onAction     : (OcrAction) -> Unit,
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
                    tint = if (isDarkTheme) TextPrimary else ModernBlue
                )
                Spacer(Modifier.width(4.dp))
                Text("New Image", style = MaterialTheme.typography.labelMedium, color = if (isDarkTheme) TextPrimary else ModernBlue, fontWeight = FontWeight.SemiBold)
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

/** The first lines of what was read, so the person can check it before asking for anything. */
@Composable
internal fun ExtractedTextPreview(extractedText: String, isDarkTheme: Boolean, label: String = "EXTRACTED TEXT") {
    var expanded by remember(extractedText) { mutableStateOf(false) }
    val long = extractedText.length > 200
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
            .then(
                if (long) Modifier.clickable(
                    role = Role.Button,
                    onClickLabel = if (expanded) "Show less" else "Show all text",
                    onClick = { expanded = !expanded }
                ) else Modifier
            )
            .padding(20.dp)
    ) {
        Column {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (isDarkTheme) TextSecondary else TextSecondaryLight,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
            Text(
                if (long && !expanded) extractedText.take(200) + "…" else extractedText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                lineHeight = 18.sp,
                modifier = if (expanded) Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()) else Modifier
            )
            }
            if (long) {
                Text(
                    if (expanded) "Show less" else "Show all",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isDarkTheme) AccentGoldBg else ModernBlue,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
internal fun ActionChip(label: String, isDarkTheme: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .shadow(
                elevation = if (isDarkTheme) 0.dp else 2.dp,
                shape = RoundedCornerShape(22.dp),
                ambientColor = LightShadow,
                spotColor = LightShadow
            )
            .clip(RoundedCornerShape(22.dp))
            .background(if (isDarkTheme) Color(0xFF1E293B) else ModernBlueSubtle)
            .border(1.dp, ModernBlue.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (isDarkTheme) TextPrimary else ModernBlue,
            fontWeight = FontWeight.SemiBold
        )
    }
}
