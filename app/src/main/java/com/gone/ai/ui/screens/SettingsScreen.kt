package com.gone.ai.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gone.ai.ai.state.AIInferenceState
import com.gone.ai.health.sos.AndroidSosSender
import com.gone.ai.health.sos.SosNumbers
import com.gone.ai.health.sos.SosPreferences
import com.gone.ai.health.sos.SosRecord
import com.gone.ai.health.sos.sendTestSos
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import com.gone.ai.data.UserProfile
import com.gone.ai.health.service.HealthMonitoringService
import com.gone.ai.health.service.ReportArtifacts
import com.gone.ai.health.service.MonitoringDataSource
import com.gone.ai.ui.components.BreadcrumbHeader
import com.gone.ai.ui.components.BreadcrumbItem
import com.gone.ai.ui.components.PureBreadcrumbText
import com.gone.ai.ui.components.GradientBackground
import com.gone.ai.ui.components.GlassCard
import com.gone.ai.ui.theme.*
import com.gone.ai.voice.SpeechInput
import androidx.compose.ui.zIndex

/**
 * @param aiState hoisted in from [com.gone.ai.ui.navigation.AppNavigation].
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
    onRedoOnboarding: () -> Unit = {},
    onOpenDevice: () -> Unit = {},
    onOpenReports: () -> Unit = {},
    onOpenEmergencyId: () -> Unit = {}
) {
    val scroll = rememberScrollState()

    GradientBackground(darkTheme = isDarkTheme, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().verticalScroll(scroll)) {
            Spacer(Modifier.height(56.dp))

            SettingsBodyContent(
                isDarkTheme = isDarkTheme,
                aiState = aiState,
                onToggleTheme = onToggleTheme,
                onRedoOnboarding = onRedoOnboarding,
                onOpenDevice = onOpenDevice,
                onOpenReports = onOpenReports,
                onOpenEmergencyId = onOpenEmergencyId
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
    onRedoOnboarding: () -> Unit = {},
    onOpenDevice: () -> Unit = {},
    onOpenReports: () -> Unit = {},
    onOpenEmergencyId: () -> Unit = {}
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
                    onRedoOnboarding = onRedoOnboarding,
                    onOpenDevice = onOpenDevice,
                    onOpenReports = onOpenReports,
                    onOpenEmergencyId = onOpenEmergencyId
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
    onRedoOnboarding: () -> Unit = {},
    onOpenDevice: () -> Unit = {},
    onOpenReports: () -> Unit = {},
    onOpenEmergencyId: () -> Unit = {}
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
    // No storage row: files are opened through the system picker, which needs no storage
    // permission — and the manifest never declared one, so that row always read "Denied".

    // Profile card. Unset fields say so instead of showing a placeholder person.
    var profile by remember { mutableStateOf(UserProfile.load(context)) }
    var editingProfile by remember { mutableStateOf(false) }
    val appVersion = remember { appVersionName(context) }
    val currentUserName = profile.name ?: "Name not set"
    val ageLabel = profile.age?.let { "$it years" } ?: "Not set"
    val simulatedVitals by MonitoringDataSource.simulationEnabled(context).collectAsState()
    val savedWearable by MonitoringDataSource.wearable(context).collectAsState()

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
            com.gone.ai.ui.components.WatchScanningLottieAnimation(
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
                    (profile.age?.let { "Age $it • " } ?: "") + "Runs entirely on this device",
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

    SettingsSection("Profile", isDarkTheme) {
        SettingsActionRow(
            Icons.Default.AccountBox, "Name, age and emergency contact",
            listOf(currentUserName, ageLabel, profile.emergencyContact ?: "No emergency contact").joinToString(" · "),
            "Edit", Blue500, isLoading = false, isDarkTheme = isDarkTheme, onClick = { editingProfile = true }
        )
        SettingsDivider(isDarkTheme)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { onOpenEmergencyId() }
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
                        .background(Color(0xFFC0392B).copy(alpha = if (isDarkTheme) 0.2f else 0.12f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.CreditCard, null,
                        tint = Color(0xFFC0392B),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        "Emergency Medical ID",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "NFC tag & QR code for first responders",
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
        SettingsDivider(isDarkTheme)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { onRedoOnboarding() }
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
                        "Go through the welcome steps again",
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

    SosSettingsSection(
        profile = profile,
        isDarkTheme = isDarkTheme,
        onEditProfile = { editingProfile = true }
    )

    Spacer(Modifier.height(12.dp))

    if (editingProfile) {
        ProfileEditDialog(
            profile = profile,
            onDismiss = { editingProfile = false },
            onSave = { name, age, contact ->
                UserProfile.save(context, name, age, contact)
                profile = UserProfile.load(context)
                editingProfile = false
            }
        )
    }

    SettingsSection("Monitoring data", isDarkTheme) {
        SettingsActionRow(
            Icons.Default.Bluetooth, "Wearable",
            savedWearable?.let { "${it.name} · used when simulated vitals are off" } ?: "None chosen",
            if (savedWearable == null) "Choose" else "Open",
            Blue500, isLoading = false, isDarkTheme = isDarkTheme, onClick = onOpenDevice
        )
        SettingsDivider(isDarkTheme)
        SettingsToggle(
            Icons.Default.Science, "Simulated vitals",
            when {
                simulatedVitals -> "On — monitoring streams simulated readings, not the wearable"
                savedWearable != null -> "Off — monitoring streams from the wearable"
                else -> "Off — choose a wearable, or turn this on to try monitoring"
            },
            simulatedVitals,
            {
                val enable = !simulatedVitals
                MonitoringDataSource.setSimulationEnabled(context, enable)
                // Switching source must not leave monitoring running on the old one.
                HealthMonitoringService.stop(context)
            },
            isDarkTheme
        )
        SettingsDivider(isDarkTheme)
        SettingsActionRow(
            Icons.Default.Description, "Session reports",
            "Reports from monitoring sessions you started and ended",
            "Open", Blue500, isLoading = false, isDarkTheme = isDarkTheme, onClick = onOpenReports
        )
        SettingsDivider(isDarkTheme)
        var autoSummaries by remember { mutableStateOf(ReportArtifacts.autoSummaries(context)) }
        SettingsToggle(
            Icons.Default.AutoAwesome, "Report summaries",
            if (autoSummaries) "On — the on-device model adds a checked summary when a session ends"
            else "Off — add one from a report when you want it",
            autoSummaries,
            {
                autoSummaries = !autoSummaries
                ReportArtifacts.setAutoSummaries(context, autoSummaries)
            },
            isDarkTheme
        )
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
        SettingsRow(Icons.Default.Language, "Explanations", "English", Blue500, isDarkTheme)
    }

    Spacer(Modifier.height(12.dp))

    SettingsSection("Permissions", isDarkTheme) {
        SettingsRowBadge(Icons.Default.Mic, "Microphone", "Required for voice", micGranted, isDarkTheme)
        SettingsDivider(isDarkTheme)
        SettingsRowBadge(Icons.Default.Notifications, "Notifications", "For health alerts", notifGranted, isDarkTheme)
    }

    Spacer(Modifier.height(12.dp))

    SettingsSection("About", isDarkTheme) {
        SettingsRow(
            Icons.Default.Info, "Version", appVersion,
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
        val speechOnDevice = remember { SpeechInput(context).onDevice }
        SettingsRow(
            Icons.Default.CloudOff, "Network",
            if (speechOnDevice) "Health AI offline · AQI optional online" else "AI offline · voice and optional AQI may go online",
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
            "G-one · v$appVersion",
            style = MaterialTheme.typography.labelSmall,
            color = if (isDarkTheme) TextDisabled else TextSecondaryLight.copy(0.4f)
        )
    }
}

/** Name, age and emergency contact, without redoing onboarding. */
@Composable
private fun ProfileEditDialog(
    profile: UserProfile,
    onDismiss: () -> Unit,
    onSave: (name: String, age: Int?, contact: String) -> Unit
) {
    var name by remember { mutableStateOf(profile.name.orEmpty()) }
    var ageText by remember { mutableStateOf(profile.age?.toString().orEmpty()) }
    var contact by remember { mutableStateOf(profile.emergencyContact.orEmpty()) }
    val age = ageText.trim().toIntOrNull()
    val ageError = ageText.isNotBlank() && (age == null || age !in 1..110)
    val nameError = name.isBlank()
    val contactError = contact.isNotBlank() && SosNumbers.normalize(contact) == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = nameError,
                    supportingText = if (nameError) ({ Text("Name is required") }) else null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next)
                )
                OutlinedTextField(
                    value = ageText,
                    onValueChange = { ageText = it.filter(Char::isDigit).take(3) },
                    label = { Text("Age") },
                    singleLine = true,
                    isError = ageError,
                    supportingText = if (ageError) ({ Text("Enter an age from 1 to 110") }) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
                )
                OutlinedTextField(
                    value = contact,
                    onValueChange = { contact = it },
                    label = { Text("Emergency contact phone") },
                    singleLine = true,
                    isError = contactError,
                    supportingText = {
                        Text(
                            if (contactError) "Enter a phone number: digits, with + for a country code"
                            else "With Automatic SOS on, G-one texts, then calls, this number in an emergency."
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), age, contact.trim()) }, enabled = !nameError && !ageError && !contactError) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Automatic SOS: on or off, whether to call after the text, how long "I'm OK" has, and a test.
 *
 * Turning it on is where Android asks, once, to let G-one send texts and make calls; without that
 * the switch stays off. The number is the profile's emergency contact.
 */
@Composable
private fun SosSettingsSection(
    profile: UserProfile,
    isDarkTheme: Boolean,
    onEditProfile: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by SosPreferences.settings(context).collectAsState()
    val records by SosPreferences.records(context).collectAsState()
    val number = remember(profile) { SosNumbers.normalize(profile.emergencyContact) }

    // Read again on return to the app: the permission can be withdrawn in Android's settings.
    var allowed by remember { mutableStateOf(AndroidSosSender.hasPermissions(context)) }
    var enableAfterPermission by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showSmsPermissionHelp by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var permissionSettingsError by remember { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allowed = AndroidSosSender.hasPermissions(context)
                if (allowed) {
                    showSmsPermissionHelp = false
                    if (enableAfterPermission) {
                        SosPreferences.update(context) { it.copy(enabled = true) }
                        enableAfterPermission = false
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val askToTurnOn = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        allowed = AndroidSosSender.hasPermissions(context)
        if (allowed) {
            SosPreferences.update(context) { it.copy(enabled = true) }
            enableAfterPermission = false
            showSmsPermissionHelp = false
        } else {
            showSmsPermissionHelp = true
        }
    }
    var testing by remember { mutableStateOf(false) }
    val sendTest: () -> Unit = {
        if (number != null) {
            testing = true
            scope.launch {
                val record = sendTestSos(AndroidSosSender(context), number, profile.name, System.currentTimeMillis())
                SosPreferences.addRecord(context, record)
                testing = false
            }
        }
    }
    val askToTest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        allowed = AndroidSosSender.hasPermissions(context)
        if (allowed) sendTest() else showSmsPermissionHelp = true
    }
    val askToCall = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        SosPreferences.update(context) { it.copy(callAfter = granted) }
    }
    val on = settings.enabled && allowed && number != null

    if (showSmsPermissionHelp) {
        AlertDialog(
            onDismissRequest = { showSmsPermissionHelp = false; enableAfterPermission = false },
            title = { Text("Allow SMS for Automatic SOS") },
            text = {
                Text(permissionSettingsError ?: "Android has not granted SMS permission, so G-one cannot send emergency messages. " +
                    "Open app settings, choose Permissions, then SMS, and select Allow. " +
                    "Return to G-one to finish enabling Automatic SOS. No message is sent during setup.")
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        context.startActivity(android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:${context.packageName}")
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: RuntimeException) {
                        permissionSettingsError = "Open your phone's Settings, then Apps, G-one, Permissions, and allow SMS."
                    }
                }) { Text("Open app settings") }
            },
            dismissButton = {
                TextButton(onClick = { showSmsPermissionHelp = false; enableAfterPermission = false }) { Text("Not now") }
            }
        )
    }

    SettingsSection("Emergency SOS", isDarkTheme) {
        SettingsToggle(
            Icons.Default.Sos, "Automatic SOS",
            when {
                number == null -> "Add your emergency contact's phone number first"
                settings.enabled && !allowed -> "SMS permission is missing. Tap to allow automatic messages"
                on -> "On: confirmed anomalies automatically text $number"
                else -> "Off. Enable to automatically text $number when an anomaly is detected"
            },
            checked = on,
            onToggle = {
                when {
                    number == null -> onEditProfile()
                    on -> SosPreferences.update(context) { it.copy(enabled = false) }
                    allowed -> SosPreferences.update(context) { it.copy(enabled = true) }
                    else -> {
                        enableAfterPermission = true
                        permissionSettingsError = null
                        askToTurnOn.launch(AndroidSosSender.PERMISSIONS)
                    }
                }
            },
            isDarkTheme = isDarkTheme
        )
        SettingsDivider(isDarkTheme)
        Text(
            "Every confirmed live anomaly sends an SMS automatically, without waiting for you. " +
                "Motion requires a high impact of at least 5 g; smaller knocks do not send an SOS, even " +
                "if followed by stillness. Alerts from the same reading are combined for 1.5 seconds. " +
                "Existing alert cooldowns limit repeats. Simulated and historical readings never send. " +
                "A working SIM and mobile signal are required.",
            style = MaterialTheme.typography.bodySmall,
            color = if (isDarkTheme) TextSecondary else TextSecondaryLight,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        SettingsDivider(isDarkTheme)
        SettingsToggle(
            Icons.Default.Call, "Then call them",
            "A few seconds after the text, so it arrives first",
            checked = settings.callAfter,
            onToggle = {
                if (settings.callAfter) SosPreferences.update(context) { it.copy(callAfter = false) }
                else askToCall.launch(Manifest.permission.CALL_PHONE)
            },
            isDarkTheme = isDarkTheme
        )
        SettingsDivider(isDarkTheme)
        SettingsActionRow(
            Icons.AutoMirrored.Filled.Send, "Send a test text",
            when {
                number == null -> "Add an emergency contact first"
                else -> "Texts $number a message marked TEST, to check it arrives. No call."
            },
            "Send", Blue500, isLoading = testing, isDarkTheme = isDarkTheme,
            onClick = {
                when {
                    number == null -> onEditProfile()
                    AndroidSosSender.hasPermissions(context) -> sendTest()
                    else -> askToTest.launch(AndroidSosSender.PERMISSIONS)
                }
            }
        )
        records.firstOrNull()?.let { last ->
            SettingsDivider(isDarkTheme)
            Text(
                sosRecordText(last),
                style = MaterialTheme.typography.bodySmall,
                color = if (last.sms.sent) SuccessGreen else ErrorRed,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}

private fun sosRecordText(record: SosRecord): String {
    val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(record.at))
    val what = if (record.test) "Test text" else "SOS"
    val call = record.call?.let { " Call: ${it.words}." }.orEmpty()
    return "Last: $what to ${record.number}, $time. Text: ${record.sms.words}.$call"
}

private fun appVersionName(context: Context): String = runCatching {
    val info = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    info.versionName
}.getOrNull() ?: "unknown"

@Composable
private fun SettingsSection(title: String, isDarkTheme: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall,
            color = if (isDarkTheme) TextSecondary else TextSecondaryLight,
            fontWeight = FontWeight.Medium, letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp).semantics { heading() })
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
    Row(modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = { onToggle() }),
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
        Switch(checked = checked, onCheckedChange = null,
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
            color = if (isDarkTheme) TextSecondary else TextSecondaryLight,
            textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 180.dp))
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
            .clickable(role = Role.Button, enabled = !isLoading, onClick = onClick),
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
                .clickable(role = Role.Button, enabled = !isLoading, onClick = onClick)
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

