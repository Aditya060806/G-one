package com.infinity.ai.health.source

import com.infinity.ai.health.data.ReadingSource
import com.infinity.ai.health.domain.VitalsSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Where vitals come from.
 *
 * THIS INTERFACE IS THE HARDWARE BOUNDARY, AND IT IS THE WHOLE POINT.
 *
 * Phase 1 ships [SimulatedVitalsSource] and no hardware at all. Phase 2 adds a BLE
 * GATT implementation for the HM-10. Nothing downstream — persistence, the anomaly
 * engine, the explanation templates, the notification path — may know or care which
 * one is running. If adding real hardware requires touching the detection engine,
 * this abstraction has failed.
 *
 * Note the transport is BLE (GATT) and not Bluetooth Classic SPP: HM-10 is a BLE
 * module, so the eventual implementation is `BluetoothGatt` + service/characteristic
 * FFE0/FFE1, not `BluetoothSocket` + the SPP UUID. That difference is confined
 * entirely to the implementation behind this interface.
 */
interface VitalsSource {

    /** Stable identity for persistence and UI display. */
    val descriptor: SourceDescriptor

    /** Observable connection/stream health, for the Device screen. */
    val status: StateFlow<SourceStatus>

    /**
     * Cold flow of samples.
     *
     * Cold on purpose: collecting starts the stream and cancelling the collector
     * stops it, so the lifecycle is structured concurrency rather than a pair of
     * start()/stop() calls a caller can forget to balance.
     */
    fun stream(): Flow<VitalsSample>
}

/** Identity and provenance of a [VitalsSource]. */
data class SourceDescriptor(
    val id: String,
    val displayName: String,
    val transport: ReadingSource
)

/**
 * Stream health.
 *
 * [Recovering] is distinct from [Failed] because BLE links drop routinely — the
 * phone's screen turning off is enough. A transient drop must read as "reconnecting"
 * in the UI, not as an error, or users will think the app is broken every time they
 * pocket their phone.
 */
sealed class SourceStatus {
    object Idle : SourceStatus()
    object Starting : SourceStatus()
    object Streaming : SourceStatus()
    data class Recovering(val attempt: Int, val reason: String) : SourceStatus()
    object Stopped : SourceStatus()
    data class Failed(val reason: String) : SourceStatus()

    val isLive: Boolean get() = this is Streaming
}
