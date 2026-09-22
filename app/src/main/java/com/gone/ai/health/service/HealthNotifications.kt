package com.gone.ai.health.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.gone.ai.MainActivity
import com.gone.ai.R
import com.gone.ai.health.data.AnomalyEventEntity
import com.gone.ai.health.domain.Severity
import com.gone.ai.health.explain.Explanation
import com.gone.ai.health.sos.SosCoordinator
import com.gone.ai.health.sos.SosRecord

/**
 * Notification channels and builders for health alerts.
 *
 * TWO CHANNELS, SPLIT BY URGENCY.
 *
 * The ongoing monitoring notification is IMPORTANCE_LOW — it must be silent and
 * unobtrusive, because it is present permanently and a service notification that
 * buzzes would get the app uninstalled. Health alerts are IMPORTANCE_HIGH so they can
 * actually interrupt.
 *
 * Separating them also means a user who silences the persistent notification does not
 * accidentally silence critical alerts, which on a single shared channel they would.
 */
object HealthNotifications {

    const val CHANNEL_MONITORING = "gone_monitoring"
    const val CHANNEL_ALERTS = "gone_health_alerts"
    /** Its own channel, sounding as an alarm: the countdown must be noticed to be cancelled. */
    const val CHANNEL_SOS = "gone_sos"

    const val NOTIF_ID_MONITORING = 4201
    const val NOTIF_ID_SOS = 4202
    const val NOTIF_ID_SOS_RESULT = 4203
    /** Alert IDs are offset so they can never collide with the ongoing notification. */
    private const val ALERT_ID_BASE = 5000

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return

        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MONITORING,
                "Health monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows that G-one is watching your vitals in the background."
                setShowBadge(false)
            }
        )

        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                "Health alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Warnings when your readings need attention."
                enableVibration(true)
            }
        )

        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SOS,
                "Emergency SOS",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "The countdown before G-one texts and calls your emergency contact, and what it sent."
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 700, 300, 700, 300, 700)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
        )
    }

    // ── SOS ──────────────────────────────────────────────────────────────────

    /**
     * The countdown before the SOS goes, with "I'm OK" to stop it. The phone shows the time
     * left itself, so this is not re-posted every second; it is re-posted, silently, when another
     * reason joins the message.
     */
    fun showSosCountdown(context: Context, pending: SosCoordinator.Pending) {
        // Without the permission (Android 13+) nothing is shown; the SOS itself still goes.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val imOk = PendingIntent.getService(
            context, 0,
            Intent(context, HealthMonitoringService::class.java).setAction(HealthMonitoringService.ACTION_CANCEL_SOS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val what = pending.reasons.joinToString("; ")
        val notification = NotificationCompat.Builder(context, CHANNEL_SOS)
            .setSmallIcon(R.drawable.ic_stat_gone)
            .setContentTitle("SOS to your emergency contact")
            .setContentText("$what. Sending unless you tap I'm OK.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$what.\n\nG-one will text, then call, your emergency contact when the countdown ends, unless you tap I'm OK."))
            .setWhen(pending.sendAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(context))
            .addAction(0, "I'm OK, don't send", imOk)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID_SOS, notification) }
    }

    fun cancelSosCountdown(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_ID_SOS) }
    }

    /** What the SOS did: the wearer should know their contact was texted, and whether it worked. */
    fun notifySosResult(context: Context, record: SosRecord) {
        // Without the permission (Android 13+) nothing is shown; the SOS itself still goes.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val title = if (record.sms.sent) "SOS sent to ${record.number}" else "SOS could not be sent"
        val call = record.call?.let { if (it.sent) " Call: started." else " Call: ${it.words}." }.orEmpty()
        val text = "Text: ${record.sms.words}.$call"
        val notification = NotificationCompat.Builder(context, CHANNEL_SOS)
            .setSmallIcon(R.drawable.ic_stat_gone)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID_SOS_RESULT, notification) }
    }

    /** The persistent foreground-service notification. */
    fun buildMonitoringNotification(
        context: Context,
        contentText: String
    ): Notification =
        NotificationCompat.Builder(context, CHANNEL_MONITORING)
            .setContentTitle("G-one is monitoring")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_gone)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openAppIntent(context))
            .build()

    fun alertIdFor(eventId: Long): Int = (ALERT_ID_BASE + (eventId % 1000)).toInt()

    /**
     * A health alert.
     *
     * Uses BigTextStyle so the full explanation is readable without opening the app —
     * which matters when the person who needs to act is a family member glancing at a
     * locked screen.
     */
    fun buildAlertNotification(
        context: Context,
        event: AnomalyEventEntity,
        explanation: Explanation
    ): Notification {
        val severity = Severity.fromWireName(event.severity)
        val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setContentTitle(explanation.headline)
            .setContentText(explanation.tier.label)
            .setStyle(NotificationCompat.BigTextStyle().bigText(explanation.full))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(openAppIntent(context))
            .setPriority(
                when (severity) {
                    Severity.CRITICAL -> NotificationCompat.PRIORITY_MAX
                    Severity.MODERATE -> NotificationCompat.PRIORITY_HIGH
                    Severity.LOW      -> NotificationCompat.PRIORITY_DEFAULT
                }
            )

        if (severity == Severity.CRITICAL) {
            // Bypasses Do Not Disturb where the user has allowed it. A critical
            // desaturation at 3am is exactly the case DND must not swallow.
            builder.setCategory(NotificationCompat.CATEGORY_ALARM)
            builder.setOngoing(false)
        }

        return builder.build()
    }

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

/**
 * Production [AlertSink]: posts Android notifications.
 *
 * Every post is wrapped in runCatching because POST_NOTIFICATIONS can be revoked at
 * any time on API 33+. A missing notification permission must degrade the alert to
 * "recorded but not shown", never crash the monitoring service.
 */
class NotificationAlertSink(
    private val context: Context
) : AlertSink {

    override suspend fun onAnomaly(
        eventId: Long,
        event: AnomalyEventEntity,
        explanation: Explanation
    ) {
        // Without the permission (Android 13+) the alert is still stored and shown in the app.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        runCatching {
            NotificationManagerCompat.from(context).notify(
                HealthNotifications.alertIdFor(eventId),
                HealthNotifications.buildAlertNotification(context, event, explanation)
            )
        }
    }

    override suspend fun onExplanationUpgraded(eventId: Long, text: String) {
        // Intentionally does NOT re-notify. The user has already been alerted; firing
        // a second notification for the same event with nicer wording would be noise.
        // The in-app alert list reads the updated row and shows the better text there.
    }
}
