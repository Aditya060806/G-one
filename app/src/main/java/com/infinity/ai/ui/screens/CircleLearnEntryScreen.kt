package com.infinity.ai.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infinity.ai.circle.InfinityOverlayService
import com.infinity.ai.circle.OverlayPermissionHelper
import com.infinity.ai.ui.components.BreadcrumbHeader
import com.infinity.ai.ui.components.BreadcrumbItem
import com.infinity.ai.ui.components.PureBreadcrumbText
import com.infinity.ai.ui.components.GlassCard
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.infinity.ai.ui.theme.*

@Composable
fun CircleLearnEntryScreen(
    isDarkTheme   : Boolean,
    bottomPadding : Dp,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit = {}
) {
    val context = LocalContext.current
    var serviceRunning  by remember { mutableStateOf(false) }
    var overlayGranted  by remember { mutableStateOf(OverlayPermissionHelper.hasOverlayPermission(context)) }
    var projectionData  by remember { mutableStateOf<Intent?>(null) }
    var showOnboarding  by remember { mutableStateOf(true) }

    // Check overlay permission on resume
    LaunchedEffect(Unit) {
        overlayGranted = OverlayPermissionHelper.hasOverlayPermission(context)
    }

    // MediaProjection permission launcher
    val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            projectionData = result.data
            startOverlayService(context, result.resultCode, result.data!!)
            serviceRunning = true
        }
    }

    // Notification permission launcher (API 33+)
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* proceed regardless */ }

    fun startService() {
        if (!overlayGranted) {
            OverlayPermissionHelper.requestOverlayPermission(context as Activity)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !OverlayPermissionHelper.hasNotificationPermission(context)) {
            notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    fun stopService() {
        val intent = Intent(context, InfinityOverlayService::class.java).apply {
            action = InfinityOverlayService.ACTION_STOP
        }
        context.stopService(intent)
        serviceRunning = false
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
            // Sticky breadcrumb text on top (background-less, border-less)
            PureBreadcrumbText(
                items = listOf(
                    BreadcrumbItem("Home", onNavigateHome),
                    BreadcrumbItem("Tools", onNavigateBack),
                    BreadcrumbItem("Circle Learn")
                ),
                isDarkTheme = isDarkTheme,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                FeatureHeroIdleCard(
                    title = if (serviceRunning) "Circle Learn is Active" else "Circle Learn AI",
                    subtitle = if (serviceRunning)
                        "The assistant bubble is floating over your apps.\nTap it anytime to circle and analyze text instantly."
                    else
                        "Circle any text, image, or graph on your screen.\nPowered by on-device AI — fully offline & private.",
                    isDarkTheme = isDarkTheme,
                    lottieContent = {
                        if (serviceRunning) {
                            com.infinity.ai.ui.components.WatchScanningLottieAnimation(
                                modifier = Modifier.size(115.dp)
                            )
                        } else {
                            com.infinity.ai.ui.components.DoctorLottieAnimation(
                                modifier = Modifier.size(125.dp)
                            )
                        }
                    },
                    actionContent = {
                        val ctaInteraction = remember { MutableInteractionSource() }
                        val haptic = LocalHapticFeedback.current
                        val isPressed by ctaInteraction.collectIsPressedAsState()
                        val ctaElevation by animateDpAsState(
                            targetValue = if (isPressed) 1.dp else (if (isDarkTheme) 0.dp else 4.dp),
                            label = "ctaElevation"
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pressScale(ctaInteraction, pressedScale = 0.96f)
                                .shadow(
                                    elevation = ctaElevation,
                                    shape = RoundedCornerShape(20.dp),
                                    ambientColor = LightShadow,
                                    spotColor = LightShadow
                                )
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (serviceRunning) ErrorRed else ModernBlue)
                                .clickable(
                                    interactionSource = ctaInteraction,
                                    indication = null,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        if (serviceRunning) stopService() else startService()
                                    }
                                )
                                .padding(vertical = 18.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    if (serviceRunning) Icons.Default.Stop else Icons.Default.RadioButtonChecked,
                                    null,
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                                Text(
                                    if (serviceRunning) "Stop Circle Learn" else "Start Circle Learn",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    },
                    noteContent = {
                        if (!serviceRunning) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                PermissionCard(
                                    "Overlay Permission",
                                    "Required to show the floating bubble",
                                    overlayGranted,
                                    isDarkTheme,
                                    Icons.Default.Layers
                                )
                                PermissionCard(
                                    "Screen Capture",
                                    "Required to capture and analyze screen content",
                                    projectionData != null,
                                    isDarkTheme,
                                    Icons.Default.Screenshot
                                )
                            }
                        }
                    }
                )

                Spacer(Modifier.height(16.dp))

                // How it works Bento Card
                if (!serviceRunning) {
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
                                "HOW IT WORKS",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isDarkTheme) TextSecondary else TextSecondaryLight,
                                letterSpacing = 1.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(16.dp))
                            listOf(
                                Triple(Icons.Default.TouchApp, "1. Tap the floating bubble", "Appears over any open app"),
                                Triple(Icons.Default.CropFree, "2. Select screen region", "Drag a box around text or diagram"),
                                Triple(Icons.Default.DocumentScanner, "3. On-device OCR", "Extracts text offline with ML Kit"),
                                Triple(Icons.Default.AutoAwesome, "4. AI Synthesis", "Explain, summarize, generate flashcards"),
                                Triple(Icons.Default.BookmarkAdd, "5. Save to Library", "Store to Knowledge Vault")
                            ).forEach { (icon, title, sub) ->
                                HowItWorksStep(icon, title, sub, isDarkTheme)
                                Spacer(Modifier.height(10.dp))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun PermissionCard(
    title      : String,
    subtitle   : String,
    granted    : Boolean,
    isDarkTheme: Boolean,
    icon       : ImageVector
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isDarkTheme) Color(0xFF1E293B).copy(alpha = 0.5f) else ModernBlueSubtle.copy(alpha = 0.5f))
            .border(
                1.dp,
                if (isDarkTheme) ModernBorderDark else ModernBorderLight,
                RoundedCornerShape(16.dp)
            )
            .padding(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(
                        if (granted) SuccessGreen.copy(0.15f) else WarnAmber.copy(0.15f),
                        RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon, null,
                    tint = if (granted) SuccessGreen else WarnAmber,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDarkTheme) TextSecondary else TextSecondaryLight
                )
            }
            Icon(
                if (granted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                null,
                tint = if (granted) SuccessGreen else WarnAmber,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun HowItWorksStep(
    icon      : ImageVector,
    title     : String,
    subtitle  : String,
    isDarkTheme: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(if (isDarkTheme) Color(0xFF1E293B) else ModernBlueSubtle, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = ModernBlue, modifier = Modifier.size(18.dp))
        }
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (isDarkTheme) TextSecondary else TextSecondaryLight
            )
        }
    }
}

private fun startOverlayService(context: Context, resultCode: Int, data: Intent) {
    val intent = Intent(context, InfinityOverlayService::class.java).apply {
        action = InfinityOverlayService.ACTION_START
        putExtra(InfinityOverlayService.EXTRA_RESULT_CODE, resultCode)
        putExtra(InfinityOverlayService.EXTRA_RESULT_DATA, data)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        context.startForegroundService(intent)
    else
        context.startService(intent)
}
