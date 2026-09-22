package com.gone.ai.health.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gone.ai.health.emergency.EmergencySharingState
import com.gone.ai.ui.components.GlassCard
import java.text.DateFormat
import java.util.Date

@Composable
fun EmergencyOnlineSharingCard(state: EmergencySharingState, onEnable: () -> Unit, onDisable: () -> Unit, onRetry: () -> Unit, isDarkTheme: Boolean) {
    val ctx = LocalContext.current
    GlassCard(darkTheme = isDarkTheme) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.CloudSync, null)
                Text("Keep my emergency link updated", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            }
            Text("One tag, one unique link. Save your details above first. With sharing enabled, saved edits sync when connected. Wearable snapshots can update about every 30 seconds while monitoring runs; Android background limits may delay retries.", fontSize = 13.sp)
            Text(state.message, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            state.syncedAt?.let { Text("Last upload: ${DateFormat.getDateTimeInstance().format(Date(it))}", fontSize = 12.sp) }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.enabled || state.removing) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRetry, enabled = !state.busy) { Text("Retry now") }
                    if (state.enabled) OutlinedButton(onClick = onDisable, enabled = !state.busy) { Text("Stop sharing") }
                }
                if (state.enabled && state.syncedAt != null && state.url != null) {
                    OutlinedButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.url))) }) { Text("Open my emergency page") }
                }
            } else Button(onClick = onEnable, enabled = !state.busy) { Text("Enable online sharing…") }
            Text("The generic /e link already on your tag must be replaced once using Web link mode below. Offline chip records never update remotely. Stop sharing and confirm removal before uninstalling or clearing app data; this phone holds the link’s edit key.", fontSize = 12.sp)
        }
    }
}
