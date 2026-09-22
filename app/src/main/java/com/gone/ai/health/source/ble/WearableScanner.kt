package com.gone.ai.health.source.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A device seen during a scan. */
data class NearbyWearable(
    val address: String,
    val name: String?,
    val rssi: Int,
    /** It advertises the G-one serial service, so it is very likely the wearable. */
    val advertisesSerialService: Boolean
)

/** Why a scan cannot start, in words the user can act on. */
enum class ScanBlocker(val message: String) {
    NO_BLUETOOTH("This phone has no Bluetooth."),
    PERMISSION("Allow Nearby devices so G-one can look for the wearable."),
    LOCATION_PERMISSION("Android 11 and older need location access to find Bluetooth devices. G-one does not use your location."),
    LOCATION_OFF("Android 11 and older only find Bluetooth devices while Location is switched on."),
    BLUETOOTH_OFF("Bluetooth is off.")
}

/**
 * Finds nearby BLE devices, putting the ones that advertise the G-one serial service first.
 *
 * The scan is NOT filtered to that service. An HM-10 or an ESP32 whose firmware does not
 * list its service in the advertisement would then be invisible, and the user would have no
 * way to pick it. Instead every named device is listed and the likely wearables are marked.
 *
 * PERMISSIONS differ by Android version, which is why [blocker] exists:
 *  - Android 12+: BLUETOOTH_SCAN, declared `neverForLocation`, and BLUETOOTH_CONNECT.
 *  - Android 11 and older: ACCESS_FINE_LOCATION, and Location switched on. Without both the
 *    system returns no scan results at all, silently — so it is checked up front.
 */
class WearableScanner(context: Context) {

    private val context = context.applicationContext

    /** Permissions to request before scanning on this Android version. */
    fun permissionsNeeded(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** The first thing stopping a scan, or null when one can start. */
    fun blocker(): ScanBlocker? {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return ScanBlocker.NO_BLUETOOTH
        val missing = permissionsNeeded().any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ScanBlocker.PERMISSION
            else ScanBlocker.LOCATION_PERMISSION
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val location = context.getSystemService(LocationManager::class.java)
            if (location == null || !LocationManagerCompat.isLocationEnabled(location)) return ScanBlocker.LOCATION_OFF
        }
        if (!adapter.isEnabled) return ScanBlocker.BLUETOOTH_OFF
        return null
    }

    /**
     * Devices as they are seen, until the collector stops. Check [blocker] first; a scan
     * that fails to start closes the flow with a [WearableLinkException] carrying the reason.
     */
    @SuppressLint("MissingPermission") // blocker() verified the scan permissions before this is collected.
    fun scan(): Flow<NearbyWearable> = callbackFlow {
        blocker()?.let { throw WearableLinkException(it.message, permanent = true) }
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
            ?: throw WearableLinkException(ScanBlocker.BLUETOOTH_OFF.message)

        val serviceUuid = ParcelUuid(WearableUuids.SERVICE)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                val name = record?.deviceName ?: try {
                    result.device.name
                } catch (_: SecurityException) {
                    null
                }
                val serial = record?.serviceUuids?.contains(serviceUuid) == true
                if (name == null && !serial) return
                trySend(NearbyWearable(result.device.address, name, result.rssi, serial))
            }

            override fun onScanFailed(errorCode: Int) {
                close(WearableLinkException("The Bluetooth scan could not start (error $errorCode). Try again."))
            }
        }

        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(null, settings, callback)
        } catch (e: SecurityException) {
            throw WearableLinkException(ScanBlocker.PERMISSION.message, permanent = true, cause = e)
        }
        awaitClose {
            try {
                scanner.stopScan(callback)
            } catch (_: SecurityException) {
                // Permission withdrawn mid-scan; the system has already stopped it.
            } catch (_: IllegalStateException) {
                // Bluetooth switched off mid-scan; nothing left to stop.
            }
        }
    }
}
