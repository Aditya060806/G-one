package com.gone.ai.health.sos

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Texts and calls through the phone's own SIM: the mobile network, not the internet, so it
 * works with no data connection. Needs SEND_SMS and CALL_PHONE, which Android asks the wearer
 * for once, when they turn the automatic SOS on.
 *
 * A text counts as SENT only when the phone's radio reports it sent; if no report comes back
 * within [SENT_TIMEOUT_MILLIS] it is recorded as handed over, not as sent.
 */
class AndroidSosSender(context: Context) : SosSender {

    private val context = context.applicationContext

    override suspend fun sendSms(number: String, text: String): SosResult {
        if (!granted(Manifest.permission.SEND_SMS)) return SosResult.NO_PERMISSION
        val sms = smsManager() ?: return SosResult.CANNOT_SEND
        val parts = runCatching { sms.divideMessage(text) }.getOrNull() ?: return SosResult.CANNOT_SEND

        // Each part reports back separately; the text is sent when every part is.
        val action = "${context.packageName}.SOS_SMS_SENT.${UUID.randomUUID()}"
        val reports = Channel<Int>(parts.size)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                reports.trySend(resultCode)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val sent = ArrayList(parts.indices.map { i ->
                PendingIntent.getBroadcast(
                    context, i, Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                )
            })
            try {
                if (parts.size == 1) sms.sendTextMessage(number, null, parts[0], sent[0], null)
                else sms.sendMultipartTextMessage(number, null, parts, sent, null)
            } catch (e: SecurityException) {
                return SosResult.NO_PERMISSION
            } catch (e: Exception) {
                return SosResult.CANNOT_SEND
            }
            val codes = withTimeoutOrNull(SENT_TIMEOUT_MILLIS) { List(parts.size) { reports.receive() } }
                ?: return SosResult.HANDED_OVER
            return when {
                codes.all { it == Activity.RESULT_OK } -> SosResult.SENT
                codes.any { it == SmsManager.RESULT_ERROR_NO_SERVICE || it == SmsManager.RESULT_ERROR_RADIO_OFF } ->
                    SosResult.NO_SERVICE
                else -> SosResult.CANNOT_SEND
            }
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    /** Through the phone's calling service, which rings the number itself; no screen is opened by G-one. */
    @SuppressLint("MissingPermission")   // checked on the first line
    override fun placeCall(number: String): SosResult {
        if (!granted(Manifest.permission.CALL_PHONE)) return SosResult.NO_PERMISSION
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return SosResult.CANNOT_SEND
        return try {
            telecom.placeCall(Uri.fromParts("tel", number, null), Bundle())
            SosResult.CALL_PLACED
        } catch (e: SecurityException) {
            SosResult.NO_PERMISSION
        } catch (e: Exception) {
            SosResult.CANNOT_SEND
        }
    }

    private fun smsManager(): SmsManager? {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val SENT_TIMEOUT_MILLIS = 60_000L
        /** What Android must allow, once, for the automatic SOS. */
        val PERMISSIONS = arrayOf(Manifest.permission.SEND_SMS)

        fun hasPermissions(context: Context) = PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
