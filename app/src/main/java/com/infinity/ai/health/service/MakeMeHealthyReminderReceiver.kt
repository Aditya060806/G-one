package com.infinity.ai.health.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Delivers the reminder selected in Tools even when the app has no active UI. */
class MakeMeHealthyReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (MakeMeHealthyReminderManager.isEnabled(context)) {
            MakeMeHealthyReminderManager.sendReminder(
                context,
                MakeMeHealthyReminderManager.selectedType(context)
            )
            MakeMeHealthyReminderManager.scheduleHourly(context)
        }
    }
}
