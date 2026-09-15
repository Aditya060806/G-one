package com.infinity.ai.health.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SmsManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.infinity.ai.health.data.AnomalyEventEntity
import com.infinity.ai.health.data.severityEnum
import com.infinity.ai.health.domain.Severity
import com.infinity.ai.ui.theme.AccentGoldBg
import com.infinity.ai.ui.theme.BaseBg
import com.infinity.ai.ui.theme.BaseFg
import com.infinity.ai.ui.theme.CardBg
import com.infinity.ai.ui.theme.DarkBorder
import com.infinity.ai.ui.theme.DarkSurfaceElevated
import com.infinity.ai.ui.theme.DestructiveBg
import com.infinity.ai.ui.theme.FontSans
import com.infinity.ai.ui.theme.PrimaryBg
import com.infinity.ai.ui.theme.TextPrimary
import com.infinity.ai.ui.theme.TokenBorder

/**
 * App-wide, transient surface for active clinical alerts. It intentionally only
 * presents live alerts; wellness reminders remain system-notification only.
 */
@Composable
fun InAppAlertStack(
    events: List<AnomalyEventEntity>,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier,
    onAcknowledge: ((Long) -> Unit)? = null
) {
    val context = LocalContext.current
    var dismissedIds by remember { mutableStateOf(setOf<Long>()) }
    var pendingAction by remember { mutableStateOf<SosAction?>(null) }
    val visibleEvents = events.filterNot { it.id in dismissedIds }.take(3)

    fun sendSms(event: AnomalyEventEntity) {
        val contact = emergencyContact(context) ?: return
        runCatching {
            val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(
                contact,
                null,
                "G-one alert: ${event.eventType}. Please check on me.",
                null,
                null
            )
        }.onSuccess {
            Toast.makeText(context, "SOS message sent", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, "Unable to send SOS message", Toast.LENGTH_SHORT).show()
        }
    }

    fun placeCall(event: AnomalyEventEntity) {
        val contact = emergencyContact(context) ?: return
        runCatching {
            context.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$contact")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }.onFailure {
            Toast.makeText(context, "Unable to place SOS call", Toast.LENGTH_SHORT).show()
        }
    }

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingAction
        pendingAction = null
        if (granted && action != null) sendSms(action.event)
    }
    val callPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingAction
        pendingAction = null
        if (granted && action != null) placeCall(action.event)
    }

    if (visibleEvents.isNotEmpty()) {
        Box(modifier = modifier.fillMaxWidth()) {
            visibleEvents.asReversed().forEachIndexed { reverseIndex, event ->
                val offset = (visibleEvents.size - reverseIndex - 1) * 10
                AlertStackCard(
                    event = event,
                    isDarkTheme = isDarkTheme,
                    compact = reverseIndex != visibleEvents.lastIndex,
                    modifier = Modifier.padding(top = offset.dp),
                    onDismiss = {
                        dismissedIds = dismissedIds + event.id
                        onAcknowledge?.invoke(event.id)
                    },
                    onSos = {
                        if (event.severityEnum() == Severity.CRITICAL) {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                                placeCall(event)
                            } else {
                                pendingAction = SosAction(event)
                                callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
                            }
                        } else {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                                sendSms(event)
                            } else {
                                pendingAction = SosAction(event)
                                smsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
                            }
                        }
                    }
                )
            }
        }
    }
}

private data class SosAction(val event: AnomalyEventEntity)

private fun emergencyContact(context: Context): String? {
    val prefs = context.getSharedPreferences("gone_preferences", Context.MODE_PRIVATE)
    return prefs.getString("emergency_contact", "")?.takeIf { it.isNotBlank() }
        ?: prefs.getString("family_contact", "")?.takeIf { it.isNotBlank() }
        ?: run {
            Toast.makeText(context, "Add an emergency contact first", Toast.LENGTH_SHORT).show()
            null
        }
}

@Composable
private fun AlertStackCard(
    event: AnomalyEventEntity,
    isDarkTheme: Boolean,
    compact: Boolean,
    modifier: Modifier,
    onDismiss: () -> Unit,
    onSos: () -> Unit
) {
    val severity = event.severityEnum()
    val surface = if (isDarkTheme) DarkSurfaceElevated else CardBg
    val text = if (isDarkTheme) TextPrimary else BaseFg
    val border = if (isDarkTheme) DarkBorder else TokenBorder
    val actionColor = if (severity == Severity.CRITICAL) DestructiveBg else AccentGoldBg

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(surface)
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = event.eventType.replace('_', ' '),
                modifier = Modifier.weight(1f),
                fontFamily = FontSans,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 15.sp,
                color = text
            )
            Text(
                text = "Dismiss",
                modifier = Modifier.clickable(onClick = onDismiss).padding(start = 14.dp),
                fontFamily = FontSans,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = if (isDarkTheme) AccentGoldBg else PrimaryBg
            )
        }
        if (!compact) {
            Text(
                text = event.displayExplanation,
                modifier = Modifier.padding(top = 5.dp),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = text.copy(alpha = 0.72f)
            )
            Row(
                modifier = Modifier.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onSos,
                    colors = ButtonDefaults.buttonColors(containerColor = actionColor, contentColor = BaseBg),
                    shape = RoundedCornerShape(7.dp)
                ) {
                    Text("Contact SOS", fontFamily = FontSans, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = if (severity == Severity.CRITICAL) "Calls your contact" else "Messages your contact",
                    fontSize = 12.sp,
                    color = text.copy(alpha = 0.62f)
                )
            }
        }
    }
}
