package com.gone.ai.health.source.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opens a GATT link to the wearable at [address] and subscribes to its serial characteristic.
 *
 * Sequence, each step started from the previous step's callback because GATT allows one
 * operation at a time: connect → request a larger MTU → discover services → enable
 * notifications on FFE1 by writing its CCCD → ready.
 *
 * The larger MTU is a courtesy, not a requirement: a wearable line is ~90 bytes and the
 * parser reassembles lines split across 20-byte notifications. It only cuts the number of
 * radio packets per line. If the request fails the link continues at the default size.
 *
 * PERMISSIONS: every call below needs BLUETOOTH_CONNECT on Android 12+. [connect] checks it
 * first and turns a refusal into a permanent [WearableLinkException]; a SecurityException
 * later (the permission revoked mid-session) is handled the same way. That is why the
 * lint MissingPermission check is suppressed for this class.
 */
@SuppressLint("MissingPermission")
class AndroidBleConnector(
    context: Context,
    private val address: String
) : WearableConnector {

    private val context = context.applicationContext

    override suspend fun connect(): WearableConnection {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw WearableLinkException("This phone has no Bluetooth", permanent = true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw WearableLinkException(
                "The Nearby devices permission is off, so G-one cannot talk to the wearable",
                permanent = true
            )
        }
        if (!adapter.isEnabled) throw WearableLinkException("Bluetooth is off")
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            throw WearableLinkException("The saved wearable address is not valid — choose the device again", permanent = true)
        }

        val link = GattLink()
        val device = adapter.getRemoteDevice(address)
        val gatt = try {
            device.connectGatt(context, false, link.callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            throw WearableLinkException("The Nearby devices permission was withdrawn", permanent = true, cause = e)
        } ?: throw WearableLinkException("Android refused to open a Bluetooth link")

        link.gatt = gatt
        try {
            withTimeout(CONNECT_TIMEOUT_MILLIS) { link.ready.await() }
        } catch (e: TimeoutCancellationException) {
            link.close()
            throw WearableLinkException("The wearable did not answer — is it switched on and nearby?", cause = e)
        } catch (e: Throwable) {
            link.close()
            throw e
        }
        return link
    }

    private inner class GattLink : WearableConnection {

        @Volatile var gatt: BluetoothGatt? = null
        @Volatile private var serial: BluetoothGattCharacteristic? = null

        val ready = CompletableDeferred<Unit>()
        private val events = Channel<LinkEvent>(Channel.UNLIMITED)
        private val closed = AtomicBoolean(false)

        override val incoming: ReceiveChannel<LinkEvent> get() = events

        val callback = object : BluetoothGattCallback() {

            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when {
                    newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS ->
                        guarded { if (!g.requestMtu(REQUESTED_MTU)) g.discoverServices() }

                    // A connection reported with an error status is not usable. Without this the
                    // attempt sat out the whole connect timeout instead of retrying at once.
                    newState == BluetoothProfile.STATE_CONNECTED -> {
                        val reason = "The wearable connection failed (${gattStatusText(status)})"
                        if (!ready.isCompleted) ready.completeExceptionally(WearableLinkException(reason))
                        events.trySend(LinkEvent.Closed(reason))
                        events.close()
                    }

                    newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        val reason = "The wearable disconnected (${gattStatusText(status)})"
                        if (!ready.isCompleted) ready.completeExceptionally(WearableLinkException(reason))
                        events.trySend(LinkEvent.Closed(reason))
                        events.close()
                    }
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                Log.i(TAG, "MTU $mtu (status $status)")
                guarded { if (!g.discoverServices()) fail("Could not read the wearable's services") }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("Could not read the wearable's services (${gattStatusText(status)})")
                    return
                }
                val characteristic = g.getService(WearableUuids.SERVICE)?.getCharacteristic(WearableUuids.SERIAL)
                if (characteristic == null) {
                    fail(
                        "This device does not have the G-one serial service (FFE0/FFE1) — choose the wearable again",
                        permanent = true
                    )
                    return
                }
                serial = characteristic
                guarded {
                    val descriptor = characteristic.getDescriptor(WearableUuids.CCCD)
                    if (!g.setCharacteristicNotification(characteristic, true) || descriptor == null) {
                        fail("The wearable refused notifications", permanent = true)
                        return@guarded
                    }
                    val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                            BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(descriptor)
                    }
                    if (!queued) fail("Could not enable notifications on the wearable")
                }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid != WearableUuids.CCCD) return
                if (status == BluetoothGatt.GATT_SUCCESS) ready.complete(Unit)
                else fail("Could not enable notifications on the wearable (${gattStatusText(status)})")
            }

            /** Android 13+. Overriding it means the deprecated overload below is not called there. */
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (characteristic.uuid == WearableUuids.SERIAL) events.trySend(LinkEvent.Data(value.copyOf()))
            }

            @Deprecated("Called only below Android 13")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid != WearableUuids.SERIAL) return
                characteristic.value?.let { events.trySend(LinkEvent.Data(it.copyOf())) }
            }
        }

        override suspend fun write(bytes: ByteArray): Boolean {
            val g = gatt ?: return false
            val characteristic = serial ?: return false
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(
                        characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    @Suppress("DEPRECATION")
                    characteristic.value = bytes
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(characteristic)
                }
            } catch (e: SecurityException) {
                false
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            val g = gatt
            gatt = null
            try {
                g?.disconnect()
                g?.close()
            } catch (_: SecurityException) {
                // The permission is gone; the system tears the link down itself.
            }
            events.close()
        }

        private fun fail(message: String, permanent: Boolean = false) {
            val error = WearableLinkException(message, permanent)
            if (!ready.isCompleted) ready.completeExceptionally(error)
            else {
                events.trySend(LinkEvent.Closed(message))
                events.close()
            }
        }

        private inline fun guarded(block: () -> Unit) {
            try {
                block()
            } catch (e: SecurityException) {
                fail("The Nearby devices permission was withdrawn", permanent = true)
            }
        }
    }

    companion object {
        private const val TAG = "AndroidBleConnector"
        /** Covers a slow first connection, including service discovery. */
        const val CONNECT_TIMEOUT_MILLIS = 20_000L
        /** 185 is the largest MTU iOS and most Android phones agree on; ESP32 accepts it. */
        const val REQUESTED_MTU = 185

        /** Plain words for the GATT status codes a wearer is likely to meet. */
        fun gattStatusText(status: Int): String = when (status) {
            BluetoothGatt.GATT_SUCCESS -> "normal disconnect"
            0x08 -> "link timed out, likely out of range"
            0x13 -> "the wearable ended the connection"
            0x16 -> "the phone ended the connection"
            0x3E -> "could not establish a connection"
            0x85 -> "Android Bluetooth error 133, usually out of range or busy"
            else -> "GATT status $status"
        }
    }
}
