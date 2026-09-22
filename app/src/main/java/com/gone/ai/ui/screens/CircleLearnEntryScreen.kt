package com.gone.ai.ui.screens

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
import com.gone.ai.circle.InfinityOverlayService
import com.gone.ai.circle.OverlayPermissionHelper
import com.gone.ai.ui.components.BreadcrumbHeader
import com.gone.ai.ui.components.BreadcrumbItem
import com.gone.ai.ui.components.PureBreadcrumbText
import com.gone.ai.ui.components.GlassCard
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gone.ai.ui.theme.*

@Composable
fun CircleLearnEntryScreen(
    isDarkTheme   : Boolean,
    bottomPadding : Dp,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit = {}
) {
    val context = LocalContext.current
    // The service's own state, so leaving and reopening this screen shows the truth.
    val serviceRunning by InfinityOverlayService.isRunning.collectAsState()
    val captureLost    by InfinityOverlayService.captureLost.collectAsState()
    var overlayGranted by remember { mutableStateOf(OverlayPermissionHelper.hasOverlayPermission(context)) }

    // Re-check on every return: the overlay permission is granted on a system Settings page.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = OverlayPermissionHelper.hasOverlayPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // MediaProjection permission launcher
    val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startOverlayService(context, result.resultCode, result.data!!)
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
        // A fresh consent every start: Android gives one screen capture per consent.
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    fun stopService() {
        val intent = Intent(context, InfinityOverlayService::class.java).apply {
            action = InfinityOverlayService.ACTION_STOP
        }
        context.stopService(intent)
    }

    val active = serviceRunning && !captureLost

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
                    title = when {
                        active -> "Circle Learn is Active"
                        serviceRunning -> "Circle Learn is Paused"
                        else -> "Circle Learn AI"
                    },
                    subtitle = when {
                        active -> "The bubble is floating over your apps.\nTap it any time to circle text and ask about it."
                        serviceRunning -> "Android stopped screen capture, for example when the phone locked.\nTurn it back on to keep using the bubble."
                        else -> "Circle any text on your screen and ask G-one about it.\nRead and answered on this phone, without internet."
                    },
                    isDarkTheme = isDarkTheme,
                    lottieContent = {
                        if (active) {
                            com.gone.ai.ui.components.WatchScanningLottieAnimation(
                                modifier = Modifier.size(115.dp)
                            )
                        } else {
                            com.gone.ai.ui.components.DoctorLottieAnimation(
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
                                .background(if (active) ErrorRed else if (isDarkTheme) AccentGoldBg else ModernBlue)
                                .clickable(
                                    interactionSource = ctaInteraction,
                                    indication = null,
                                    role = Role.Button,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        if (active) stopService() else startService()
                                    }
                                )
                                .padding(vertical = 18.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                val ctaColor = if (!active && isDarkTheme) AccentGoldFg else Color.White
                                Icon(
                                    if (active) Icons.Default.Stop else Icons.Default.RadioButtonChecked,
                                    null,
                                    tint = ctaColor,
                                    modifier = Modifier.size(22.dp)
                                )
                                Text(
                                    when {
                                        active -> "Stop Circle Learn"
                                        serviceRunning -> "Turn screen capture back on"
                                        else -> "Start Circle Learn"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = ctaColor,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    },
                    noteContent = {
                        if (!active) {
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
                                    "Asked each time you start: Android allows one capture per consent",
                                    active,
                                    isDarkTheme,
                                    Icons.Default.Screenshot
                                )
                                if (serviceRunning) {
                                    TextButton(onClick = { stopService() }, modifier = Modifier.heightIn(min = 48.dp)) {
                                        Text("Stop Circle Learn instead", color = if (isDarkTheme) TextSecondary else TextSecondaryLight)
                                    }
                                }
                            }
                        }
                    }
                )

                Spacer(Modifier.height(16.dp))

                // How it works Bento Card
                if (!active) {
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
                                Triple(Icons.Default.DocumentScanner, "3. Text is read on the phone", "No internet needed"),
                                Triple(Icons.Default.AutoAwesome, "4. Choose what to do", "Explain, summarise, make flashcards"),
                                Triple(Icons.Default.BookmarkAdd, "5. Keep it", "Save to your Vault or ask G-one more")
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
