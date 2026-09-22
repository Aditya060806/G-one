package com.gone.ai.health.ui

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.gone.ai.health.source.ble.NearbyWearable
import com.gone.ai.health.source.ble.ScanBlocker
import com.gone.ai.health.source.ble.WearableLinkException
import com.gone.ai.health.source.ble.WearableScanner
import kotlinx.coroutines.withTimeoutOrNull

/** Colours the picker takes from its host, so it fits onboarding and the device screen alike. */
data class PickerColors(val content: Color, val muted: Color, val accent: Color, val onAccent: Color)

/**
 * Finds the wearable and lets the user choose it.
 *
 * Handles everything that can stand between the user and a scan, in the order Android
 * enforces it — Bluetooth present, permission granted, Location on (Android 11 and older
 * only), Bluetooth on — and says which one is missing with an action that fixes it. A
 * scan that silently returns nothing, which is what Android does when one of these is
 * missing, would leave the user with no idea why.
 */
@Composable
fun WearablePicker(
    selectedAddress: String?,
    colors: PickerColors,
    onChoose: (NearbyWearable) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scanner = remember { WearableScanner(context) }

    var blocker by remember { mutableStateOf(scanner.blocker()) }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    var scanRound by remember { mutableIntStateOf(0) }
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<NearbyWearable>>(emptyList()) }
    var scanError by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionRequested = true
        blocker = scanner.blocker()
        if (blocker == null) scanRound++
    }

    // Bluetooth and Location toggled from the quick-settings shade land here.
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val before = blocker
                blocker = scanner.blocker()
                if (before != null && blocker == null) scanRound++
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
        }
        // System broadcasts still arrive with NOT_EXPORTED; it only shuts out other apps.
        runCatching { ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    LaunchedEffect(Unit) { if (blocker == null) scanRound++ }

    LaunchedEffect(scanRound) {
        if (scanRound == 0 || blocker != null) return@LaunchedEffect
        scanning = true
        scanError = null
        try {
            withTimeoutOrNull(SCAN_MILLIS) {
                scanner.scan().collect { device ->
                    found = (found.filterNot { it.address == device.address } + device)
                        .sortedWith(
                            compareByDescending<NearbyWearable> { it.advertisesSerialService }
                                .thenByDescending { it.rssi }
                        )
                }
            }
        } catch (e: WearableLinkException) {
            scanError = e.message
            blocker = scanner.blocker()
        } finally {
            scanning = false
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val currentBlocker = blocker
        if (currentBlocker != null) {
            Text(currentBlocker.message, color = colors.content, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            val action: Pair<String, () -> Unit>? = when (currentBlocker) {
                ScanBlocker.NO_BLUETOOTH -> null
                ScanBlocker.PERMISSION, ScanBlocker.LOCATION_PERMISSION ->
                    if (!permissionRequested) {
                        "Allow" to { permissionLauncher.launch(scanner.permissionsNeeded()) }
                    } else {
                        // Asked once and still missing: Android may not show the prompt again.
                        "Open app settings" to {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                ScanBlocker.LOCATION_OFF -> "Turn on Location" to {
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                ScanBlocker.BLUETOOTH_OFF -> "Open Bluetooth settings" to {
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (action != null) {
                    Button(
                        onClick = action.second,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text(action.first, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = {
                    blocker = scanner.blocker()
                    if (blocker == null) scanRound++
                }) { Text("Check again", color = colors.accent, fontWeight = FontWeight.SemiBold) }
            }
            return@Column
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (scanning) "Looking for your wearable…" else "Nearby devices",
                color = colors.content,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (scanning) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.accent)
            } else {
                TextButton(onClick = { found = emptyList(); scanRound++ }) {
                    Text("Scan again", color = colors.accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        scanError?.let { Text(it, color = colors.muted, fontSize = 13.sp) }

        if (!scanning && found.isEmpty() && scanRound > 0 && scanError == null) {
            Text(
                "Nothing found. Check the wearable is switched on and within a few metres, then scan again.",
                color = colors.muted,
                fontSize = 13.sp
            )
        }

        found.take(MAX_LISTED).forEach { device ->
            val selected = device.address.equals(selectedAddress, ignoreCase = true)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onChoose(device) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        device.name ?: "Unnamed device",
                        color = if (selected) colors.accent else colors.content,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        buildString {
                            append(device.address)
                            if (device.advertisesSerialService) append(" · G-one serial service")
                            append(" · ${signalLabel(device.rssi)}")
                        },
                        color = colors.muted,
                        fontSize = 12.sp
                    )
                }
                if (selected) Icon(Icons.Default.Check, "Chosen", tint = colors.accent, modifier = Modifier.size(20.dp))
            }
        }
    }
}

private fun signalLabel(rssi: Int): String = when {
    rssi >= -60 -> "strong signal"
    rssi >= -80 -> "fair signal"
    else -> "weak signal"
}

private const val SCAN_MILLIS = 12_000L
private const val MAX_LISTED = 8
