package com.gone.ai.health.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.gone.ai.health.service.WearableLiveState
import com.gone.ai.health.source.SourceStatus
import com.gone.ai.ui.components.BreadcrumbItem
import com.gone.ai.ui.components.GlassCard
import com.gone.ai.ui.components.PureBreadcrumbText
import com.gone.ai.ui.theme.Blue500
import com.gone.ai.ui.theme.ErrorRed
import com.gone.ai.ui.theme.ModernBgDark
import com.gone.ai.ui.theme.ModernBgLight
import com.gone.ai.ui.theme.SuccessGreen
import com.gone.ai.ui.theme.TextPrimary
import com.gone.ai.ui.theme.TextPrimaryLight
import com.gone.ai.ui.theme.WarnAmber
import kotlinx.coroutines.delay

/**
 * Choose the wearable, and see honestly what its link is doing.
 *
 * Ported from REFERENCE's DeviceScreen, which listed bonded Classic Bluetooth devices and
 * connected over SPP. The wearable is BLE, so this scans instead, and it reports the link as
 * the monitoring service sees it — including the wearable's own sensor report and how many
 * lines were rejected — rather than a "connected" dot that only means a socket is open.
 */
@Composable
fun DeviceScreen(
    isDarkTheme: Boolean,
    bottomPadding: Dp,
    onNavigateHome: () -> Unit,
    vm: HealthViewModel
) {
    val context = LocalContext.current
    val saved by vm.savedWearable.collectAsState()
    val live by vm.wearable.collectAsState()
    val isMonitoring by vm.isMonitoring.collectAsState()
    val simulated by vm.simulationEnabled.collectAsState()
    val autoSync by vm.autoSync.collectAsState()
    val emgLevels by vm.emgLevels.collectAsState()
    val calibration by vm.calibration.collectAsState()
    var confirmForget by remember { mutableStateOf(false) }

    val content = if (isDarkTheme) TextPrimary else TextPrimaryLight
    val muted = secondaryTextColor(isDarkTheme)
    val pickerColors = PickerColors(content = content, muted = muted, accent = Blue500, onAccent = Color.White)

    // Ticks so "last data 4 s ago" stays true without new data arriving.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(if (isDarkTheme) ModernBgDark else ModernBgLight)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(64.dp))

            SectionHeader(title = "Your wearable", darkTheme = isDarkTheme)
            Spacer(Modifier.height(8.dp))
            GlassCard(darkTheme = isDarkTheme, modifier = Modifier.fillMaxWidth()) {
                val device = saved
                if (device == null) {
                    Text("No wearable chosen", color = content, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Pick it from the list below. It only needs choosing once.", color = muted, fontSize = 13.sp)
                } else {
                    Text(device.name, color = content, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(device.address, color = muted, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))

                    val streamingFromThis = isMonitoring && !simulated && live.address == device.address
                    when {
                        simulated -> StatusLine(
                            WarnAmber,
                            "Simulated vitals are on in Settings, so monitoring uses the simulator, not this wearable.",
                            content
                        )
                        !streamingFromThis -> StatusLine(
                            muted,
                            "Not connected. Start monitoring and G-one connects to it.",
                            content
                        )
                        else -> LinkDetails(live, now, content, muted)
                    }

                    TextButton(onClick = { confirmForget = true }, modifier = Modifier.padding(top = 4.dp)) {
                        Text("Forget this wearable", color = ErrorRed, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            val chosen = saved
            if (chosen != null && !simulated) {
                val streamingFromThis = isMonitoring && live.address == chosen.address

                Spacer(Modifier.height(20.dp))
                SectionHeader(title = "Sync", darkTheme = isDarkTheme)
                Spacer(Modifier.height(8.dp))
                WearableSyncCard(
                    device = chosen,
                    live = live,
                    streamingFromThis = streamingFromThis,
                    autoSync = autoSync,
                    autoSyncSupported = vm.autoSyncSupported,
                    now = now,
                    isDarkTheme = isDarkTheme,
                    content = content,
                    muted = muted,
                    onSyncNow = { vm.syncNow(context) },
                    onAutoSyncApproved = { vm.autoSyncApproved(context) },
                    onAutoSyncFailed = vm::autoSyncFailed,
                    onDisableAutoSync = { vm.disableAutoSync(context) }
                )

                Spacer(Modifier.height(20.dp))
                SectionHeader(title = "Keep syncing with the screen off", darkTheme = isDarkTheme)
                Spacer(Modifier.height(8.dp))
                BackgroundSyncCard(isDarkTheme = isDarkTheme, content = content, muted = muted)

                Spacer(Modifier.height(20.dp))
                SectionHeader(
                    title = "Muscle sensor levels",
                    darkTheme = isDarkTheme,
                    subtitle = "EMG readings depend on the person and where the pads sit."
                )
                Spacer(Modifier.height(8.dp))
                MuscleCalibrationCard(
                    levels = emgLevels,
                    calibration = calibration,
                    canCalibrate = streamingFromThis && live.isStreaming,
                    isDarkTheme = isDarkTheme,
                    content = content,
                    muted = muted,
                    onStart = vm::startCalibration,
                    onSave = { vm.saveCalibration(context, it) },
                    onUseDefaults = { vm.useDefaultEmgLevels(context) },
                    onClose = vm::closeCalibration
                )
            }

            Spacer(Modifier.height(20.dp))
            SectionHeader(
                title = if (saved == null) "Choose your wearable" else "Choose a different wearable",
                darkTheme = isDarkTheme,
                subtitle = "Devices advertising the G-one serial service are listed first."
            )
            Spacer(Modifier.height(8.dp))
            GlassCard(darkTheme = isDarkTheme, modifier = Modifier.fillMaxWidth()) {
                WearablePicker(
                    selectedAddress = saved?.address,
                    colors = pickerColors,
                    onChoose = { vm.chooseWearable(context, it) }
                )
            }

            Spacer(Modifier.height(20.dp))
            SectionHeader(title = "What the app expects", darkTheme = isDarkTheme)
            Spacer(Modifier.height(8.dp))
            GlassCard(darkTheme = isDarkTheme, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "BLE service FFE0, characteristic FFE1 (the HM-10 serial profile). One line per " +
                        "reading, twice a second, ending in a newline:",
                    color = muted,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "HR:72,SPO2:97,STEMP:33.9,MOT:1.02,EMG:1234,EMGPK:1810,EMGBITS:12,ST:7F,TS:1726470000000",
                    color = content,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "On connect the phone writes T:<epoch ms> to set the wearable's clock. Readings " +
                        "kept on the SD card while out of range are replayed with BUF:1, their own TS " +
                        "and a record number (SEQ); the phone answers ACK:<n> once they are stored, " +
                        "and only then does the wearable delete them. Skin temperature (STEMP) is never " +
                        "treated as body temperature, and EMG is 12-bit ADC counts. The full protocol " +
                        "is in docs/WEARABLE_PROTOCOL.md.",
                    color = muted,
                    fontSize = 12.sp
                )
            }

            Spacer(Modifier.height(bottomPadding + 32.dp))
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 20.dp, top = 18.dp)
                .zIndex(10f)
        ) {
            PureBreadcrumbText(
                items = listOf(BreadcrumbItem("Home", onNavigateHome), BreadcrumbItem("Wearable")),
                isDarkTheme = isDarkTheme
            )
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget this wearable?") },
            text = {
                Text("Monitoring stops if it is using this wearable. Readings already recorded stay in your history.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    vm.forgetWearable(context)
                }) { Text("Forget", color = ErrorRed) }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Keep") } }
        )
    }
}

@Composable
private fun LinkDetails(live: WearableLiveState, now: Long, content: Color, muted: Color) {
    when (val link = live.link) {
        SourceStatus.Idle, SourceStatus.Starting -> StatusLine(Blue500, "Connecting…", content)
        SourceStatus.Streaming -> StatusLine(SuccessGreen, "Connected and receiving", content)
        is SourceStatus.Recovering -> StatusLine(WarnAmber, "Reconnecting (attempt ${link.attempt}): ${link.reason}", content)
        SourceStatus.Stopped -> StatusLine(muted, "Stopped", content)
        is SourceStatus.Failed -> StatusLine(ErrorRed, link.reason, content)
    }

    Spacer(Modifier.height(10.dp))
    val facts = buildList {
        add("Last data" to (live.lastDataAt?.let { secondsAgo(now - it) } ?: "none yet"))
        add("Lines accepted" to live.parser.linesAccepted.toString())
        add("Lines rejected" to live.parser.linesRejected.toString())
        add("Values dropped (bad or out of range)" to live.parser.fieldsRejected.toString())
        if (live.backlogLines > 0) add("Buffered lines replayed" to live.backlogLines.toString())
        add(
            "Wearable clock" to when {
                live.clockSyncFailed -> "could not be set"
                live.sensors?.clockSynced == true -> "set"
                else -> "waiting for the wearable to confirm"
            }
        )
    }
    facts.forEach { (label, value) ->
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(label, color = muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(value, color = content, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    Spacer(Modifier.height(10.dp))
    val sensors = live.sensors
    when {
        sensors == null -> Text("The wearable has not reported on its sensors yet.", color = muted, fontSize = 13.sp)
        sensors.problems().isEmpty() -> StatusLine(SuccessGreen, "All sensors report in order.", content)
        else -> sensors.problems().forEach { StatusLine(WarnAmber, it, content) }
    }
}

@Composable
internal fun StatusLine(dot: Color, text: String, content: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text(
            text,
            color = content,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 10.dp)
        )
    }
}

private fun secondsAgo(millis: Long): String {
    val s = (millis / 1000).coerceAtLeast(0)
    return when {
        s < 2 -> "just now"
        s < 90 -> "$s s ago"
        else -> "${s / 60} min ago"
    }
}
