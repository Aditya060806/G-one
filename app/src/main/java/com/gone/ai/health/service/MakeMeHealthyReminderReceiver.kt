package com.gone.ai.health.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Delivers the reminder selected in Tools even when the app has no active UI. */
class MakeMeHealthyReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MakeMeHealthyReminderManager.onAlarm(context)
    }
}

/**
 * Android clears alarms when the phone restarts and when the app is updated, and stops any
 * running service. Without this, reminders the person turned on silently stopped after either,
 * and so did monitoring they had started and not stopped.
 */
class MakeMeHealthyBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (MakeMeHealthyReminderManager.isEnabled(context)) {
                    MakeMeHealthyReminderManager.scheduleNext(context)
                }
                WearablePreferences.wantedScenario(context)?.let { scenario ->
                    if (!HealthMonitoringService.isRunning.value) HealthMonitoringService.start(context, scenario)
                }
            }
        }
    }
}
