package com.infinity.ai.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.infinity.ai.ai.state.AIInferenceState
import com.infinity.ai.ui.components.BreadcrumbHeader
import com.infinity.ai.ui.components.BreadcrumbItem
import com.infinity.ai.ui.components.PureBreadcrumbText
import com.infinity.ai.ui.components.GradientBackground
import com.infinity.ai.ui.components.GlassCard
import com.infinity.ai.ui.theme.*
import androidx.compose.ui.zIndex

/**
 * @param aiState hoisted in from [com.infinity.ai.ui.navigation.AppNavigation].
 *
 * Deliberately NOT obtained via `viewModel()` here. Inside a `composable {}` block
 * the LocalViewModelStoreOwner is the NavBackStackEntry, so calling `viewModel()`
 * created a *second*, route-scoped ChatViewModel. Popping Settings then ran that
 * instance's onCleared(), which used to unload the shared model. Hoisting the
 * state removes the duplicate ViewModel entirely.
 */
@Composable
fun SettingsScreen(
    isDarkTheme: Boolean,
    bottomPadding: Dp,
    aiState: AIInferenceState,
    onToggleTheme: () -> Unit,
    onNavigateHome: () -> Unit = {},
    onRedoOnboarding: () -> Unit = {}
) {
    val scroll = rememberScrollState()

    GradientBackground(darkTheme = isDarkTheme, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().verticalScroll(scroll)) {
            Spacer(Modifier.height(56.dp))

            SettingsBodyContent(
                isDarkTheme = isDarkTheme,
                aiState = aiState,
                onToggleTheme = onToggleTheme,
                onRedoOnboarding = onRedoOnboarding
            )

            Spacer(Modifier.height(bottomPadding + 16.dp))
        }

        // ── Sticky Breadcrumb Header (Background-less, border-less text pinned at top) ───
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 20.dp, top = 16.dp)
                .zIndex(10f)
        ) {
            PureBreadcrumbText(
                items = listOf(
                    BreadcrumbItem("Home", onNavigateHome),
                    BreadcrumbItem("Settings")
                ),
                isDarkTheme = isDarkTheme
            )
        }
    }
}

// ── Settings Bottom Sheet (Drawer Style matching Chats and Alerts) ─────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    isDarkTheme: Boolean,
    aiState: AIInferenceState,
    onToggleTheme: () -> Unit,
    onDismiss: () -> Unit,
    onRedoOnboarding: () -> Unit = {}
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor   = if (isDarkTheme) DarkSurfaceElevated else CardBg,
        dragHandle = {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (isDarkTheme) DarkBorder else TokenBorder)
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding()
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Settings & Setup",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isDarkTheme) TextPrimary else BaseFg,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Device calibration, AI engine & system controls",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isDarkTheme) TextSecondary else MutedFg
                    )
                }

                TextButton(onClick = onDismiss) {
                    Text(
                        "Done",
                        fontWeight = FontWeight.SemiBold,
                        color = if (isDarkTheme) AccentGoldBg else PrimaryBg
                    )
                }
            }

            HorizontalDivider(color = if (isDarkTheme) DarkBorder else TokenBorder)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp)
            ) {
                Spacer(Modifier.height(16.dp))
                SettingsBodyContent(
                    isDarkTheme = isDarkTheme,
                    aiState = aiState,
                    onToggleTheme = onToggleTheme,
                    onRedoOnboarding = onRedoOnboarding
                )
            }
        }
    }
}

// ── Shared Settings Body Content ──────────────────────────────────────────────

@Composable
fun SettingsBodyContent(
    isDarkTheme: Boolean,
    aiState: AIInferenceState,
    onToggleTheme: () -> Unit,
    onRedoOnboarding: () -> Unit = {}
) {
    val context = LocalContext.current

    val micGranted = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }
    val notifGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        remember {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        }
    } else {
        true
    }
    val storageGranted = remember {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) true
        else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
    }

    // Profile card
    val prefs = remember { context.getSharedPreferences("gone_preferences", Context.MODE_PRIVATE) }
    val currentUserName = remember { prefs.getString("user_name", "Olga") ?: "Olga" }
    val currentUserAge = remember { prefs.getInt("user_age", 18) }

    GlassCard(
        darkTheme = isDarkTheme,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            com.infinity.ai.ui.components.WatchScanningLottieAnimation(
                modifier = Modifier.size(48.dp)
            )
            Column {
                Text(
                    currentUserName,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Age $currentUserAge • 100% Local Sentinel",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDarkTheme) TextSecondary else TextSecondaryLight
                )
            }
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Default.ChevronRight, null,
                tint = if (isDarkTheme) TextSecondary else TextSecondaryLight,
                modifier = Modifier.size(17.dp)
            )
        }
    }

    Spacer(Modifier.height(20.dp))

    SettingsSection("Clinical Calibration", isDarkTheme) {
        SettingsRow(Icons.Default.AccountBox, "User Name", currentUserName, Blue500, isDarkTheme)
        SettingsDivider(isDarkTheme)
        SettingsRow(Icons.Default.CalendarMonth, "Calibrated Age", "$currentUserAge years", Blue500, isDarkTheme)
        SettingsDivider(isDarkTheme)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onRedoOnboarding() }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(if (isDarkTheme) AccentGoldBg.copy(alpha = 0.15f) else Blue500.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.RestartAlt, null,
                        tint = Blue500,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        "Redo Onboarding",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Re-enter name, age, or reset welcome flow",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isDarkTheme) TextSecondary else TextSecondaryLight
                    )
                }
            }
            Icon(
                Icons.Default.ChevronRight, null,
                tint = if (isDarkTheme) TextSecondary else TextSecondaryLight,
                modifier = Modifier.size(18.dp)
            )
        }
    }

    Spacer(Modifier.height(12.dp))

    SettingsSection("Appearance", isDarkTheme) {
        SettingsToggle(
            Icons.Default.DarkMode, "Dark Mode",
            if (isDarkTheme) "Dark theme active" else "Light theme active",
            isDarkTheme, onToggleTheme, isDarkTheme
        )
    }

    Spacer(Modifier.height(12.dp))

    SettingsSection("AI Engine", isDarkTheme) {
        val (modelLabel, modelColor) = when (aiState) {
            is AIInferenceState.Idle       -> "Ready" to SuccessGreen
            is AIInferenceState.Loading    -> "Loading..." to WarnAmber
            is AIInferenceState.Thinking   -> "Thinking..." to Blue500
            is AIInferenceState.Responding -> "Responding..." to Blue500
            is AIInferenceState.Error      -> "Error" to ErrorRed
        }
        SettingsRow(Icons.Default.Memory, "Local Model", modelLabel, modelColor, isDarkTheme)
        SettingsDivider(isDarkTheme)
        SettingsRow(Icons.Default.Speed, "Response Mode", "Balanced", Blue500, isDarkTheme)
        SettingsDivider(isDarkTheme)
        SettingsRow(Icons.Default.Language, "Language", "English", Blue500, isDarkTheme)
    }

    Spacer(Modifier.height(12.dp))

    SettingsSection("Permissions", isDarkTheme) {
        SettingsRowBadge(Icons.Default.Mic, "Microphone", "Required for voice", micGranted, isDarkTheme)
        SettingsDivider(isDarkTheme)
        SettingsRowBadge(Icons.Default.Notifications, "Notifications", "For AI alerts", notifGranted, isDarkTheme)
        SettingsDivider(isDarkTheme)
        SettingsRowBadge(Icons.Default.FolderOpen, "Storage", "For file analyzer", storageGranted, isDarkTheme)
    }

    Spacer(Modifier.height(12.dp))

    SettingsSection("About", isDarkTheme) {
        SettingsRow(
            Icons.Default.Info, "Version", "1.0.0",
            if (isDarkTheme) TextSecondary else TextSecondaryLight, isDarkTheme
        )
        SettingsDivider(isDarkTheme)
        SettingsRow(
            Icons.Default.Memory, "Engine", "llama.cpp · arm64-v8a",
            if (isDarkTheme) TextSecondary else TextSecondaryLight, isDarkTheme
        )
        SettingsDivider(isDarkTheme)
        SettingsRow(
            Icons.Default.Psychology, "Model", "Qwen2.5-1.5B · Q4_K_M",
            if (isDarkTheme) TextSecondary else TextSecondaryLight, isDarkTheme
        )
        SettingsDivider(isDarkTheme)
        SettingsRow(
            Icons.Default.CloudOff, "Network", "Fully offline",
            if (isDarkTheme) TextSecondary else TextSecondaryLight, isDarkTheme
        )
    }

    Spacer(Modifier.height(36.dp))

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.MonitorHeart, null,
            tint = if (isDarkTheme) TextDisabled else TextSecondaryLight.copy(0.4f),
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "G-one · v1.0.0",
            style = MaterialTheme.typography.labelSmall,
            color = if (isDarkTheme) TextDisabled else TextSecondaryLight.copy(0.4f)
        )
    }
}

@Composable
private fun SettingsSection(title: String, isDarkTheme: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall,
            color = if (isDarkTheme) TextSecondary else TextSecondaryLight,
            fontWeight = FontWeight.Medium, letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp))
        GlassCard(darkTheme = isDarkTheme, modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

@Composable
private fun SettingsDivider(isDarkTheme: Boolean) {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp),
        color = if (isDarkTheme) Color.White.copy(0.05f) else Color.Black.copy(0.05f)
    )
}

@Composable
private fun SettingsToggle(
    icon: ImageVector, title: String, subtitle: String,
    checked: Boolean, onToggle: () -> Unit, isDarkTheme: Boolean
) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(modifier = Modifier.size(36.dp)
            .background(if (isDarkTheme) AccentGoldBg.copy(alpha = 0.15f) else Blue500.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (isDarkTheme) AccentGoldBg else Blue500, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = if (isDarkTheme) TextPrimary else TextPrimaryLight, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = if (isDarkTheme) TextSecondary else TextSecondaryLight)
        }
        Switch(checked = checked, onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedTrackColor = if (isDarkTheme) AccentGoldBg else PrimaryBg,
                checkedThumbColor = if (isDarkTheme) PrimaryBg else Color.White,
                uncheckedTrackColor = if (isDarkTheme) Color(0xFF383838) else Color(0xFFE2E8F0),
                uncheckedThumbColor = if (isDarkTheme) Color(0xFFA1A19A) else Color.White,
                uncheckedBorderColor = Color.Transparent
            ))
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, value: String,
                        iconTint: Color, isDarkTheme: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(modifier = Modifier.size(36.dp)
            .background(iconTint.copy(0.12f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
        }
        Text(title, style = MaterialTheme.typography.bodyLarge,
            color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
            fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium,
            color = if (isDarkTheme) TextSecondary else TextSecondaryLight)
    }
}

@Composable
private fun SettingsRowBadge(icon: ImageVector, title: String, subtitle: String,
                              granted: Boolean, isDarkTheme: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(modifier = Modifier.size(36.dp)
            .background(
                if (granted) SuccessGreen.copy(0.12f) else ErrorRed.copy(0.12f),
                RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center) {
            Icon(icon, null,
                tint = if (granted) SuccessGreen else ErrorRed,
                modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = if (isDarkTheme) TextPrimary else TextPrimaryLight, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = if (isDarkTheme) TextSecondary else TextSecondaryLight)
        }
        Box(modifier = Modifier
            .background(
                if (granted) SuccessGreen.copy(0.12f) else ErrorRed.copy(0.12f),
                RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)) {
            Text(if (granted) "Granted" else "Denied",
                style = MaterialTheme.typography.labelSmall,
                color = if (granted) SuccessGreen else ErrorRed)
        }
    }
}

@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String,
    iconTint: Color,
    isLoading: Boolean,
    isDarkTheme: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isLoading, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(iconTint.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                fontWeight = FontWeight.Medium
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (isDarkTheme) TextSecondary else TextSecondaryLight
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (isLoading) Blue500.copy(alpha = 0.12f) else Blue500)
                .clickable(enabled = !isLoading, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = Blue500
                )
            } else {
                Text(
                    actionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

