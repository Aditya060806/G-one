package com.gone.ai.health.service

import android.annotation.SuppressLint
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * "Sync automatically when nearby": the phone starts monitoring by itself when the saved
 * wearable comes into range, using Android's Companion Device Manager (Android 12 and later).
 *
 * Opt-in, in three steps the person sees:
 *  1. [requestAssociation] shows Android's own "Allow G-one to manage G-one Wearable?" dialog.
 *  2. On approval, [startObserving] asks Android to watch for the device.
 *  3. When it appears, [WearablePresenceService] starts monitoring.
 *
 * Android does the watching, so the app does not have to stay running or scan on its own.
 */
object WearableAutoSync {

    private const val TAG = "WearableAutoSync"

    /** False on phones before Android 12 or without companion device support. */
    fun isSupported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    /**
     * Ask Android to associate the wearable at [address]. [onApprovalNeeded] receives the system
     * dialog to launch; the result of that dialog confirms the association.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    fun requestAssociation(
        context: Context,
        address: String,
        onApprovalNeeded: (IntentSender) -> Unit,
        onFailure: (String) -> Unit
    ) {
        val manager = context.getSystemService(CompanionDeviceManager::class.java)
            ?: return onFailure("This phone cannot link companion devices.")
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(ScanFilter.Builder().setDeviceAddress(address).build())
            .build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(true)
            .build()

        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) = onApprovalNeeded(intentSender)

            @Deprecated("Called instead of onAssociationPending before Android 13")
            override fun onDeviceFound(intentSender: IntentSender) = onApprovalNeeded(intentSender)

            override fun onFailure(error: CharSequence?) {
                onFailure(error?.toString()?.takeIf { it.isNotBlank() } ?: "The wearable was not found nearby.")
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.associate(request, context.mainExecutor, callback)
            } else {
                manager.associate(request, callback, Handler(Looper.getMainLooper()))
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Association request failed", e)
            onFailure("Android refused the request: ${e.message ?: "unknown reason"}")
        }
    }

    /** Watch for the wearable. Call after association, and again after the app is updated. */
    @SuppressLint("MissingPermission")   // REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE is a normal permission in the manifest
    @RequiresApi(Build.VERSION_CODES.S)
    fun startObserving(context: Context, address: String): Boolean {
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                val id = associationId(context, address) ?: return false
                manager.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(id).build())
            } else {
                @Suppress("DEPRECATION")
                manager.startObservingDevicePresence(address)
            }
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not observe the wearable", e)
            false
        }
    }

    /** Stop watching and remove the association, e.g. when auto-sync is switched off or the wearable forgotten. */
    @SuppressLint("MissingPermission")
    fun disable(context: Context, address: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || address == null) return
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                associationId(context, address)?.let {
                    manager.stopObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(it).build())
                }
            } else {
                @Suppress("DEPRECATION")
                manager.stopObservingDevicePresence(address)
            }
        }
        runCatching {
            val id = associationId(context, address)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && id != null) {
                manager.disassociate(id)
            } else {
                @Suppress("DEPRECATION")
                manager.disassociate(address)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun associationId(context: Context, address: String): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return null
        return manager.myAssociations.firstOrNull { it.matches(address) }?.id
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun AssociationInfo.matches(address: String): Boolean =
        deviceMacAddress?.toString()?.equals(address, ignoreCase = true) == true
}
