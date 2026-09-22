package com.gone.ai.health.service

import com.gone.ai.health.source.ParserStats
import com.gone.ai.health.source.SourceStatus
import com.gone.ai.health.source.WearableStatus
import com.gone.ai.health.source.ble.BleUartVitalsSource

/**
 * Everything the device screen and live monitor show about the wearable link.
 *
 * Published by [HealthMonitoringService] while it streams from a wearable; reset to the
 * default when monitoring stops. Nothing here is persisted.
 */
data class WearableLiveState(
    val deviceName: String? = null,
    val address: String? = null,
    val link: SourceStatus = SourceStatus.Idle,
    /** The wearable's report on its own sensors; null until one arrives. */
    val sensors: WearableStatus? = null,
    val lastDataAt: Long? = null,
    val parser: ParserStats = ParserStats(),
    /** The time-sync write failed on the current link, so buffered readings cannot be placed. */
    val clockSyncFailed: Boolean = false,
    /** Raw EMG envelope values from live lines, oldest first, for the on-screen trace. */
    val emgTrace: List<Int> = emptyList(),
    /** Backlog lines received from the SD card on this run. */
    val backlogLines: Long = 0,
    /** Catching up with records stored on the wearable. */
    val sync: BleUartVitalsSource.SyncProgress = BleUartVitalsSource.SyncProgress()
) {
    val isStreaming: Boolean get() = link is SourceStatus.Streaming

    companion object {
        /** About a minute at the wearable's two lines a second. */
        const val TRACE_LENGTH = 120
    }
}
