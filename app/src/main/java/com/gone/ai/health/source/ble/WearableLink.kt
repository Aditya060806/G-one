package com.gone.ai.health.source.ble

import kotlinx.coroutines.channels.ReceiveChannel
import java.util.UUID

/**
 * The seam between the reconnect logic and Android's Bluetooth stack.
 *
 * [BleUartVitalsSource] decides WHEN to connect, how long to wait for data and how to back
 * off; an implementation of this interface only knows HOW to open one link. Splitting them
 * is what lets the reconnect, watchdog and error paths run as plain JVM tests against a
 * fake, since the real `BluetoothGatt` cannot exist off a device.
 */
fun interface WearableConnector {
    /**
     * Open one link and enable notifications.
     *
     * @throws WearableLinkException when the link cannot be opened; its [WearableLinkException.permanent]
     *   flag says whether retrying could help.
     */
    suspend fun connect(): WearableConnection
}

/** One open link. Single use: once [incoming] reports [LinkEvent.Closed], connect again. */
interface WearableConnection {
    /** Bytes as they arrive, then a single [LinkEvent.Closed] when the link drops. */
    val incoming: ReceiveChannel<LinkEvent>

    /** @return false when the write could not be queued. */
    suspend fun write(bytes: ByteArray): Boolean

    /** Idempotent. */
    fun close()
}

sealed interface LinkEvent {
    class Data(val bytes: ByteArray) : LinkEvent
    data class Closed(val reason: String) : LinkEvent
}

/**
 * A link that could not be opened or was lost.
 *
 * [message] is shown to the wearer as-is, so it says what happened in plain words.
 * [permanent] is true when retrying cannot help without the user doing something — the
 * permission was refused, or the saved device is not a G-one wearable — so the source
 * stops and reports a failure instead of retrying forever in the background.
 */
class WearableLinkException(
    override val message: String,
    val permanent: Boolean = false,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * The wearable's serial profile.
 *
 * These are the HM-10's UUIDs. The ESP32-S3 firmware advertises the same service and
 * characteristic, so one app implementation talks to either module.
 */
object WearableUuids {
    /** Serial service. */
    val SERVICE: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    /** Notify for wearable-to-phone lines, write-without-response for phone-to-wearable commands. */
    val SERIAL: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    /** Client Characteristic Configuration descriptor, written to enable notifications. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
