package com.gone.ai.health.service

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.gone.ai.health.source.VitalsScenario

/**
 * Bound by Android when the associated wearable comes into range (see [WearableAutoSync]).
 *
 * Starts monitoring if the person has auto-sync on, has not switched to simulated vitals, and
 * monitoring is not already running. It never stops monitoring when the wearable leaves:
 * the link's own reconnect handles a short absence, and stopping is the person's choice.
 */
@RequiresApi(Build.VERSION_CODES.S)
class WearablePresenceService : CompanionDeviceService() {

    @Deprecated("Android 12 only; later versions call the AssociationInfo overload")
    override fun onDeviceAppeared(address: String) = appeared(address)

    @Deprecated("Replaced by onDevicePresenceEvent on Android 16")
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)   // Android calls this overload from 13 on
    override fun onDeviceAppeared(associationInfo: AssociationInfo) =
        appeared(associationInfo.deviceMacAddress?.toString())

    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && event.event == DevicePresenceEvent.EVENT_BLE_APPEARED) {
            appeared(null)
        }
    }

    private fun appeared(address: String?) {
        val saved = MonitoringDataSource.savedWearable(this) ?: return
        if (address != null && !address.equals(saved.address, ignoreCase = true)) return
        if (!WearablePreferences.autoSync(this).value) return
        if (MonitoringDataSource.isSimulationEnabled(this) || HealthMonitoringService.isRunning.value) return
        // The scenario only applies to the simulator; the wearable streams what it measures.
        val scenario = WearablePreferences.wantedScenario(this) ?: VitalsScenario.HEALTHY_BASELINE
        when (val result = HealthMonitoringService.start(this, scenario)) {
            HealthMonitoringService.StartResult.Started -> Log.i(TAG, "Wearable nearby: monitoring started")
            is HealthMonitoringService.StartResult.NotStarted -> Log.w(TAG, "Wearable nearby, but: ${result.reason}")
        }
    }

    private companion object {
        const val TAG = "WearablePresence"
    }
}
