package com.infinity.ai.health.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.infinity.ai.MainActivity

/**
 * Manages "Make Me Healthy" wellness notification reminders (drink water, stand up, take a walk).
 */
object MakeMeHealthyReminderManager {

    const val CHANNEL_WELLNESS = "gone_healthy_reminders"
    private const val PREFS_NAME = "gone_healthy_prefs"
    private const val KEY_REMINDERS_ENABLED = "wellness_reminders_enabled"
    private const val KEY_REMINDER_TYPE = "wellness_reminder_type"
    private const val REMINDER_REQUEST_CODE = 7100
    private const val HOURLY_INTERVAL_MILLIS = 60 * 60 * 1000L

    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_REMINDERS_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_REMINDERS_ENABLED, enabled).apply()
        if (enabled) scheduleHourly(context) else cancelHourly(context)
    }

    fun selectedType(context: Context): ReminderType {
        val value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_REMINDER_TYPE, ReminderType.HYDRATE.name)
        return ReminderType.entries.firstOrNull { it.name == value } ?: ReminderType.HYDRATE
    }

    fun setSelectedType(context: Context, type: ReminderType) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_REMINDER_TYPE, type.name)
            .apply()
        if (isEnabled(context)) scheduleHourly(context)
    }

    fun scheduleHourly(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + HOURLY_INTERVAL_MILLIS,
            HOURLY_INTERVAL_MILLIS,
            reminderPendingIntent(context)
        )
    }

    fun cancelHourly(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(reminderPendingIntent(context))
    }

    private fun reminderPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REMINDER_REQUEST_CODE,
            Intent(context, MakeMeHealthyReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_WELLNESS,
            "Wellness Reminders",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Gentle prompts to hydrate, stand, and move throughout the day."
            enableVibration(true)
        }
        mgr.createNotificationChannel(channel)
    }

    enum class ReminderType(
        val title: String,
        val message: String,
        val id: Int
    ) {
        HYDRATE(
            title = "💧 Time to Hydrate",
            message = "Take a sip of water to maintain peak metabolic function and focus.",
            id = 7101
        ),
        STAND(
            title = "🚶 Stand & Reset",
            message = "You've been still for a while. Stand up, roll your shoulders, and stretch.",
            id = 7102
        ),
        MOVE(
            title = "🏃 Movement Boost",
            message = "Take a brisk 5-minute walk to encourage healthy circulation.",
            id = 7103
        )
    }

    fun sendReminder(context: Context, type: ReminderType) {
        createNotificationChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            type.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_WELLNESS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(type.title)
            .setContentText(type.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(type.message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(type.id, notification)
        }
    }
}
