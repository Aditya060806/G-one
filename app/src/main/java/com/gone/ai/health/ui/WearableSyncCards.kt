package com.gone.ai.health.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gone.ai.health.domain.AnomalyThresholds
import com.gone.ai.health.domain.EmgCalibration
import com.gone.ai.health.service.SavedWearable
import com.gone.ai.health.service.WearableAutoSync
import com.gone.ai.health.service.WearableLiveState
import com.gone.ai.health.source.SourceStatus
import com.gone.ai.ui.components.GlassCard
import com.gone.ai.ui.theme.Blue500
import com.gone.ai.ui.theme.ErrorRed
import com.gone.ai.ui.theme.SuccessGreen
import com.gone.ai.ui.theme.WarnAmber

/**
 * Catching up with the wearable: what is waiting, when it last had everything, and a way to
 * sync now. Automatic sync when the wearable is nearby is switched on here too.
 */
@Composable
internal fun WearableSyncCard(
    device: SavedWearable,
    live: WearableLiveState,
    streamingFromThis: Boolean,
    autoSync: Boolean,
    autoSyncSupported: Boolean,
    now: Long,
    isDarkTheme: Boolean,
    content: Color,
    muted: Color,
    onSyncNow: () -> Unit,
    onAutoSyncApproved: () -> Unit,
    onAutoSyncFailed: (String) -> Unit,
    onDisableAutoSync: () -> Unit
) {
    val context = LocalContext.current
    val sync = live.sync
    GlassCard(darkTheme = isDarkTheme, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            val pending = sync.pendingOnWearable ?: 0
            when {
                // Only a wearable that has reported a working SD card keeps readings; the firmware
                // ships live-only, and one with no card sends readings as they happen or not at all.
                !streamingFromThis && live.sensors?.sdCardPresent == true ->
                    StatusLine(muted, "Not syncing. Readings stay on the wearable until the phone collects them.", content)
                !streamingFromThis -> StatusLine(muted, "Not syncing. Start monitoring to receive readings from the wearable.", content)
                live.link is SourceStatus.Recovering -> StatusLine(WarnAmber, "Waiting for the wearable to come back in range.", content)
                pending > 0 -> StatusLine(Blue500, "Catching up: $pending ${plural(pending, "reading")} still on the wearable (${durationOf(pending)}).", content)
                sync.lastSyncedAt != null -> StatusLine(SuccessGreen, "Up to date.", content)
                else -> StatusLine(Blue500, "Connected, checking for stored readings…", content)
            }
            if (streamingFromThis && pending > 0) {
                val received = sync.receivedRecords
                LinearProgressIndicator(
                    progress = { (received.toFloat() / (received + pending).coerceAtLeast(1)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    color = Blue500
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        val facts = buildList {
            add("Last synced" to (sync.lastSyncedAt?.let { ago(now - it) } ?: "not yet this run"))
            if (sync.receivedRecords > 0) add("Stored readings collected" to sync.receivedRecords.toString())
            if (sync.lostRecords > 0) add("Readings that could not be placed in time" to sync.lostRecords.toString())
        }
        facts.forEach { (label, value) ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(label, color = muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(value, color = content, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (sync.lostRecords > 0) {
            Text(
                "These were taken after the wearable restarted away from the phone, before its clock was set again. They are counted, not stored.",
                color = muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (sync.ackWriteFailed) {
            StatusLine(WarnAmber, "The wearable has not been told what was saved yet. It keeps those readings and sends them again.", content)
        }

        val canSyncNow = !streamingFromThis || live.link is SourceStatus.Recovering
        OutlinedButton(
            onClick = onSyncNow,
            enabled = canSyncNow,
            modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp)
        ) { Text(if (streamingFromThis) "Try to reconnect now" else "Sync now") }

        Spacer(Modifier.height(12.dp))
        val approval = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) onAutoSyncApproved() else onAutoSyncFailed("the request was not approved")
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .toggleable(
                    value = autoSync,
                    enabled = autoSyncSupported,
                    role = Role.Switch,
                    onValueChange = { on ->
                        if (!on) {
                            onDisableAutoSync()
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            WearableAutoSync.requestAssociation(
                                context,
                                device.address,
                                onApprovalNeeded = { approval.launch(IntentSenderRequest.Builder(it).build()) },
                                onFailure = onAutoSyncFailed
                            )
                        }
                    }
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Sync automatically when nearby", color = content, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (autoSyncSupported) {
                        "Android watches for ${device.name} and starts monitoring when it comes into range."
                    } else {
                        "Needs Android 12 or later. Start monitoring yourself to sync."
                    },
                    color = muted,
                    fontSize = 12.sp
                )
            }
            Switch(checked = autoSync, onCheckedChange = null, enabled = autoSyncSupported)
        }
    }
}

/**
 * Asks to be left running in the background, where Android and phone makers otherwise stop
 * apps and the wearable link with them.
 */
@Composable
internal fun BackgroundSyncCard(isDarkTheme: Boolean, content: Color, muted: Color) {
    val context = LocalContext.current
    var exempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) exempt = isIgnoringBatteryOptimizations(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    GlassCard(darkTheme = isDarkTheme, modifier = Modifier.fillMaxWidth()) {
        if (exempt) {
            StatusLine(SuccessGreen, "G-one may keep running in the background.", content)
        } else {
            StatusLine(WarnAmber, "Android may stop G-one in the background, and syncing with it.", content)
            Text(
                "Allow G-one to run without battery restrictions so the wearable keeps syncing with the screen off.",
                color = muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
            OutlinedButton(
                onClick = { requestIgnoreBatteryOptimizations(context) },
                modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp)
            ) { Text("Allow background use") }
        }
        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            Text(
                "On Samsung phones, also open Settings › Battery › Background usage limits › Never sleeping apps, and add G-one.",
                color = muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/** The wearer's muscle levels, and the wizard that measures them. */
@Composable
internal fun MuscleCalibrationCard(
    levels: EmgCalibration.Result.Levels?,
    calibration: HealthViewModel.Calibration,
    canCalibrate: Boolean,
    isDarkTheme: Boolean,
    content: Color,
    muted: Color,
    onStart: () -> Unit,
    onSave: (EmgCalibration.Result.Levels) -> Unit,
    onUseDefaults: () -> Unit,
    onClose: () -> Unit
) {
    val defaults = AnomalyThresholds.DEFAULT
    GlassCard(darkTheme = isDarkTheme, modifier = Modifier.fillMaxWidth()) {
        if (levels == null) {
            StatusLine(WarnAmber, "Using default levels, which are not tuned to you.", content)
        } else {
            StatusLine(SuccessGreen, "Calibrated to you: relaxed ${levels.relaxed}, clench ${levels.clench}.", content)
        }
        Spacer(Modifier.height(6.dp))
        listOf(
            "Active from" to (levels?.active ?: defaults.emgActiveLevel),
            "High (held at rest)" to (levels?.high ?: defaults.emgHighLevel),
            "Very high (held at rest)" to (levels?.veryHigh ?: defaults.emgVeryHighLevel)
        ).forEach { (label, value) ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(label, color = muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(value.toString(), color = content, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Text(
            if (canCalibrate) {
                "Takes about 30 seconds: relax the muscle for ${EmgCalibration.RELAX_SECONDS} seconds, then clench firmly for ${EmgCalibration.CLENCH_SECONDS}."
            } else {
                "Start monitoring with the wearable on to calibrate."
            },
            color = muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = onStart, enabled = canCalibrate, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Calibrate")
            }
            if (levels != null) {
                TextButton(onClick = onUseDefaults, modifier = Modifier.heightIn(min = 48.dp)) { Text("Use defaults") }
            }
        }
    }

    if (calibration != HealthViewModel.Calibration.Idle) {
        CalibrationDialog(calibration, onSave, onClose)
    }
}

@Composable
private fun CalibrationDialog(
    calibration: HealthViewModel.Calibration,
    onSave: (EmgCalibration.Result.Levels) -> Unit,
    onClose: () -> Unit
) {
    val finished = calibration as? HealthViewModel.Calibration.Finished
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Calibrate the muscle sensor") },
        text = {
            Column(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) {
                when (calibration) {
                    is HealthViewModel.Calibration.Relaxing -> {
                        Text("Relax the muscle completely.", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        Text("${calibration.secondsLeft} s left · ${calibration.readings} readings")
                    }
                    is HealthViewModel.Calibration.GetReady -> {
                        Text("Get ready to clench firmly…", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        Text("Starting in ${calibration.secondsLeft} s")
                    }
                    is HealthViewModel.Calibration.Clenching -> {
                        Text("Clench firmly now and hold.", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        Text("${calibration.secondsLeft} s left · ${calibration.readings} readings")
                    }
                    is HealthViewModel.Calibration.Finished -> when (val result = calibration.result) {
                        is EmgCalibration.Result.Levels -> {
                            Text("Relaxed ${result.relaxed}, clench ${result.clench}.")
                            Text("New levels: active from ${result.active}, high ${result.high}, very high ${result.veryHigh}.")
                        }
                        is EmgCalibration.Result.Problem -> Text(result.message, color = ErrorRed)
                    }
                    HealthViewModel.Calibration.Idle -> Unit
                }
            }
        },
        confirmButton = {
            val levels = finished?.result as? EmgCalibration.Result.Levels
            if (levels != null) {
                TextButton(onClick = { onSave(levels) }) { Text("Save") }
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text(if (finished != null) "Close" else "Cancel") }
        }
    )
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true

/** Android's own confirmation; the list in Settings when a phone has no such dialog. */
@SuppressLint("BatteryLife")   // continuous health monitoring from a wearable is the purpose this request exists for
private fun requestIgnoreBatteryOptimizations(context: Context) {
    val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
    try {
        context.startActivity(request)
    } catch (_: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

private fun plural(n: Long, word: String) = if (n == 1L) word else "${word}s"

/** Two readings a second: how much time that many readings covers. */
private fun durationOf(readings: Long): String {
    val seconds = readings / 2
    return when {
        seconds < 90 -> "about $seconds s"
        seconds < 5_400 -> "about ${seconds / 60} min"
        else -> "about ${seconds / 3_600} h"
    }
}

private fun ago(millis: Long): String {
    val s = (millis / 1000).coerceAtLeast(0)
    return when {
        s < 2 -> "just now"
        s < 90 -> "$s s ago"
        s < 5_400 -> "${s / 60} min ago"
        else -> "${s / 3_600} h ago"
    }
}
