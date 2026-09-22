package com.gone.ai.health.service

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.gone.ai.MainActivity
import java.time.ZonedDateTime

/**
 * "Make me Healthy" reminders: drink water, stand up, move.
 *
 * One reminder at the top of each hour inside the person's active hours
 * ([ReminderSchedule]). Each alarm schedules the next when it fires, and
 * [MakeMeHealthyBootReceiver] schedules again after a restart or an app update, because
 * Android clears alarms on both.
 */
object MakeMeHealthyReminderManager {

    const val CHANNEL_WELLNESS = "gone_healthy_reminders"
    private const val PREFS_NAME = "gone_healthy_prefs"
    private const val KEY_REMINDERS_ENABLED = "wellness_reminders_enabled"
    private const val KEY_REMINDER_TYPE = "wellness_reminder_type"
    private const val KEY_TAKE_TURNS = "wellness_take_turns"
    private const val KEY_LAST_SENT = "wellness_last_sent"
    private const val KEY_START_HOUR = "wellness_start_hour"
    private const val KEY_END_HOUR = "wellness_end_hour"
    private const val REMINDER_REQUEST_CODE = 7100

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_REMINDERS_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_REMINDERS_ENABLED, enabled) }
        if (enabled) scheduleNext(context) else cancel(context)
    }

    fun selectedType(context: Context): ReminderType {
        val value = prefs(context).getString(KEY_REMINDER_TYPE, ReminderType.HYDRATE.name)
        return ReminderType.entries.firstOrNull { it.name == value } ?: ReminderType.HYDRATE
    }

    fun setSelectedType(context: Context, type: ReminderType) {
        prefs(context).edit {
            putString(KEY_REMINDER_TYPE, type.name)
            putBoolean(KEY_TAKE_TURNS, false)
        }
    }

    /** True when reminders cycle through drink, stand and move instead of one kind. */
    fun takesTurns(context: Context): Boolean = prefs(context).getBoolean(KEY_TAKE_TURNS, false)

    fun setTakeTurns(context: Context) {
        prefs(context).edit { putBoolean(KEY_TAKE_TURNS, true) }
    }

    fun window(context: Context): ReminderSchedule.Window {
        val p = prefs(context)
        val start = p.getInt(KEY_START_HOUR, ReminderSchedule.DEFAULT_START_HOUR)
        val end = p.getInt(KEY_END_HOUR, ReminderSchedule.DEFAULT_END_HOUR)
        return runCatching { ReminderSchedule.Window(start, end) }
            .getOrDefault(ReminderSchedule.Window(ReminderSchedule.DEFAULT_START_HOUR, ReminderSchedule.DEFAULT_END_HOUR))
    }

    fun setWindow(context: Context, window: ReminderSchedule.Window) {
        prefs(context).edit {
            putInt(KEY_START_HOUR, window.startHour)
            putInt(KEY_END_HOUR, window.endHour)
        }
        if (isEnabled(context)) scheduleNext(context)
    }

    /** Schedule the next reminder inside the active hours, replacing any already set. */
    fun scheduleNext(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val next = ReminderSchedule.next(ZonedDateTime.now(), window(context))
        // Inexact on purpose: needs no exact-alarm permission, and a few minutes late is fine.
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            next.toInstant().toEpochMilli(),
            reminderPendingIntent(context)
        )
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(reminderPendingIntent(context))
    }

    /** Called when an alarm fires: show the reminder if it is still inside the active hours. */
    fun onAlarm(context: Context) {
        if (!isEnabled(context)) return
        if (ReminderSchedule.shouldShow(ZonedDateTime.now(), window(context))) {
            val type = if (takesTurns(context)) {
                val last = prefs(context).getString(KEY_LAST_SENT, null)
                    ?.let { name -> ReminderType.entries.firstOrNull { it.name == name } }
                ReminderSchedule.nextInTurn(ReminderType.entries.toList(), last)
            } else {
                selectedType(context)
            }
            if (sendReminder(context, type)) prefs(context).edit { putString(KEY_LAST_SENT, type.name) }
        }
        scheduleNext(context)
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
            description = "Gentle prompts to hydrate, stand, and move during your active hours."
            enableVibration(true)
        }
        mgr.createNotificationChannel(channel)
    }

    enum class ReminderType(
        val label: String,
        val title: String,
        val message: String,
        val id: Int
    ) {
        HYDRATE(
            label = "Drink",
            title = "Time for some water",
            message = "Have a glass of water.",
            id = 7101
        ),
        STAND(
            label = "Stand",
            title = "Stand and stretch",
            message = "You may have been sitting a while. Stand up, roll your shoulders and stretch.",
            id = 7102
        ),
        MOVE(
            label = "Move",
            title = "A short walk",
            message = "If you can, take a brisk five-minute walk.",
            id = 7103
        )
    }

    /** True when notifications can be shown: the Android 13+ permission is granted. */
    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Show [type] now. Returns false when notifications are not allowed. */
    fun sendReminder(context: Context, type: ReminderType): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
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
            .setSmallIcon(com.gone.ai.R.drawable.ic_stat_gone)
            .setContentTitle(type.title)
            .setContentText(type.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(type.message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(type.id, notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }
}
