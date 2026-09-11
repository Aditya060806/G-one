package com.infinity.ai.health.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.infinity.ai.MainActivity
import com.infinity.ai.health.data.AnomalyEventEntity
import com.infinity.ai.health.domain.Severity
import com.infinity.ai.health.explain.Explanation

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

    const val NOTIF_ID_MONITORING = 4201
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
    }

    /** The persistent foreground-service notification. */
    fun buildMonitoringNotification(
        context: Context,
        contentText: String
    ): Notification =
        NotificationCompat.Builder(context, CHANNEL_MONITORING)
            .setContentTitle("G-one is monitoring")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_view)
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
