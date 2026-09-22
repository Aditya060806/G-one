package com.gone.ai.ui.screens

import android.Manifest
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gone.ai.ai.state.AIInferenceState
import com.gone.ai.ui.components.AiBodyOrb
import com.gone.ai.ui.components.AppSettings
import com.gone.ai.ui.components.MarkdownText
import com.gone.ai.ui.components.OrbState
import com.gone.ai.ui.theme.*
import com.gone.ai.viewmodel.ChatViewModel
import com.gone.ai.viewmodel.VoiceViewModel
import com.gone.ai.voice.VoiceLoop.Phase

/**
 * Voice chat: speak, hear G-one's reply, and keep talking hands-free.
 *
 * Turns go into the open chat conversation, so they stay in the chat history.
 */
@Composable
fun VoiceScreen(
    isDarkTheme: Boolean,
    chatViewModel: ChatViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val vm: VoiceViewModel = viewModel(
        factory = VoiceViewModel.factory(context.applicationContext as Application, chatViewModel)
    )
    val state by vm.state.collectAsState()
    val speaking by vm.speaking.collectAsState()
    val speechUnavailable by vm.speechUnavailable.collectAsState()
    val aiState by chatViewModel.aiState.collectAsState()
    val dark = isDarkTheme
    val accent = if (dark) AccentGoldBg else PrimaryBg
    val onAccent = if (dark) AccentGoldFg else PrimaryFg
    val primaryText = if (dark) TextPrimary else TextPrimaryLight
    val secondaryText = if (dark) TextSecondary else TextSecondaryLight
    val canListen = remember { vm.canListen }

    // ── Microphone permission ──────────────────────────────────────────────
    var micGranted by remember { mutableStateOf(hasMicrophone(context)) }
    var micBlocked by remember { mutableStateOf(false) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        if (granted) {
            micBlocked = false
            vm.listen()
        } else {
            micBlocked = AppSettings.isBlockedAfterDenial(context, Manifest.permission.RECORD_AUDIO)
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // Permission may have been switched on in system Settings.
                Lifecycle.Event.ON_RESUME -> {
                    micGranted = hasMicrophone(context)
                    if (micGranted) micBlocked = false
                }
                // Never keep the microphone open or keep talking behind another app.
                Lifecycle.Event.ON_STOP -> vm.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.needsPermission) {
        if (state.needsPermission) micGranted = hasMicrophone(context)
    }

    fun onMainButton() {
        val startsListening = state.phase == Phase.Idle || state.phase == Phase.Answering
        when {
            !canListen && startsListening -> Unit
            startsListening && !micGranted ->
                if (micBlocked) AppSettings.open(context) else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            else -> vm.tap()
        }
    }

    val status = when (state.phase) {
        Phase.Idle -> if (canListen) "Tap the microphone to talk" else "Voice input is not available"
        Phase.Listening -> "Listening…"
        Phase.Thinking -> if (aiState is AIInferenceState.Loading) "Loading the model…" else "Thinking…"
        Phase.Answering -> if (speaking) "Speaking" else "Answering"
    }
    val orbState = when (state.phase) {
        Phase.Idle -> OrbState.Idle
        Phase.Listening, Phase.Answering -> OrbState.Responding
        Phase.Thinking -> if (aiState is AIInferenceState.Loading) OrbState.Loading else OrbState.Thinking
    }
    val hasTranscript = state.heard.isNotBlank() || state.reply.isNotBlank()
    val orbSize by animateDpAsState(if (hasTranscript) 120.dp else 200.dp, tween(400), label = "orbSize")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (dark) ModernBgDark else ModernBgLight)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Top bar ──────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoundIconButton(
                    icon = Icons.Default.Close,
                    label = "Close voice chat",
                    isDarkTheme = dark,
                    tint = secondaryText,
                    onClick = onDismiss
                )
                Text(
                    "Voice chat",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = primaryText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).semantics { heading() }
                )
                RoundIconButton(
                    icon = if (state.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    label = if (state.muted) "Replies muted. Tap to hear replies" else "Replies spoken. Tap to mute",
                    isDarkTheme = dark,
                    tint = if (state.muted) secondaryText else accent,
                    onClick = { vm.setMuted(!state.muted) }
                )
            }

            Spacer(Modifier.height(12.dp))

            Box(Modifier.clearAndSetSemantics { }) {
                AiBodyOrb(orbState = orbState, isDarkTheme = dark, size = orbSize)
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = status,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (state.phase == Phase.Idle) secondaryText else accent,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )

            Spacer(Modifier.height(12.dp))

            // ── What was said ────────────────────────────────────────────
            val scroll = rememberScrollState()
            LaunchedEffect(state.reply.length, state.heard) { scroll.animateScrollTo(scroll.maxValue) }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!canListen) {
                    NoticeCard(
                        text = "This phone has no speech recognition service, so voice chat cannot listen. You can still type in the chat.",
                        isDarkTheme = dark
                    )
                } else if (!micGranted && state.phase == Phase.Idle) {
                    NoticeCard(
                        text = if (micBlocked) {
                            "Microphone access is off for G-one. Turn it on in Settings to talk."
                        } else {
                            "G-one needs the microphone to hear you. It listens only while the microphone button is on."
                        },
                        isDarkTheme = dark,
                        actionLabel = if (micBlocked) "Open settings" else "Allow microphone",
                        onAction = {
                            if (micBlocked) AppSettings.open(context) else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    )
                }

                state.message?.let { message ->
                    NoticeCard(
                        text = message,
                        isDarkTheme = dark,
                        actionLabel = if (state.needsPermission && !micGranted) "Open settings" else null,
                        onAction = { AppSettings.open(context) }
                    )
                }

                if (canListen && micGranted && !state.onDevice) {
                    NoticeCard(
                        text = "Using your phone's speech service, which may go online. Install the offline language pack for fully offline voice.",
                        isDarkTheme = dark,
                        actionLabel = "Speech settings",
                        onAction = {
                            if (!openVoiceInputSettings(context)) {
                                vm.show("Open your phone's Settings and search for \"offline speech recognition\".")
                            }
                        }
                    )
                }

                speechUnavailable?.let {
                    NoticeCard(text = "Replies are shown but not spoken. $it", isDarkTheme = dark)
                }

                if (state.heard.isNotBlank()) {
                    TranscriptCard(label = "You", isDarkTheme = dark) {
                        Text(state.heard, style = MaterialTheme.typography.bodyLarge, color = primaryText, lineHeight = 24.sp)
                    }
                }
                if (state.reply.isNotBlank()) {
                    TranscriptCard(label = "G-one", isDarkTheme = dark) {
                        MarkdownText(
                            text = state.reply,
                            isDarkTheme = dark,
                            isStreaming = state.phase == Phase.Answering && aiState is AIInferenceState.Responding
                        )
                    }
                }
            }

            // ── Controls ─────────────────────────────────────────────────
            LevelBars(
                level = if (state.phase == Phase.Listening) state.level else 0f,
                color = accent,
                modifier = Modifier.padding(top = 12.dp).height(28.dp).width(120.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                HandsFreeToggle(
                    on = state.handsFree,
                    isDarkTheme = dark,
                    accent = accent,
                    onChange = vm::setHandsFree,
                    modifier = Modifier.width(96.dp)
                )

                val (mainIcon, mainLabel) = when (state.phase) {
                    Phase.Idle -> (if (canListen) Icons.Default.Mic else Icons.Default.MicOff) to "Start talking"
                    Phase.Listening -> Icons.Default.Check to "Done talking"
                    Phase.Thinking -> Icons.Default.Stop to "Stop"
                    Phase.Answering -> Icons.Default.Mic to "Interrupt and talk"
                }
                val active = state.phase != Phase.Idle
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(if (active) accent else (if (dark) ModernCardDark else ModernCardLight))
                        .border(1.dp, if (active) Color.Transparent else (if (dark) ModernBorderDark else ModernBorderLight), CircleShape)
                        .clickable(
                            enabled = canListen || active,
                            role = Role.Button,
                            onClickLabel = mainLabel,
                            onClick = ::onMainButton
                        )
                        .semantics { contentDescription = mainLabel },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(mainIcon, contentDescription = null, tint = if (active) onAccent else accent, modifier = Modifier.size(32.dp))
                }

                Box(Modifier.width(96.dp), contentAlignment = Alignment.CenterEnd) {
                    if (state.phase != Phase.Idle) {
                        RoundIconButton(
                            icon = Icons.Default.Stop,
                            label = "Stop voice chat",
                            isDarkTheme = dark,
                            tint = secondaryText,
                            onClick = vm::stop
                        )
                    }
                }
            }
        }
    }
}

private fun hasMicrophone(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** The phone's voice input settings, where offline language packs are installed. False if there is none. */
private fun openVoiceInputSettings(context: Context): Boolean = try {
    context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
}

@Composable
private fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isDarkTheme: Boolean,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (isDarkTheme) ModernCardDark else ModernCardLight)
            .border(1.dp, if (isDarkTheme) ModernBorderDark else ModernBorderLight, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun HandsFreeToggle(
    on: Boolean,
    isDarkTheme: Boolean,
    accent: Color,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Switch(
            checked = on,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = accent)
        )
        Text(
            "Hands-free",
            style = MaterialTheme.typography.labelMedium,
            color = if (isDarkTheme) TextSecondary else TextSecondaryLight
        )
    }
}

@Composable
private fun NoticeCard(
    text: String,
    isDarkTheme: Boolean,
    actionLabel: String? = null,
    onAction: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isDarkTheme) ModernCardDark else ModernCardLight)
            .border(1.dp, if (isDarkTheme) ModernBorderDark else ModernBorderLight, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                Icons.Default.Info, contentDescription = null,
                tint = if (isDarkTheme) TextSecondary else TextSecondaryLight,
                modifier = Modifier.size(18.dp).padding(top = 2.dp)
            )
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isDarkTheme) TextPrimary else TextPrimaryLight
            )
        }
        if (actionLabel != null) {
            TextButton(onClick = onAction, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                Text(actionLabel, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun TranscriptCard(label: String, isDarkTheme: Boolean, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (isDarkTheme) ModernCardDark else ModernCardLight)
            .border(1.dp, if (isDarkTheme) ModernBorderDark else ModernBorderLight, RoundedCornerShape(20.dp))
            .padding(16.dp)
            .semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (isDarkTheme) TextSecondary else TextSecondaryLight
        )
        content()
    }
}

/** Bars that follow the microphone level while listening. Decorative: the status line says the same. */
@Composable
private fun LevelBars(level: Float, color: Color, modifier: Modifier = Modifier) {
    val shape = floatArrayOf(0.35f, 0.6f, 0.85f, 1f, 0.85f, 0.6f, 0.35f)
    Row(
        modifier = modifier.clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        shape.forEach { weight ->
            val height by animateFloatAsState(0.12f + 0.88f * level * weight, tween(120), label = "levelBar")
            Box(
                Modifier
                    .width(5.dp)
                    .fillMaxHeight(height.coerceIn(0.12f, 1f))
                    .clip(RoundedCornerShape(3.dp))
                    .background(color.copy(alpha = if (level > 0f) 0.9f else 0.3f))
            )
        }
    }
}
