package com.gone.ai.health.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gone.ai.health.data.EmergencyProfileEntity
import com.gone.ai.health.domain.EmergencyHtmlRenderer
import com.gone.ai.health.domain.EmergencyPayload
import com.gone.ai.health.domain.Staleness
import com.gone.ai.ui.components.GlassCard
import com.gone.ai.ui.components.GradientBackground
import com.gone.ai.ui.theme.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Emergency Medical ID setup screen.
 *
 * THREE LOGICAL SECTIONS:
 *   1. Profile fields (blood group, allergies, conditions, medications, implants, contact)
 *   2. Privacy toggles (which fields appear in the QR/NFC snapshot)
 *   3. Action cards:
 *      a. Live preview of the offline emergency page (updates as fields change)
 *      b. QR code generation and sharing
 *      c. NFC tag write
 *      d. Emergency ID management (show ID, regenerate)
 *
 * NFC READER MODE LIFECYCLE
 *
 * NFC reader mode is enabled/disabled via [DisposableEffect] keyed on the NFC state.
 * The activity is obtained via [LocalActivity]. Reader mode is active ONLY while
 * nfcState == WaitingForTag — it is always released when the effect disposes (screen
 * exit, state transition, or recomposition).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencyIdScreen(
    onBack: () -> Unit,
    isDarkTheme: Boolean,
    vm: EmergencyIdViewModel = viewModel()
) {
    val context  = LocalContext.current
    val activity = LocalActivity.current

    val profile    by vm.profile.collectAsState()
    val qrBitmap   by vm.qrBitmap.collectAsState()
    val qrError    by vm.qrError.collectAsState()
    val qrGenerating by vm.qrGenerating.collectAsState()
    val nfcState   by vm.nfcState.collectAsState()
    val nfcTarget  by vm.nfcTarget.collectAsState()
    val sharing by vm.sharing.collectAsState()
    var showOnlineConsent by remember { mutableStateOf(false) }

    // ── Local edit state (draft held here, saved on explicit "Save" tap) ──────
    var bloodGroup       by remember(profile) { mutableStateOf(profile?.bloodGroup ?: "") }
    var allergies        by remember(profile) { mutableStateOf(profile?.allergies ?: "") }
    var conditions       by remember(profile) { mutableStateOf(profile?.chronicConditions ?: "") }
    var medications      by remember(profile) { mutableStateOf(profile?.medications ?: "") }
    var implants         by remember(profile) { mutableStateOf(profile?.implantedDevices ?: "") }
    var contact          by remember(profile) { mutableStateOf(profile?.primaryEmergencyContact ?: "") }
    var shareAllergies   by remember(profile) { mutableStateOf(profile?.shareAllergies ?: true) }
    var shareConditions  by remember(profile) { mutableStateOf(profile?.shareConditions ?: true) }
    var shareMedications by remember(profile) { mutableStateOf(profile?.shareMedications ?: false) }
    var shareLiveVitals  by remember(profile) { mutableStateOf(profile?.shareLiveVitals ?: true) }

    var showRegenDialog    by remember { mutableStateOf(false) }
    var showPreview        by remember { mutableStateOf(false) }
    var showChipPreview    by remember { mutableStateOf(false) }
    var bloodGroupExpanded by remember { mutableStateOf(false) }

    // Build a preview payload from the current draft for the live preview card
    val previewPayload = remember(
        bloodGroup, allergies, conditions, medications, implants, contact,
        shareAllergies, shareConditions, shareMedications, shareLiveVitals
    ) {
        EmergencyPayload(
            name              = null,  // not shown in the draft preview; real name from DB at write time
            age               = null,
            bloodGroup        = bloodGroup.takeIf { it.isNotBlank() },
            allergies         = allergies.takeIf { it.isNotBlank() && shareAllergies },
            chronicConditions = conditions.takeIf { it.isNotBlank() && shareConditions },
            medications       = medications.takeIf { it.isNotBlank() && shareMedications },
            implantedDevices  = implants.takeIf { it.isNotBlank() },
            emergencyContact  = contact.takeIf { it.isNotBlank() },
            heartRate         = null,
            spo2              = null,
            bodyTempC         = null,
            motionStatus      = null,
            riskStatus        = null,
            readingTimestamp  = null,
            snapshotTimestamp = System.currentTimeMillis()
        )
    }

    // ── NFC reader mode DisposableEffect ──────────────────────────────────────
    val nfcAdapter = remember { NfcAdapter.getDefaultAdapter(context) }

    val readerActive = nfcState == EmergencyIdViewModel.NfcState.WaitingForTag || nfcState == EmergencyIdViewModel.NfcState.Writing
    DisposableEffect(readerActive) {
        if (readerActive && nfcAdapter != null && activity != null) {
            nfcAdapter.enableReaderMode(
                activity,
                { tag -> vm.onTagDiscovered(tag) },
                NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V,
                null
            )
        }
        onDispose {
            runCatching { nfcAdapter?.disableReaderMode(activity) }
        }
    }

    GradientBackground(darkTheme = isDarkTheme, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // ── Top bar ───────────────────────────────────────────────────────
            TopAppBar(
                title = {
                    Column {
                        Text("Emergency Medical ID",
                            fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
                            color = if (isDarkTheme) TextPrimary else MaterialTheme.colorScheme.onSurface)
                        Text("NFC, web sharing & offline snapshots",
                            fontSize = 12.sp,
                            color = if (isDarkTheme) TextSecondary else TextSecondaryLight)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back",
                            tint = if (isDarkTheme) TextPrimary else MaterialTheme.colorScheme.onSurface)
                    }
                },
                actions = {
                    TextButton(onClick = {
                        val updated = (profile ?: return@TextButton).copy(
                            bloodGroup           = bloodGroup.trim().takeIf { it.isNotBlank() },
                            allergies            = allergies.trim().takeIf { it.isNotBlank() },
                            chronicConditions    = conditions.trim().takeIf { it.isNotBlank() },
                            medications          = medications.trim().takeIf { it.isNotBlank() },
                            implantedDevices     = implants.trim().takeIf { it.isNotBlank() },
                            primaryEmergencyContact = contact.trim().takeIf { it.isNotBlank() },
                            shareAllergies       = shareAllergies,
                            shareConditions      = shareConditions,
                            shareMedications     = shareMedications,
                            shareLiveVitals      = shareLiveVitals
                        )
                        vm.save(updated)
                        Toast.makeText(context, "Emergency profile saved", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Save", fontWeight = FontWeight.SemiBold, color = AccentGoldBg)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding()
            ) {
                Spacer(Modifier.height(4.dp))

                // ── Emergency ID chip ─────────────────────────────────────────
                profile?.let { p ->
                    EmergencyIdChip(
                        emergencyId = p.emergencyId,
                        isDarkTheme = isDarkTheme,
                        onRegenerate = { showRegenDialog = true }
                    )
                    Spacer(Modifier.height(16.dp))
                }

                // ── Section: Profile fields ───────────────────────────────────
                SectionLabel("Medical Information", isDarkTheme)
                Spacer(Modifier.height(8.dp))

                GlassCard(darkTheme = isDarkTheme) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Blood group picker
                        BloodGroupPicker(
                            selected = bloodGroup,
                            expanded = bloodGroupExpanded,
                            onExpand = { bloodGroupExpanded = !bloodGroupExpanded },
                            onSelect = { bloodGroup = it; bloodGroupExpanded = false },
                            isDarkTheme = isDarkTheme
                        )
                        EmergencyTextField(
                            value = allergies,
                            onValueChange = { allergies = it },
                            label = "Allergies",
                            hint  = "e.g. Penicillin, Peanuts",
                            icon  = Icons.Default.Warning,
                            isDarkTheme = isDarkTheme
                        )
                        EmergencyTextField(
                            value = conditions,
                            onValueChange = { conditions = it },
                            label = "Chronic Conditions",
                            hint  = "e.g. Asthma, Type 1 Diabetes",
                            icon  = Icons.Default.Favorite,
                            isDarkTheme = isDarkTheme
                        )
                        EmergencyTextField(
                            value = medications,
                            onValueChange = { medications = it },
                            label = "Current Medications",
                            hint  = "e.g. Insulin 10U daily",
                            icon  = Icons.Default.MedicalServices,
                            isDarkTheme = isDarkTheme,
                            note  = if (!shareMedications) "⚠ Off by default — enable the toggle below to include in snapshot." else null
                        )
                        EmergencyTextField(
                            value = implants,
                            onValueChange = { implants = it },
                            label = "Implanted Devices",
                            hint  = "e.g. Pacemaker",
                            icon  = Icons.Default.ElectricBolt,
                            isDarkTheme = isDarkTheme,
                            note  = "Flags contraindications — always shown when not blank."
                        )
                        EmergencyTextField(
                            value = contact,
                            onValueChange = { contact = it },
                            label = "Emergency Contact Number",
                            hint  = "+91 XXXXX XXXXX",
                            icon  = Icons.Default.Phone,
                            isDarkTheme = isDarkTheme,
                            keyboardType = KeyboardType.Phone
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── Section: Privacy toggles ──────────────────────────────────
                SectionLabel("What's in the Snapshot", isDarkTheme)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Turned-off fields are absent from the QR and NFC tag — not just hidden.",
                    fontSize = 12.sp,
                    color = if (isDarkTheme) TextSecondary else TextSecondaryLight,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                GlassCard(darkTheme = isDarkTheme) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        PrivacyToggle(
                            label    = "Share allergies",
                            detail   = "Critical for treatment — shown by default",
                            checked  = shareAllergies,
                            onChange = { shareAllergies = it },
                            isDarkTheme = isDarkTheme
                        )
                        HorizontalDivider(color = if (isDarkTheme) DarkBorder else Color(0xFFE5E7EB))
                        PrivacyToggle(
                            label    = "Share conditions",
                            detail   = "Chronic conditions visible to responder",
                            checked  = shareConditions,
                            onChange = { shareConditions = it },
                            isDarkTheme = isDarkTheme
                        )
                        HorizontalDivider(color = if (isDarkTheme) DarkBorder else Color(0xFFE5E7EB))
                        PrivacyToggle(
                            label    = "Share medications",
                            detail   = "Most sensitive field — off by default",
                            checked  = shareMedications,
                            onChange = { shareMedications = it },
                            isDarkTheme = isDarkTheme,
                            highlight = true
                        )
                        HorizontalDivider(color = if (isDarkTheme) DarkBorder else Color(0xFFE5E7EB))
                        PrivacyToggle(
                            label    = "Show last vitals",
                            detail   = "Last heart rate, SpO₂, temperature from wearable",
                            checked  = shareLiveVitals,
                            onChange = { shareLiveVitals = it },
                            isDarkTheme = isDarkTheme
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── Section: Live preview ─────────────────────────────────────
                SectionLabel("Preview", isDarkTheme)
                Spacer(Modifier.height(4.dp))
                Text(
                    "This is what a first responder would see on the offline page.",
                    fontSize = 12.sp,
                    color = if (isDarkTheme) TextSecondary else TextSecondaryLight,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                AnimatedVisibility(visible = showPreview, enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()) {
                    EmergencyPagePreview(
                        payload = previewPayload,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    )
                }

                OutlinedButton(
                    onClick = { showPreview = !showPreview },
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, if (isDarkTheme) DarkBorder else Color(0xFFD1D5DB)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        if (showPreview) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        null, modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (showPreview) "Hide Preview" else "Show Preview")
                }

                Spacer(Modifier.height(16.dp))

                // ── Section: QR code ──────────────────────────────────────────
                EmergencyOnlineSharingCard(
                    state = sharing,
                    onEnable = { showOnlineConsent = true },
                    onDisable = { vm.setOnlineSharing(false) },
                    onRetry = vm::retrySync,
                    isDarkTheme = isDarkTheme
                )
                Spacer(Modifier.height(16.dp))
                SectionLabel(if (nfcTarget == EmergencyIdViewModel.NfcTarget.WEB_URL) "Web QR Code" else "Offline Text QR", isDarkTheme)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (nfcTarget == EmergencyIdViewModel.NfcTarget.WEB_URL)
                        "Opens your shared record in a browser. Requires internet. Uses the same unique link as the NFC web mode below."
                    else "Contains a saved text snapshot. A compatible QR reader is needed. Regenerate after changing your details; printed copies do not update.",
                    fontSize = 12.sp,
                    color = if (isDarkTheme) TextSecondary else TextSecondaryLight,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                GlassCard(darkTheme = isDarkTheme) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // QR bitmap display
                        AnimatedContent(
                            targetState = Triple(qrGenerating, qrBitmap, qrError),
                            label = "QrContent"
                        ) { (generating, bitmap, error) ->
                            when {
                                generating -> {
                                    Box(
                                        modifier = Modifier.size(220.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(color = AccentGoldBg)
                                    }
                                }
                                bitmap != null -> {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = "Emergency QR code",
                                            modifier = Modifier
                                                .size(220.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .border(2.dp, if (isDarkTheme) DarkBorder else Color(0xFFE5E7EB), RoundedCornerShape(8.dp))
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        profile?.qrGeneratedAt?.let { ts ->
                                            if (nfcTarget == EmergencyIdViewModel.NfcTarget.OFFLINE_CHIP) {
                                                Text(
                                                    "Saved snapshot only. Regenerate after changes; check measurement times.",
                                                    fontSize = 11.sp, color = Color(0xFFB45309),
                                                    textAlign = TextAlign.Center
                                                )
                                                Spacer(Modifier.height(4.dp))
                                            }
                                            Text(
                                                "Generated ${formatRelative(ts)}",
                                                fontSize = 11.sp,
                                                color = if (isDarkTheme) TextSecondary else TextSecondaryLight
                                            )
                                        }
                                    }
                                }
                                error != null -> {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(Icons.Default.ErrorOutline, null,
                                            tint = DestructiveBg, modifier = Modifier.size(40.dp))
                                        Spacer(Modifier.height(8.dp))
                                        Text(error, fontSize = 13.sp, color = DestructiveBg,
                                            textAlign = TextAlign.Center)
                                        TextButton(onClick = vm::clearQrError) { Text("Dismiss") }
                                    }
                                }
                                else -> {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(Icons.Default.QrCode2, null,
                                            modifier = Modifier.size(56.dp),
                                            tint = if (isDarkTheme) TextSecondary else Color(0xFF9CA3AF))
                                        Spacer(Modifier.height(8.dp))
                                        Text("No QR generated yet", fontSize = 14.sp,
                                            color = if (isDarkTheme) TextSecondary else Color(0xFF9CA3AF))
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // Generate / Regenerate button
                        Button(
                            onClick = vm::generateQr,
                            enabled = !qrGenerating,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGoldBg)
                        ) {
                            Icon(Icons.Default.QrCode2, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (qrBitmap != null) "Regenerate QR Code" else "Generate QR Code",
                                fontWeight = FontWeight.SemiBold)
                        }

                        // Share / Save / View HTML buttons
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { openOfflineHtmlInBrowser(context, previewPayload) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Language, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Open HTML")
                            }
                            if (qrBitmap != null) {
                                OutlinedButton(
                                    onClick = { saveQrToGallery(context, qrBitmap!!) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.SaveAlt, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Save QR")
                                }
                                OutlinedButton(
                                    onClick = { shareQr(context, qrBitmap!!) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Share QR")
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── Section: NFC tag ──────────────────────────────────────────
                SectionLabel("NFC Tag · Choose Format", isDarkTheme)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (!vm.nfcAvailable)
                        "This device does not have NFC. Use the QR code or View Offline HTML for offline access."
                    else if (!vm.nfcEnabled)
                        "NFC is off. Enable it in device Settings to write the wearable tag."
                    else if (nfcTarget == EmergencyIdViewModel.NfcTarget.OFFLINE_CHIP)
                        "Writes a saved text snapshot to the chip. Reading requires a compatible NFC reader app. Changes require physically rewriting the tag; small tags may not fit all details."
                    else
                        "Writes your unique emergency link. Later saved app changes update the online record without rewriting this tag. The scanning phone needs internet.",
                    fontSize = 12.sp,
                    color = if (!vm.nfcAvailable || !vm.nfcEnabled)
                        if (isDarkTheme) TextSecondary else Color(0xFF9CA3AF)
                    else
                        if (isDarkTheme) TextSecondary else TextSecondaryLight,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Write Target Selector
                GlassCard(darkTheme = isDarkTheme) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Write Target Format:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isDarkTheme) TextPrimary else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val isOffline = nfcTarget == EmergencyIdViewModel.NfcTarget.OFFLINE_CHIP
                            OutlinedButton(
                                onClick = { vm.setNfcTarget(EmergencyIdViewModel.NfcTarget.OFFLINE_CHIP) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(
                                    if (isOffline) 2.dp else 1.dp,
                                    if (isOffline) Color(0xFF16A34A) else (if (isDarkTheme) DarkBorder else Color(0xFFD1D5DB))
                                ),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (isOffline) Color(0xFF16A34A).copy(alpha = 0.12f) else Color.Transparent
                                )
                            ) {
                                Text(
                                    "Offline snapshot\n(Reader app needed)",
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.Center,
                                    fontWeight = if (isOffline) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isOffline) Color(0xFF16A34A) else (if (isDarkTheme) TextPrimary else MaterialTheme.colorScheme.onSurface)
                                )
                            }

                            val isWeb = nfcTarget == EmergencyIdViewModel.NfcTarget.WEB_URL
                            OutlinedButton(
                                onClick = { vm.setNfcTarget(EmergencyIdViewModel.NfcTarget.WEB_URL) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(
                                    if (isWeb) 2.dp else 1.dp,
                                    if (isWeb) Color(0xFF2563EB) else (if (isDarkTheme) DarkBorder else Color(0xFFD1D5DB))
                                ),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (isWeb) Color(0xFF2563EB).copy(alpha = 0.12f) else Color.Transparent
                                )
                            ) {
                                Text(
                                    "Updating web link\n(Internet required)",
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.Center,
                                    fontWeight = if (isWeb) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isWeb) Color(0xFF2563EB) else (if (isDarkTheme) TextPrimary else MaterialTheme.colorScheme.onSurface)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Chip data preview toggle when in offline mode
                if (nfcTarget == EmergencyIdViewModel.NfcTarget.OFFLINE_CHIP) {
                    OutlinedButton(
                        onClick = { showChipPreview = !showChipPreview },
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, if (isDarkTheme) DarkBorder else Color(0xFFD1D5DB)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(if (showChipPreview) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (showChipPreview) "Hide Chip Data Preview" else "Preview Chip Data", fontSize = 12.sp)
                    }

                    AnimatedVisibility(
                        visible = showChipPreview,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        GlassCard(darkTheme = isDarkTheme) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "Data written to tag memory:",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF16A34A)
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = previewPayload.toOfflineText(),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (isDarkTheme) TextPrimary else MaterialTheme.colorScheme.onSurface,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                }

                GlassCard(darkTheme = isDarkTheme) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        NfcStateDisplay(nfcState = nfcState, isDarkTheme = isDarkTheme)

                        Spacer(Modifier.height(12.dp))

                        when (nfcState) {
                            is EmergencyIdViewModel.NfcState.WaitingForTag -> {
                                OutlinedButton(
                                    onClick = vm::cancelNfcWrite,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) { Text("Cancel") }
                            }
                            is EmergencyIdViewModel.NfcState.Success -> {
                                profile?.tagWrittenAt?.let { ts ->
                                    Text("Written ${formatRelative(ts)}", fontSize = 12.sp,
                                        color = if (isDarkTheme) TextSecondary else TextSecondaryLight)
                                    Spacer(Modifier.height(8.dp))
                                }
                                Button(
                                    onClick = vm::clearNfcState,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) { Text("Done") }
                            }
                            is EmergencyIdViewModel.NfcState.Error -> {
                                Button(
                                    onClick = vm::clearNfcState,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = DestructiveBg)
                                ) { Text("Try Again") }
                            }
                            else -> {
                                val buttonColor = if (nfcTarget == EmergencyIdViewModel.NfcTarget.OFFLINE_CHIP)
                                    Color(0xFF16A34A)
                                else
                                    Color(0xFF2563EB)

                                Button(
                                    onClick = vm::startNfcWrite,
                                    enabled = vm.nfcAvailable && vm.nfcEnabled && nfcState != EmergencyIdViewModel.NfcState.Writing,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = buttonColor,
                                        disabledContainerColor = buttonColor.copy(alpha = 0.3f)
                                    )
                                ) {
                                    Icon(Icons.Default.Nfc, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (nfcTarget == EmergencyIdViewModel.NfcTarget.OFFLINE_CHIP)
                                            "Write Offline Record to Tag"
                                        else
                                            "Write Web URL to Tag",
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showOnlineConsent) {
        AlertDialog(
            onDismissRequest = { showOnlineConsent = false },
            title = { Text("Publish your emergency details?") },
            text = { Text("This uploads your saved name, age, blood group, implants, emergency contact and the medical fields enabled above to G-one’s online emergency service. If wearable sharing is on, the last real wearable readings and their measurement time are included.\n\nAnyone who scans or obtains your unique link can read those details without signing in. Health analysis still runs locally. You can stop sharing here, but screenshots and offline tag copies cannot be recalled.\n\nOnly saved fields are uploaded. Save your edits before enabling.") },
            confirmButton = { TextButton(onClick = { showOnlineConsent = false; vm.setOnlineSharing(true) }) { Text("Enable & publish saved details") } },
            dismissButton = { TextButton(onClick = { showOnlineConsent = false }) { Text("Keep offline") } }
        )
    }

    // ── Regenerate confirmation dialog ────────────────────────────────────────
    if (showRegenDialog) {
        AlertDialog(
            onDismissRequest = { showRegenDialog = false },
            icon = { Icon(Icons.Default.Warning, null, tint = DestructiveBg) },
            title = { Text("Regenerate Emergency ID?") },
            text  = {
                Text(
                    "First turn off online sharing and wait for removal to finish. Regenerating then creates a new link when you enable sharing again.\n\n" +
                    "Rewrite your tag and regenerate your QR. Old offline copies cannot be remotely erased or revoked.",
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.regenerateId()
                        showRegenDialog = false
                    }
                ) {
                    Text("Regenerate", color = DestructiveBg, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRegenDialog = false }) { Text("Cancel") }
            }
        )
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun EmergencyIdChip(
    emergencyId: String,
    isDarkTheme: Boolean,
    onRegenerate: () -> Unit
) {
    GlassCard(darkTheme = isDarkTheme) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Badge, null,
                tint = AccentGoldBg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Emergency ID", fontSize = 11.sp,
                    color = if (isDarkTheme) TextSecondary else TextSecondaryLight,
                    fontWeight = FontWeight.Medium)
                Text(
                    emergencyId,
                    fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = if (isDarkTheme) TextPrimary else MaterialTheme.colorScheme.onSurface,
                    letterSpacing = 1.sp
                )
            }
            TextButton(onClick = onRegenerate) {
                Text("Regenerate", color = DestructiveBg, fontSize = 13.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BloodGroupPicker(
    selected: String,
    expanded: Boolean,
    onExpand: () -> Unit,
    onSelect: (String) -> Unit,
    isDarkTheme: Boolean
) {
    val groups = listOf("A+", "A−", "B+", "B−", "AB+", "AB−", "O+", "O−", "Unknown")
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { onExpand() }
    ) {
        OutlinedTextField(
            value = selected.ifBlank { "Select blood group" },
            onValueChange = {},
            readOnly = true,
            label = { Text("Blood Group") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            leadingIcon = { Icon(Icons.Default.WaterDrop, null, tint = Color(0xFFC0392B)) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentGoldBg,
                unfocusedBorderColor = if (isDarkTheme) DarkBorder else Color(0xFFD1D5DB)
            ),
            shape = RoundedCornerShape(10.dp)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { onSelect(selected) }) {
            groups.forEach { g ->
                DropdownMenuItem(text = { Text(g) }, onClick = { onSelect(g) })
            }
        }
    }
}

@Composable
private fun EmergencyTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    hint: String,
    icon: ImageVector,
    isDarkTheme: Boolean,
    note: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = { Text(hint, fontSize = 13.sp) },
            leadingIcon = { Icon(icon, null, modifier = Modifier.size(20.dp)) },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                keyboardType = keyboardType
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentGoldBg,
                unfocusedBorderColor = if (isDarkTheme) DarkBorder else Color(0xFFD1D5DB)
            ),
            shape = RoundedCornerShape(10.dp)
        )
        note?.let {
            Text(it, fontSize = 11.sp, color = if (isDarkTheme) TextSecondary else Color(0xFF9CA3AF),
                modifier = Modifier.padding(start = 4.dp, top = 3.dp))
        }
    }
}

@Composable
private fun PrivacyToggle(
    label: String,
    detail: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    isDarkTheme: Boolean,
    highlight: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                color = if (isDarkTheme) TextPrimary else MaterialTheme.colorScheme.onSurface)
            Text(detail, fontSize = 12.sp,
                color = if (isDarkTheme) TextSecondary else TextSecondaryLight,
                modifier = Modifier.padding(top = 2.dp))
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor  = Color.White,
                checkedTrackColor  = if (highlight) DestructiveBg else AccentGoldBg
            )
        )
    }
}

@Composable
private fun NfcStateDisplay(
    nfcState: EmergencyIdViewModel.NfcState,
    isDarkTheme: Boolean
) {
    when (nfcState) {
        is EmergencyIdViewModel.NfcState.Idle -> {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Nfc, null, modifier = Modifier.size(32.dp),
                    tint = if (isDarkTheme) TextSecondary else Color(0xFF9CA3AF))
                Spacer(Modifier.width(8.dp))
                Text("Ready to write", color = if (isDarkTheme) TextSecondary else Color(0xFF6B7280))
            }
        }
        is EmergencyIdViewModel.NfcState.WaitingForTag -> {
            // Animated NFC ripple
            val infiniteTransition = rememberInfiniteTransition(label = "nfc_ripple")
            val scale by infiniteTransition.animateFloat(
                initialValue = 1f, targetValue = 1.25f,
                animationSpec = infiniteRepeatable(tween(900, easing = EaseOut), RepeatMode.Reverse),
                label = "nfc_scale"
            )
            val alpha by infiniteTransition.animateFloat(
                initialValue = 1f, targetValue = 0.5f,
                animationSpec = infiniteRepeatable(tween(900, easing = EaseOut), RepeatMode.Reverse),
                label = "nfc_alpha"
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Nfc, null,
                    modifier = Modifier.size(48.dp).scale(scale),
                    tint = Color(0xFF2563EB).copy(alpha = alpha))
                Spacer(Modifier.height(8.dp))
                Text("Hold your phone near the G-one band",
                    fontWeight = FontWeight.Medium, fontSize = 14.sp,
                    textAlign = TextAlign.Center)
            }
        }
        is EmergencyIdViewModel.NfcState.Writing -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                CircularProgressIndicator(color = Color(0xFF2563EB), modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(8.dp))
                Text("Writing to tag…", fontSize = 14.sp,
                    color = if (isDarkTheme) TextSecondary else Color(0xFF6B7280))
            }
        }
        is EmergencyIdViewModel.NfcState.Success -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CheckCircle, null,
                    tint = Color(0xFF16A34A), modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(6.dp))
                Text("Tag written successfully!", fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF16A34A))
            }
        }
        is EmergencyIdViewModel.NfcState.Error -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.ErrorOutline, null,
                    tint = DestructiveBg, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(6.dp))
                Text(nfcState.message, fontSize = 13.sp, color = DestructiveBg,
                    textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, isDarkTheme: Boolean) {
    Text(
        text = text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        color = if (isDarkTheme) TextSecondary else TextSecondaryLight
    )
}

// ── Utility functions ─────────────────────────────────────────────────────────

private fun saveQrToGallery(context: Context, bitmap: Bitmap) {
    val filename = "g_one_emergency_qr_${System.currentTimeMillis()}.png"
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/G-one")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let { context.contentResolver.openOutputStream(it)?.use { os -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, os) } }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "G-one")
            dir.mkdirs()
            FileOutputStream(File(dir, filename)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        Toast.makeText(context, "QR saved to Photos", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Could not save: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

private fun shareQr(context: Context, bitmap: Bitmap) {
    try {
        val file = File(context.cacheDir, "g_one_emergency_qr.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "G-one Emergency QR Code")
            putExtra(Intent.EXTRA_TEXT, "G-one Emergency ID. Web links require internet; text snapshots require a compatible QR reader and reflect the time they were generated.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Emergency QR"))
    } catch (e: Exception) {
        Toast.makeText(context, "Could not share: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

private val relFmt = SimpleDateFormat("d MMM yyyy · h:mm a", Locale.getDefault())
private fun formatRelative(epochMs: Long): String = "on ${relFmt.format(Date(epochMs))}"

private fun openOfflineHtmlInBrowser(context: Context, payload: EmergencyPayload) {
    try {
        val html = EmergencyHtmlRenderer.render(payload, System.currentTimeMillis())
        val file = File(context.cacheDir, "emergency_id.html")
        file.writeText(html)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/html")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open Offline Emergency ID"))
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open browser: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
