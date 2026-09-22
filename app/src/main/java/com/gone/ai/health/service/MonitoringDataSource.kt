package com.gone.ai.health.service

import android.content.Context
import androidx.core.content.edit
import com.gone.ai.data.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The wearable the user chose. The address is the BLE MAC; the name is only for display. */
data class SavedWearable(val address: String, val name: String)

/**
 * Where live vitals come from, as the user has configured it.
 *
 * Two independent settings, resolved in this order when monitoring starts:
 *  1. simulated vitals ON → the simulator, whatever else is saved;
 *  2. otherwise a saved wearable → the BLE link to it;
 *  3. otherwise nothing, and monitoring refuses to start rather than pretending to run.
 *
 * Kept in SharedPreferences rather than Room because [HealthMonitoringService.start] is
 * called on the main thread and must decide synchronously whether it can start at all.
 * Observable so Settings, the device screen, the dashboard and the monitor agree without
 * polling.
 */
object MonitoringDataSource {

    /** Key predates the setting's name; kept so existing installs keep their choice. */
    private const val KEY_SIMULATED_VITALS = "is_mock_mode"
    private const val KEY_WEARABLE_ADDRESS = "wearable_address"
    private const val KEY_WEARABLE_NAME = "wearable_name"
    /** Read by [UserProfile.connectedDeviceName]; written alongside so both agree. */
    private const val KEY_CONNECTED_DEVICE_NAME = "connected_device_name"

    @Volatile private var simulation: MutableStateFlow<Boolean>? = null
    @Volatile private var wearable: MutableStateFlow<SavedWearable?>? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(UserProfile.PREFS_NAME, Context.MODE_PRIVATE)

    private fun simulationFlow(context: Context): MutableStateFlow<Boolean> =
        simulation ?: synchronized(this) {
            simulation ?: MutableStateFlow(prefs(context).getBoolean(KEY_SIMULATED_VITALS, false))
                .also { simulation = it }
        }

    private fun wearableFlow(context: Context): MutableStateFlow<SavedWearable?> =
        wearable ?: synchronized(this) {
            wearable ?: MutableStateFlow(readWearable(context)).also { wearable = it }
        }

    private fun readWearable(context: Context): SavedWearable? {
        val p = prefs(context)
        val address = p.getString(KEY_WEARABLE_ADDRESS, null)?.takeIf { it.isNotBlank() } ?: return null
        return SavedWearable(address, p.getString(KEY_WEARABLE_NAME, null)?.takeIf { it.isNotBlank() } ?: address)
    }

    /** True when monitoring should stream from the simulator. */
    fun simulationEnabled(context: Context): StateFlow<Boolean> = simulationFlow(context).asStateFlow()

    fun isSimulationEnabled(context: Context): Boolean = simulationFlow(context).value

    fun setSimulationEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_SIMULATED_VITALS, enabled) }
        simulationFlow(context).value = enabled
    }

    /** The saved wearable, or null when none has been chosen. */
    fun wearable(context: Context): StateFlow<SavedWearable?> = wearableFlow(context).asStateFlow()

    fun savedWearable(context: Context): SavedWearable? = wearableFlow(context).value

    fun saveWearable(context: Context, device: SavedWearable) {
        prefs(context).edit {
            putString(KEY_WEARABLE_ADDRESS, device.address)
            putString(KEY_WEARABLE_NAME, device.name)
            putString(KEY_CONNECTED_DEVICE_NAME, device.name)
        }
        wearableFlow(context).value = device
    }

    fun forgetWearable(context: Context) {
        prefs(context).edit {
            remove(KEY_WEARABLE_ADDRESS)
            remove(KEY_WEARABLE_NAME)
            remove(KEY_CONNECTED_DEVICE_NAME)
        }
        wearableFlow(context).value = null
    }
}
