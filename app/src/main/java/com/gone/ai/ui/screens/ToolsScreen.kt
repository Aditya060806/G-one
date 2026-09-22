package com.gone.ai.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import android.widget.Toast
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.gone.ai.health.service.MakeMeHealthyReminderManager
import com.gone.ai.health.ui.EmergencyActions
import com.gone.ai.ui.components.BreadcrumbHeader
import com.gone.ai.ui.components.BreadcrumbItem
import com.gone.ai.ui.components.HeaderActionPill
import com.gone.ai.ui.components.PureBreadcrumbText
import com.gone.ai.ui.theme.AccentGoldBg
import com.gone.ai.ui.theme.AccentGoldFg
import com.gone.ai.ui.theme.DarkBorder
import com.gone.ai.ui.theme.DestructiveBg
import com.gone.ai.ui.theme.LightBorder
import com.gone.ai.ui.theme.LightShadow
import com.gone.ai.ui.theme.ModernBgDark
import com.gone.ai.ui.theme.ModernBgLight
import com.gone.ai.ui.theme.ModernCardDark
import com.gone.ai.ui.theme.ModernCardLight
import com.gone.ai.ui.theme.MutedBg
import com.gone.ai.ui.theme.MutedFg
import com.gone.ai.ui.theme.PrimaryBg
import com.gone.ai.ui.theme.SecondaryBg
import com.gone.ai.ui.theme.TextPrimary
import com.gone.ai.ui.theme.TextPrimaryLight
import com.gone.ai.ui.theme.TextSecondary
import com.gone.ai.ui.theme.pressScale

@Composable
fun ToolsScreen(
    isDarkTheme            : Boolean,
    bottomPadding          : Dp,
    onNavigateHome         : () -> Unit = {},
    onNavigateToPdf        : () -> Unit = {},
    onNavigateToOcr        : () -> Unit = {},
    onNavigateToScreenshot : () -> Unit = {},
    onNavigateToQuiz       : () -> Unit = {},
    onNavigateToCircle     : () -> Unit = {},
    onNavigateToLibrary    : () -> Unit = {},
    onNavigateToSettings   : () -> Unit = {},
    onAskAssistant         : (title: String, text: String) -> Unit = { _, _ -> }
) {
    val dark = isDarkTheme
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val settingsInteraction = remember { MutableInteractionSource() }

    // Dialog states for interactive tools
    var showMythDialog by remember { mutableStateOf(false) }
    var showDietDialog by remember { mutableStateOf(false) }
    var showHealthyDialog by remember { mutableStateOf(false) }
    var showSosDialog by remember { mutableStateOf(false) }

    // Make Me Healthy Reminders state
    var remindersEnabled by remember {
        mutableStateOf(MakeMeHealthyReminderManager.isEnabled(context))
    }
    var selectedReminderType by remember {
        mutableStateOf(MakeMeHealthyReminderManager.selectedType(context))
    }
    var remindersTakeTurns by remember {
        mutableStateOf(MakeMeHealthyReminderManager.takesTurns(context))
    }
    var reminderWindow by remember {
        mutableStateOf(MakeMeHealthyReminderManager.window(context))
    }
    // Asked when reminders are switched on or a test is sent, not before.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(context, "Notifications are off for G-one, so reminders cannot be shown.", Toast.LENGTH_LONG).show()
        }
    }
    val askForNotifications: () -> Unit = {
        // canNotify is only false on Android 13+, where the permission exists.
        if (!MakeMeHealthyReminderManager.canNotify(context)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Emergency contact from onboarding; emergency services when none is saved.
    val savedContact = remember(context) { EmergencyActions.savedContact(context) }
    val emergencyContact = savedContact ?: EmergencyActions.EMERGENCY_SERVICES_NUMBER

    // Both actions open the system app with the details filled in; the person confirms there.
    val triggerEmergencyCall: () -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (!EmergencyActions.dial(context, emergencyContact)) {
            Toast.makeText(context, "Unable to launch dialer", Toast.LENGTH_SHORT).show()
        }
    }

    val triggerEmergencySms: () -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val alertMsg = "EMERGENCY SOS: I need urgent help. Sent from the G-one health app."
        if (!EmergencyActions.composeSms(context, emergencyContact, alertMsg)) {
            Toast.makeText(context, "Unable to compose SMS", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (dark) ModernBgDark else ModernBgLight)
            .statusBarsPadding()
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
            PureBreadcrumbText(
                items = listOf(BreadcrumbItem("Home", onNavigateHome), BreadcrumbItem("Tools")),
                isDarkTheme = dark
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
        ) {
            Spacer(Modifier.height(14.dp))

            // ── Top Action Buttons (Non-sticky, scroll away with content) ────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                HeaderActionPill(
                    icon = Icons.Default.Folder,
                    label = "Vault",
                    darkTheme = dark,
                    onClick = onNavigateToLibrary
                )
                Spacer(Modifier.width(8.dp))
                HeaderActionPill(
                    icon = Icons.Default.Settings,
                    label = "Setup",
                    darkTheme = dark,
                    onClick = onNavigateToSettings
                )
            }

            Spacer(Modifier.height(2.dp))

            Text(
                "On-device AI and health tools",
                style = MaterialTheme.typography.bodyMedium,
                color = if (dark) TextSecondary else MutedFg,
                fontSize = 12.5.sp
            )

            Spacer(Modifier.height(20.dp))

            // ════════════════════════════════════════════════════════════════
            // 1. AI TOOLS (2x2 Bento Grid Matching User Hierarchy & Image Top)
            // a. Circle to search | b. OCR
            // c. Screenshot AI   | d. Quiz generator
            // ════════════════════════════════════════════════════════════════
            Text(
                text = "AI TOOLS",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (dark) TextSecondary else MutedFg,
                fontSize = 11.sp,
                letterSpacing = 1.sp
            )

            Spacer(Modifier.height(10.dp))

            // Row 1: a. Circle to search & b. OCR
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SoberBentoGridCard(
                    icon = Icons.Default.AutoAwesome,
                    title = "Circle to Search",
                    subtitle = "Screen lens AI",
                    badge = "AI Lens",
                    darkTheme = dark,
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateToCircle
                )

                SoberBentoGridCard(
                    icon = Icons.Default.PhotoCamera,
                    title = "OCR",
                    subtitle = "Text extractor",
                    badge = "Scan",
                    darkTheme = dark,
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateToOcr
                )
            }

            Spacer(Modifier.height(12.dp))

            // Row 2: c. Screenshot AI & d. Quiz generator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SoberBentoGridCard(
                    icon = Icons.Default.Image,
                    title = "Screenshot AI",
                    subtitle = "Explain screenshot text",
                    badge = "Read",
                    darkTheme = dark,
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateToScreenshot
                )

                SoberBentoGridCard(
                    icon = Icons.Default.School,
                    title = "Quiz Generator",
                    subtitle = "Generate MCQs",
                    badge = "Learn",
                    darkTheme = dark,
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateToQuiz
                )
            }

            Spacer(Modifier.height(22.dp))

            // ════════════════════════════════════════════════════════════════
            // 2. SOS (Clean Card with a. Text emergency contacts, b. Call them)
            // ════════════════════════════════════════════════════════════════
            Text(
                text = "EMERGENCY SOS",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (dark) TextSecondary else MutedFg,
                fontSize = 11.sp,
                letterSpacing = 1.sp
            )

            Spacer(Modifier.height(10.dp))

            SoberSosCard(
                contactNumber = emergencyContact,
                darkTheme = dark,
                onCall = triggerEmergencyCall,
                onText = triggerEmergencySms,
                onConfigure = { showSosDialog = true }
            )

            Spacer(Modifier.height(22.dp))

            // ════════════════════════════════════════════════════════════════
            // 3, 4, 5, 6: HEALTH & MEMORY VAULT (Horizontal Bento Tiles)
            // 3. Medical myth buster
            // 4. Diet Coach
            // 5. Make me Healthy (system notifications: drink, stand, run)
            // 6. Memory Vault
            // ════════════════════════════════════════════════════════════════
            Text(
                text = "HEALTH & MEMORY",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (dark) TextSecondary else MutedFg,
                fontSize = 11.sp,
                letterSpacing = 1.sp
            )

            Spacer(Modifier.height(10.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 3. Medical myth buster (Highlighted card with subtle accent like reference row)
                SoberBentoListTile(
                    icon = Icons.Default.Psychology,
                    title = "Medical Myth Buster",
                    subtitle = "Common health beliefs, explained",
                    badge = "Learn",
                    darkTheme = dark,
                    onClick = { showMythDialog = true }
                )

                // 4. Diet Coach
                SoberBentoListTile(
                    icon = Icons.Default.Restaurant,
                    title = "Diet Coach",
                    subtitle = "A simple balanced-plate guide",
                    badge = "Guide",
                    darkTheme = dark,
                    onClick = { showDietDialog = true }
                )

                // 5. Make me Healthy (sends system notifications to drink, stand, run)
                SoberBentoListTile(
                    icon = Icons.Default.NotificationsActive,
                    title = "Make me Healthy",
                    subtitle = if (remindersEnabled) {
                        (if (remindersTakeTurns) "Taking turns" else selectedReminderType.label) + " · " + reminderWindow.label
                    } else "Reminders to drink water, stand and move",
                    badge = if (remindersEnabled) "On" else "Off",
                    darkTheme = dark,
                    onClick = { showHealthyDialog = true }
                )

                // 6. Memory Vault
                SoberBentoListTile(
                    icon = Icons.Default.Folder,
                    title = "Memory Vault",
                    subtitle = "Saved summaries, scans & quiz history",
                    badge = "Vault",
                    darkTheme = dark,
                    onClick = onNavigateToLibrary
                )
            }

            Spacer(Modifier.height(bottomPadding + 90.dp))
        }


    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sobriety & Consistency: Dialogs Using App Design Tokens
    // ─────────────────────────────────────────────────────────────────────────

    // 3. Medical Myth Buster Dialog
    if (showMythDialog) {
        val myths = listOf(
            "You must drink 8 glasses of water every day." to
                "How much water a person needs depends on their body, activity and the weather. Water in food, tea and other drinks counts too. Thirst is a reasonable guide for most healthy adults.",
            "Cold weather gives you a cold." to
                "Colds are caused by viruses, not by cold air itself. People catch more colds in colder months partly because they spend more time indoors, close to others.",
            "Sugar makes children hyperactive." to
                "Careful studies in which neither parents nor children knew who had sugar have not found that sugar changes behaviour. Excitement at parties and expectations play a bigger part.",
            "Antibiotics help with colds and flu." to
                "Antibiotics work against bacteria, not viruses. Colds and flu are caused by viruses, so antibiotics do not help them and can cause side effects.",
            "Cracking your knuckles causes arthritis." to
                "Studies comparing people who crack their knuckles with people who do not have not found more arthritis in those who crack them."
        )
        var mythIndex by remember { mutableIntStateOf(0) }
        val (myth, fact) = myths[mythIndex]

        AlertDialog(
            onDismissRequest = { showMythDialog = false },
            title = {
                Text(
                    text = "Medical Myth Buster",
                    fontWeight = FontWeight.Bold,
                    color = if (dark) TextPrimary else TextPrimaryLight,
                    fontSize = 17.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Myth ${mythIndex + 1} of ${myths.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (dark) TextSecondary else MutedFg
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (dark) Color(0xFF262626) else MutedBg)
                            .border(1.dp, if (dark) DarkBorder else LightBorder, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "\"$myth\"",
                            fontWeight = FontWeight.SemiBold,
                            color = if (dark) TextPrimary else TextPrimaryLight,
                            fontSize = 13.5.sp
                        )
                    }
                    Text(
                        text = fact,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (dark) TextPrimary else TextPrimaryLight,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Text(
                        text = "General information, not medical advice.",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (dark) TextSecondary else MutedFg
                    )
                    TextButton(
                        onClick = {
                            showMythDialog = false
                            onAskAssistant("Myth: $myth", "Myth: $myth\n\nWhat the evidence says: $fact")
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Ask G-one about this", color = if (dark) AccentGoldBg else PrimaryBg) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    mythIndex = (mythIndex + 1) % myths.size
                }) {
                    Text("Next myth", fontWeight = FontWeight.Bold, color = if (dark) AccentGoldBg else PrimaryBg)
                }
            },
            dismissButton = {
                TextButton(onClick = { showMythDialog = false }) {
                    Text("Close", color = if (dark) TextSecondary else MutedFg)
                }
            }
        )
    }

    // 4. Diet Coach Dialog
    if (showDietDialog) {
        val plate = listOf(
            "Half the plate: vegetables and fruit",
            "A quarter: protein, such as dal, beans, eggs, fish or chicken",
            "A quarter: whole grains or starchy foods, such as brown rice, roti, oats or potatoes"
        )
        AlertDialog(
            onDismissRequest = { showDietDialog = false },
            title = {
                Text(
                    text = "Diet Coach",
                    fontWeight = FontWeight.Bold,
                    color = if (dark) TextPrimary else TextPrimaryLight,
                    fontSize = 17.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "A balanced plate",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (dark) TextSecondary else MutedFg,
                        fontWeight = FontWeight.SemiBold
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (dark) Color(0xFF262626) else MutedBg)
                            .border(1.dp, if (dark) DarkBorder else LightBorder, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            plate.forEach {
                                Text("• $it", fontSize = 12.5.sp, color = if (dark) TextPrimary else TextPrimaryLight)
                            }
                        }
                    }
                    Text(
                        "Water is the simplest drink with meals. If you have diabetes, kidney disease or another " +
                            "condition, or are pregnant, follow the diet your doctor or dietitian gave you.",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.5.sp,
                        color = if (dark) TextSecondary else MutedFg
                    )
                    TextButton(
                        onClick = {
                            showDietDialog = false
                            onAskAssistant("A balanced plate", "A balanced plate:\n" + plate.joinToString("\n") { "- $it" })
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Ask G-one about meals", color = if (dark) AccentGoldBg else PrimaryBg) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDietDialog = false }) {
                    Text("Done", fontWeight = FontWeight.Bold, color = if (dark) AccentGoldBg else PrimaryBg)
                }
            }
        )
    }

    // 5. Make Me Healthy Dialog
    if (showHealthyDialog) {
        AlertDialog(
            onDismissRequest = { showHealthyDialog = false },
            title = {
                Text(
                    text = "Make me Healthy",
                    fontWeight = FontWeight.Bold,
                    color = if (dark) TextPrimary else TextPrimaryLight,
                    fontSize = 17.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Hourly reminders", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (dark) TextPrimary else TextPrimaryLight)
                            Text("At the top of each hour, only during your active hours", fontSize = 11.5.sp, color = if (dark) TextSecondary else MutedFg)
                        }
                        Switch(
                            checked = remindersEnabled,
                            onCheckedChange = { isChecked ->
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                remindersEnabled = isChecked
                                MakeMeHealthyReminderManager.setEnabled(context, isChecked)
                                if (isChecked) askForNotifications()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = if (dark) AccentGoldBg else PrimaryBg
                            )
                        )
                    }

                    Text("Reminder", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = if (dark) TextPrimary else TextPrimaryLight)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MakeMeHealthyReminderManager.ReminderType.entries.forEach { type ->
                            ReminderChoiceChip(
                                label = type.label,
                                selected = !remindersTakeTurns && selectedReminderType == type,
                                darkTheme = dark,
                                modifier = Modifier.weight(1f)
                            ) {
                                selectedReminderType = type
                                remindersTakeTurns = false
                                MakeMeHealthyReminderManager.setSelectedType(context, type)
                            }
                        }
                        ReminderChoiceChip(
                            label = "Turns",
                            selected = remindersTakeTurns,
                            darkTheme = dark,
                            modifier = Modifier.weight(1f)
                        ) {
                            remindersTakeTurns = true
                            MakeMeHealthyReminderManager.setTakeTurns(context)
                        }
                    }

                    Text("Active hours", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = if (dark) TextPrimary else TextPrimaryLight)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        HourStepper(
                            label = "From",
                            hour = reminderWindow.startHour,
                            canDecrease = reminderWindow.startHour > 0,
                            canIncrease = reminderWindow.startHour < reminderWindow.endHour,
                            darkTheme = dark
                        ) { delta ->
                            reminderWindow = reminderWindow.copy(startHour = reminderWindow.startHour + delta)
                            MakeMeHealthyReminderManager.setWindow(context, reminderWindow)
                        }
                        HourStepper(
                            label = "To",
                            hour = reminderWindow.endHour,
                            canDecrease = reminderWindow.endHour > reminderWindow.startHour,
                            canIncrease = reminderWindow.endHour < 23,
                            darkTheme = dark
                        ) { delta ->
                            reminderWindow = reminderWindow.copy(endHour = reminderWindow.endHour + delta)
                            MakeMeHealthyReminderManager.setWindow(context, reminderWindow)
                        }
                    }

                    TextButton(
                        onClick = {
                            val type = if (remindersTakeTurns) MakeMeHealthyReminderManager.ReminderType.HYDRATE else selectedReminderType
                            if (!MakeMeHealthyReminderManager.sendReminder(context, type)) askForNotifications()
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Send a test reminder", color = if (dark) AccentGoldBg else PrimaryBg) }

                    Text(
                        text = if (remindersEnabled) "On. Reminders keep coming while G-one is closed and after the phone restarts."
                        else "Switch on to get reminders.",
                        fontSize = 11.5.sp,
                        color = if (dark) TextSecondary else MutedFg
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showHealthyDialog = false }) {
                    Text("Done", fontWeight = FontWeight.Bold, color = if (dark) AccentGoldBg else PrimaryBg)
                }
            }
        )
    }

    // 2. SOS Info Dialog
    if (showSosDialog) {
        AlertDialog(
            onDismissRequest = { showSosDialog = false },
            title = {
                Text(
                    text = "Emergency SOS Target",
                    fontWeight = FontWeight.Bold,
                    color = if (dark) TextPrimary else TextPrimaryLight,
                    fontSize = 17.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Target Contact:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = if (dark) TextPrimary else TextPrimaryLight)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (dark) Color(0xFF262626) else MutedBg)
                            .border(1.dp, if (dark) DarkBorder else LightBorder, RoundedCornerShape(10.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = emergencyContact,
                            fontWeight = FontWeight.Bold,
                            color = DestructiveBg,
                            fontSize = 15.sp
                        )
                    }
                    Text(
                        (if (savedContact == null) "No personal contact is saved, so SOS uses emergency services. " else "") +
                            "To change the contact, open Settings › Profile. Call and Text open your " +
                            "dialer or messages with this number filled in — nothing is sent until you confirm.",
                        fontSize = 12.sp,
                        color = if (dark) TextSecondary else MutedFg
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSosDialog = false
                    onNavigateToSettings()
                }) {
                    Text("Settings", fontWeight = FontWeight.Bold, color = if (dark) AccentGoldBg else PrimaryBg)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSosDialog = false }) {
                    Text("Close", color = if (dark) TextSecondary else MutedFg)
                }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sober, Minimal Bento Grid Components (Following App Design Guidelines)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ReminderChoiceChip(
    label: String,
    selected: Boolean,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val accent = if (darkTheme) AccentGoldBg else PrimaryBg
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) accent.copy(alpha = 0.15f) else if (darkTheme) Color(0xFF262626) else SecondaryBg)
            .border(
                if (selected) 1.5.dp else 1.dp,
                if (selected) accent else if (darkTheme) DarkBorder else LightBorder,
                RoundedCornerShape(10.dp)
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            color = if (selected) accent else if (darkTheme) TextPrimary else TextPrimaryLight
        )
    }
}

@Composable
private fun HourStepper(
    label: String,
    hour: Int,
    canDecrease: Boolean,
    canIncrease: Boolean,
    darkTheme: Boolean,
    onChange: (Int) -> Unit
) {
    val color = if (darkTheme) TextPrimary else TextPrimaryLight
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = if (darkTheme) TextSecondary else MutedFg)
        IconButton(onClick = { onChange(-1) }, enabled = canDecrease) {
            Icon(Icons.Default.Remove, contentDescription = "$label one hour earlier", tint = color.copy(alpha = if (canDecrease) 1f else 0.3f))
        }
        Text("%02d:00".format(hour), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = color)
        IconButton(onClick = { onChange(1) }, enabled = canIncrease) {
            Icon(Icons.Default.Add, contentDescription = "$label one hour later", tint = color.copy(alpha = if (canIncrease) 1f else 0.3f))
        }
    }
}

/**
 * Clean, Sober Bento 2-Column Card (Grid for AI Tools)
 */
@Composable
private fun SoberBentoGridCard(
    icon      : ImageVector,
    title     : String,
    subtitle  : String,
    badge     : String,
    darkTheme : Boolean,
    onClick   : () -> Unit,
    modifier  : Modifier = Modifier
) {
    val src = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    val isPressed by src.collectIsPressedAsState()
    val elevation by animateDpAsState(
        targetValue = if (isPressed) 0.dp else (if (darkTheme) 0.dp else 2.dp),
        label = "soberBentoElevation"
    )

    Column(
        modifier = modifier
            .pressScale(src, pressedScale = 0.96f)
            .shadow(
                elevation = elevation,
                shape = RoundedCornerShape(22.dp),
                ambientColor = LightShadow,
                spotColor = LightShadow
            )
            .clip(RoundedCornerShape(22.dp))
            .background(if (darkTheme) ModernCardDark else ModernCardLight)
            .border(1.dp, if (darkTheme) DarkBorder else LightBorder, RoundedCornerShape(22.dp))
            .clickable(role = Role.Button,
                interactionSource = src,
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Sober circular avatar
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(if (darkTheme) Color(0xFF282828) else SecondaryBg)
                .border(1.dp, if (darkTheme) DarkBorder else LightBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (darkTheme) AccentGoldBg else PrimaryBg,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(Modifier.height(14.dp))

        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (darkTheme) TextPrimary else TextPrimaryLight,
                fontSize = 14.5.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (darkTheme) TextSecondary else MutedFg,
                fontSize = 11.5.sp
            )
        }

        Spacer(Modifier.height(12.dp))

        // Minimal pill (matching reference image chip position)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (darkTheme) Color(0xFF282828) else SecondaryBg)
                .border(1.dp, if (darkTheme) DarkBorder else LightBorder, RoundedCornerShape(10.dp))
                .padding(horizontal = 9.dp, vertical = 4.dp)
        ) {
            Text(
                text = badge,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (darkTheme) AccentGoldBg else PrimaryBg
            )
        }
    }
}

/**
 * Sober Emergency SOS Card (No shouting neon colors, clean structured emergency card)
 */
@Composable
private fun SoberSosCard(
    contactNumber: String,
    darkTheme    : Boolean,
    onCall       : () -> Unit,
    onText       : () -> Unit,
    onConfigure  : () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (darkTheme) 0.dp else 2.dp,
                shape = RoundedCornerShape(22.dp),
                ambientColor = LightShadow,
                spotColor = LightShadow
            )
            .clip(RoundedCornerShape(22.dp))
            .background(if (darkTheme) ModernCardDark else ModernCardLight)
            .border(1.dp, if (darkTheme) DarkBorder else LightBorder, RoundedCornerShape(22.dp))
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (darkTheme) Color(0xFF331E1E) else Color(0xFFFBECEC))
                            .border(1.dp, if (darkTheme) Color(0xFF4D2424) else Color(0xFFF5D5D5), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Warning, null, tint = DestructiveBg, modifier = Modifier.size(18.dp))
                    }

                    Column {
                        Text(
                            text = "Emergency SOS",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (darkTheme) TextPrimary else TextPrimaryLight,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "Target: $contactNumber",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (darkTheme) TextSecondary else MutedFg,
                            fontSize = 12.sp
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (darkTheme) Color(0xFF282828) else SecondaryBg)
                        .clickable(role = Role.Button, onClick = onConfigure)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Manage",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (darkTheme) TextSecondary else MutedFg
                    )
                }
            }

            // 2 Sober Action Buttons (Text & Call)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // a. Text Emergency Contacts
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (darkTheme) Color(0xFF282828) else SecondaryBg)
                        .border(1.dp, if (darkTheme) DarkBorder else LightBorder, RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button, onClick = onText),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Sms, null, tint = if (darkTheme) TextPrimary else TextPrimaryLight, modifier = Modifier.size(15.dp))
                        Text(
                            text = "Text SOS",
                            color = if (darkTheme) TextPrimary else TextPrimaryLight,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.5.sp
                        )
                    }
                }

                // b. Call Emergency Contacts
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(DestructiveBg)
                        .clickable(role = Role.Button, onClick = onCall),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Call, null, tint = Color.White, modifier = Modifier.size(15.dp))
                        Text(
                            text = "Call SOS",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.5.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Sober Horizontal Action Bento Tile (Clean list items matching reference layout)
 */
@Composable
private fun SoberBentoListTile(
    icon           : ImageVector,
    title          : String,
    subtitle       : String,
    badge          : String,
    darkTheme      : Boolean,
    onClick        : () -> Unit,
    isSubtleAccent : Boolean = false,
    modifier       : Modifier = Modifier
) {
    val src = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    val isPressed by src.collectIsPressedAsState()
    val elevation by animateDpAsState(
        targetValue = if (isPressed) 0.dp else (if (darkTheme) 0.dp else 2.dp),
        label = "soberListElevation"
    )

    val background = if (darkTheme) {
        if (isSubtleAccent) Color(0xFF1F241F) else ModernCardDark
    } else {
        if (isSubtleAccent) Color(0xFFF4F8F4) else ModernCardLight
    }

    val border = if (darkTheme) {
        if (isSubtleAccent) Color(0xFF2E3D2E) else DarkBorder
    } else {
        if (isSubtleAccent) Color(0xFFE2EBE2) else LightBorder
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .pressScale(src, pressedScale = 0.97f)
            .shadow(
                elevation = elevation,
                shape = RoundedCornerShape(18.dp),
                ambientColor = LightShadow,
                spotColor = LightShadow
            )
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(18.dp))
            .clickable(role = Role.Button,
                interactionSource = src,
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (darkTheme) Color(0xFF282828) else SecondaryBg)
                    .border(1.dp, if (darkTheme) DarkBorder else LightBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (darkTheme) AccentGoldBg else PrimaryBg,
                    modifier = Modifier.size(18.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (darkTheme) TextPrimary else TextPrimaryLight,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (darkTheme) TextSecondary else MutedFg,
                    fontSize = 11.5.sp
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (darkTheme) Color(0xFF282828) else SecondaryBg)
                    .border(1.dp, if (darkTheme) DarkBorder else LightBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = badge,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (darkTheme) AccentGoldBg else PrimaryBg
                )
            }

            if (isSubtleAccent) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(if (darkTheme) Color(0xFF223822) else Color(0xFFE2F0E2)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = if (darkTheme) Color(0xFF68D391) else Color(0xFF2E7D32),
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
        }
    }
}
