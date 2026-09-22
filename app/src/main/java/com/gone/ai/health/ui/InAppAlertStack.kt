package com.gone.ai.health.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gone.ai.data.UserProfile
import com.gone.ai.health.data.AnomalyEventEntity
import com.gone.ai.health.data.severityEnum
import com.gone.ai.health.domain.Severity
import com.gone.ai.ui.theme.AccentGoldBg
import com.gone.ai.ui.theme.BaseBg
import com.gone.ai.ui.theme.BaseFg
import com.gone.ai.ui.theme.CardBg
import com.gone.ai.ui.theme.DarkBorder
import com.gone.ai.ui.theme.DarkSurfaceElevated
import com.gone.ai.ui.theme.DestructiveBg
import com.gone.ai.ui.theme.FontSans
import com.gone.ai.ui.theme.PrimaryBg
import com.gone.ai.ui.theme.TextPrimary
import com.gone.ai.ui.theme.TokenBorder

/**
 * App-wide, transient surface for active clinical alerts. It intentionally only
 * presents live alerts; wellness reminders remain system-notification only.
 *
 * "Hide" and "Mark seen" are different actions on purpose. Hiding removes the banner for
 * now and records nothing; the alert stays active in Trails. Marking it seen acknowledges
 * it in the health record. Dismissing a banner used to acknowledge the alert, so clearing
 * the screen quietly marked a critical event as handled.
 */
@Composable
fun InAppAlertStack(
    events: List<AnomalyEventEntity>,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier,
    onAcknowledge: ((Long) -> Unit)? = null
) {
    val context = LocalContext.current
    // Saveable, so hidden banners stay hidden across rotation.
    var hiddenIds by rememberSaveable { mutableStateOf(setOf<Long>()) }
    val visibleEvents = events.filterNot { it.id in hiddenIds }.take(3)
    val hasContact = remember(events) { UserProfile.load(context).emergencyContact != null }

    fun contactSos(event: AnomalyEventEntity) {
        val profile = UserProfile.load(context)
        val critical = event.severityEnum() == Severity.CRITICAL
        val contact = profile.emergencyContact

        val opened = when {
            contact != null && critical -> EmergencyActions.dial(context, contact)
            contact != null -> EmergencyActions.composeSms(
                context, contact, EmergencyActions.alertMessage(event, profile.name)
            )
            // No personal contact saved: for a critical alert, offer emergency services.
            critical -> EmergencyActions.dial(context, EmergencyActions.EMERGENCY_SERVICES_NUMBER)
            else -> {
                Toast.makeText(context, "Add an emergency contact in Settings › Profile", Toast.LENGTH_LONG).show()
                return
            }
        }
        if (!opened) Toast.makeText(context, "No app on this phone can place calls or send messages", Toast.LENGTH_SHORT).show()
    }

    if (visibleEvents.isNotEmpty()) {
        Box(modifier = modifier.fillMaxWidth()) {
            visibleEvents.asReversed().forEachIndexed { reverseIndex, event ->
                val offset = (visibleEvents.size - reverseIndex - 1) * 10
                AlertStackCard(
                    event = event,
                    isDarkTheme = isDarkTheme,
                    hasContact = hasContact,
                    compact = reverseIndex != visibleEvents.lastIndex,
                    modifier = Modifier.padding(top = offset.dp),
                    onHide = { hiddenIds = hiddenIds + event.id },
                    onMarkSeen = onAcknowledge?.let { ack -> { ack(event.id) } },
                    onSos = { contactSos(event) }
                )
            }
        }
    }
}

@Composable
private fun AlertStackCard(
    event: AnomalyEventEntity,
    isDarkTheme: Boolean,
    hasContact: Boolean,
    compact: Boolean,
    modifier: Modifier,
    onHide: () -> Unit,
    onMarkSeen: (() -> Unit)?,
    onSos: () -> Unit
) {
    val severity = event.severityEnum()
    val critical = severity == Severity.CRITICAL
    val surface = if (isDarkTheme) DarkSurfaceElevated else CardBg
    val text = if (isDarkTheme) TextPrimary else BaseFg
    val border = if (isDarkTheme) DarkBorder else TokenBorder
    val actionColor = if (critical) DestructiveBg else AccentGoldBg
    val linkColor = if (isDarkTheme) AccentGoldBg else PrimaryBg

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(surface)
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 16.dp, vertical = 13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = event.anomalyLabel(),
                modifier = Modifier.weight(1f),
                fontFamily = FontSans,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 15.sp,
                color = text
            )
            Text(
                text = "Hide",
                modifier = Modifier.minimumInteractiveComponentSize().clickable(role = Role.Button, onClick = onHide).padding(start = 14.dp),
                fontFamily = FontSans,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = linkColor
            )
        }
        if (!compact) {
            Text(
                text = event.displayExplanation,
                modifier = Modifier.padding(top = 5.dp),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 4,
                color = text.copy(alpha = 0.72f)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSos,
                    colors = ButtonDefaults.buttonColors(containerColor = actionColor, contentColor = BaseBg),
                    shape = RoundedCornerShape(7.dp)
                ) {
                    Text(
                        text = when {
                            critical && hasContact -> "Call contact"
                            critical -> "Call ${EmergencyActions.EMERGENCY_SERVICES_NUMBER}"
                            else -> "Text contact"
                        },
                        fontFamily = FontSans,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (onMarkSeen != null) {
                    TextButton(onClick = onMarkSeen) {
                        Text("Mark seen", color = linkColor, fontFamily = FontSans, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text(
                text = if (critical) "Opens your dialer — you press call." else "Opens a message — you press send.",
                modifier = Modifier.padding(top = 4.dp),
                fontSize = 11.sp,
                color = text.copy(alpha = 0.55f)
            )
        }
    }
}
