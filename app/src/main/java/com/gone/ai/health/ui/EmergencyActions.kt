package com.gone.ai.health.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.gone.ai.data.UserProfile
import com.gone.ai.health.data.AnomalyEventEntity
import com.gone.ai.health.data.severityEnum
import com.gone.ai.health.domain.Severity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SOS actions, shared by the in-app alert banner and the Tools screen.
 *
 * NOTHING IS SENT OR DIALLED WITHOUT THE PERSON CONFIRMING.
 *
 * Both actions open the system dialer or messaging app with the number and message
 * filled in; the person presses call or send there. The alert banner used to send an
 * SMS silently via SmsManager on one tap — easy to trigger by accident, containing only
 * an internal identifier like "high_heart_rate", and dependent on SEND_SMS / CALL_PHONE,
 * which are restricted permissions on Google Play.
 */
object EmergencyActions {

    /** India's single emergency number, offered when no personal contact is saved. */
    const val EMERGENCY_SERVICES_NUMBER = "112"

    /** The saved emergency contact, or null. */
    fun savedContact(context: Context): String? = UserProfile.load(context).emergencyContact

    /** Open the dialer with [number] filled in. @return false if no dialer exists. */
    fun dial(context: Context, number: String): Boolean =
        launch(context, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))

    /** Open a new message to [number] with [body] filled in. @return false if no app handles it. */
    fun composeSms(context: Context, number: String, body: String): Boolean =
        launch(
            context,
            Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).putExtra("sms_body", body)
        )

    /** A message a family member can understand, naming what was flagged and when. */
    fun alertMessage(event: AnomalyEventEntity, userName: String?): String {
        val what = event.anomalyLabel()
        val urgency = when (event.severityEnum()) {
            Severity.CRITICAL -> "urgent"
            Severity.MODERATE -> "needs attention"
            Severity.LOW      -> "for information"
        }
        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(event.createdAt))
        val whose = userName?.let { "$it's" } ?: "My"
        return "$whose G-one health monitor flagged: $what ($urgency) at $time. Please check on me."
    }

    private fun launch(context: Context, intent: Intent): Boolean =
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
}
