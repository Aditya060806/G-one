package com.gone.ai.health.source.ble

import com.gone.ai.health.data.ReadingSource
import com.gone.ai.health.domain.VitalsSample
import com.gone.ai.health.source.AsciiKeyValuePacketParser
import com.gone.ai.health.source.BacklogAckTracker
import com.gone.ai.health.source.ParserStats
import com.gone.ai.health.source.SensorPacketParser
import com.gone.ai.health.source.SourceDescriptor
import com.gone.ai.health.source.SourceStatus
import com.gone.ai.health.source.VitalsSource
import com.gone.ai.health.source.WearableStatus
import com.gone.ai.health.source.WearablePacket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Vitals from the wearable over its BLE serial profile (service FFE0, characteristic FFE1).
 *
 * WHAT THIS CLASS OWNS
 *
 *  - RECONNECTING. BLE links drop routinely: the phone in a pocket, the wearer walking
 *    out of range. A drop is reported as [SourceStatus.Recovering] and retried with a
 *    capped exponential backoff, so a wearable that is off for an hour costs a handful of
 *    attempts, not thousands. [syncNow] cuts a wait short, e.g. when Bluetooth comes back on.
 *  - NOTICING A SILENT LINK. A link can stay nominally connected while no data arrives.
 *    Waiting for data is a suspending receive with a timeout, so after [staleAfterMillis]
 *    of silence the link is closed and reopened. REFERENCE wrapped a BLOCKING `readLine()`
 *    in `withTimeoutOrNull`, which cannot interrupt a blocked thread, so its 10-second
 *    stale check never fired.
 *  - SETTING THE WEARABLE'S CLOCK. On every connect the phone writes `T:<epoch ms>\n`, and
 *    writes it again if that failed or the wearable still reports its clock unset.
 *  - ACKNOWLEDGING STORED RECORDS. The wearable keeps readings taken out of range until the
 *    phone acknowledges them (`ACK:<n>`). A record is acknowledged only once its reading is
 *    in the database, which the collector reports with [holding] and [stored]; flow
 *    collection is sequential, so by the time an emitted packet returns, the collector has
 *    dealt with it. Resends of records already received on this link are not emitted.
 *    See [BacklogAckTracker] and docs/WEARABLE_PROTOCOL.md.
 *  - FRAMING. Bytes go to [SensorPacketParser], which is reset on every reconnect so a
 *    half-line from the old link cannot merge with the new one.
 *  - SAYING WHEN MONITORING STOPS. Cancelling the collector writes `STOP` before closing,
 *    so the wearable stops recording until the app connects again. A link lost out of
 *    range sends nothing: then the wearable should keep recording, and it does.
 *
 * Errors that retrying cannot fix — no Bluetooth hardware, the permission refused, a saved
 * device that is not a G-one wearable — end the stream with [SourceStatus.Failed] and the
 * reason, rather than retrying silently forever.
 */
class BleUartVitalsSource(
    private val connector: WearableConnector,
    address: String,
    displayName: String,
    private val parser: SensorPacketParser = AsciiKeyValuePacketParser(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val staleAfterMillis: Long = DEFAULT_STALE_AFTER_MILLIS,
    private val initialBackoffMillis: Long = DEFAULT_INITIAL_BACKOFF_MILLIS,
    private val maxBackoffMillis: Long = DEFAULT_MAX_BACKOFF_MILLIS,
    private val acks: BacklogAckTracker = BacklogAckTracker()
) : VitalsSource {

    override val descriptor = SourceDescriptor(
        id = address,
        displayName = displayName,
        transport = ReadingSource.BLE
    )

    private val _status = MutableStateFlow<SourceStatus>(SourceStatus.Idle)
    override val status: StateFlow<SourceStatus> = _status.asStateFlow()

    private val _deviceStatus = MutableStateFlow<WearableStatus?>(null)
    /** The wearable's latest report on its own sensors; null until one arrives. */
    val deviceStatus: StateFlow<WearableStatus?> = _deviceStatus.asStateFlow()

    private val _lastDataAt = MutableStateFlow<Long?>(null)
    /** When bytes last arrived from the wearable. */
    val lastDataAt: StateFlow<Long?> = _lastDataAt.asStateFlow()

    private val _parserStats = MutableStateFlow(ParserStats())
    val parserStats: StateFlow<ParserStats> = _parserStats.asStateFlow()

    private val _clockSyncFailed = MutableStateFlow(false)
    /** True when the last time-sync write could not be sent. It is retried. */
    val clockSyncFailed: StateFlow<Boolean> = _clockSyncFailed.asStateFlow()

    private val _sync = MutableStateFlow(SyncProgress())
    /** How far the phone has caught up with the records stored on the wearable. */
    val sync: StateFlow<SyncProgress> = _sync.asStateFlow()

    /** Cuts a reconnect wait short. Conflated: several requests during one wait are one retry. */
    private val retrySignal = Channel<Unit>(Channel.CONFLATED)

    /** Try to connect now instead of waiting out the backoff. No effect while streaming. */
    fun syncNow() {
        retrySignal.trySend(Unit)
    }

    /** The reading of stored record [seq] is waiting to be combined and stored. */
    fun holding(seq: Long) = acks.hold(seq)

    /** Every reading received so far is in the database. */
    fun stored() = acks.releaseAll()

    override fun stream(): Flow<VitalsSample> = packets().mapNotNull { it.sample }

    /**
     * Every complete line, with the device status, backlog flag and record number the plain
     * sample stream drops. Cold: collecting opens the link, cancelling the collector closes it.
     */
    fun packets(): Flow<WearablePacket> = flow {
        _status.value = SourceStatus.Starting
        var failures = 0

        while (true) {
            val connection = try {
                connector.connect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: WearableLinkException) {
                if (e.permanent) throw e
                failures++
                _status.value = SourceStatus.Recovering(failures, e.message)
                waitBeforeRetry(failures)
                continue
            }

            var reason = ""
            try {
                parser.reset()
                acks.reset()
                retrySignal.tryReceive()   // a request made before this link is already answered
                var clockSyncAt = clock()
                var clockSyncDue = !syncClock(connection)
                _status.value = SourceStatus.Streaming

                while (true) {
                    val event = try {
                        withTimeoutOrNull(staleAfterMillis) { connection.incoming.receive() }
                    } catch (_: ClosedReceiveChannelException) {
                        LinkEvent.Closed("The link closed")
                    }
                    when (event) {
                        null -> {
                            reason = "No data from the wearable for ${staleAfterMillis / 1000} seconds"
                            break
                        }
                        is LinkEvent.Closed -> {
                            reason = event.reason
                            break
                        }
                        is LinkEvent.Data -> {
                            val now = clock()
                            _lastDataAt.value = now
                            // A link that delivers data has recovered; restart the backoff.
                            failures = 0
                            val parsed = parser.feedPackets(event.bytes, now)
                            _parserStats.value = parser.stats
                            for (raw in parsed) {
                                val packet = track(raw, now) ?: continue
                                packet.status?.let { status ->
                                    _deviceStatus.value = status
                                    if (!status.clockSynced && now - clockSyncAt >= CLOCK_RESYNC_MILLIS) clockSyncDue = true
                                }
                                emit(packet)
                                acknowledge(connection, force = packet.caughtUp)
                            }
                            if (clockSyncDue && now - clockSyncAt >= CLOCK_RETRY_MILLIS) {
                                clockSyncAt = now
                                clockSyncDue = !syncClock(connection)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Monitoring was stopped, not lost. Without this the wearable cannot tell the
                // two apart: it would record the whole gap to its card and replay it on the
                // next connection as if the wearer had been out of range.
                withContext(NonCancellable) { sayStopping(connection) }
                throw e
            } finally {
                connection.close()
            }

            failures++
            _status.value = SourceStatus.Recovering(failures, reason)
            waitBeforeRetry(failures)
        }
    }.onCompletion { cause ->
        _status.value = when (cause) {
            null, is CancellationException -> SourceStatus.Stopped
            is WearableLinkException -> SourceStatus.Failed(cause.message)
            else -> SourceStatus.Failed(cause.message ?: cause::class.simpleName ?: "unknown error")
        }
    }

    /** Records sync progress for [packet]; null for a stored record already received on this link. */
    private fun track(packet: WearablePacket, now: Long): WearablePacket? {
        if (packet.backlog) {
            val seq = packet.seq
            if (seq != null && !acks.receive(seq, packet.lost)) return null
            _sync.update { it.copy(receivedRecords = it.receivedRecords + 1, lostRecords = it.lostRecords + packet.lost) }
            return packet
        }
        val newest = packet.newestSeq
        if (newest != null || packet.pending != null) {
            val caughtUp = newest != null && acks.hasEverythingThrough(newest)
            _sync.update {
                it.copy(
                    pendingOnWearable = packet.pending ?: it.pendingOnWearable,
                    lastSyncedAt = if (caughtUp) now else it.lastSyncedAt
                )
            }
            return if (caughtUp) packet.copy(caughtUp = true) else packet
        }
        if (packet.sample != null) {
            // Live readings flow only when nothing is waiting on the wearable.
            _sync.update { it.copy(pendingOnWearable = 0, lastSyncedAt = now) }
        }
        return packet
    }

    private suspend fun acknowledge(connection: WearableConnection, force: Boolean) {
        val seq = acks.due(clock(), force) ?: return
        if (connection.write(ackCommand(seq))) {
            acks.sent(seq)
            _sync.update { it.copy(lastAcknowledged = seq, ackWriteFailed = false) }
        } else {
            _sync.update { it.copy(ackWriteFailed = true) }
        }
    }

    private suspend fun syncClock(connection: WearableConnection): Boolean {
        val ok = connection.write(timeSyncCommand(clock()))
        _clockSyncFailed.value = !ok
        return ok
    }

    /**
     * Best effort. A wearable that misses it records the gap, which costs card space and a
     * replay, never a reading. The pause gives a write-without-response time to leave the
     * phone before the link is closed under it.
     */
    private suspend fun sayStopping(connection: WearableConnection) {
        withTimeoutOrNull(STOP_TIMEOUT_MILLIS) {
            if (connection.write(stopCommand())) delay(STOP_FLUSH_MILLIS)
        }
    }

    private suspend fun waitBeforeRetry(failures: Int) {
        withTimeoutOrNull(backoffMillis(failures)) { retrySignal.receive() }
    }

    /** 1 s, 2 s, 4 s … capped. */
    internal fun backoffMillis(failures: Int): Long {
        val shift = (failures - 1).coerceIn(0, 20)
        return (initialBackoffMillis shl shift).coerceAtMost(maxBackoffMillis)
    }

    /** What the Device and Live Monitor screens show about catching up. */
    data class SyncProgress(
        /** Records the wearable reported still waiting, from its latest status line. */
        val pendingOnWearable: Long? = null,
        /** Stored records received this run, resends excluded. */
        val receivedRecords: Long = 0,
        /** Records the wearable could not deliver (unreadable, or never placed in time). */
        val lostRecords: Long = 0,
        val lastAcknowledged: Long? = null,
        /** When the phone last had everything the wearable had stored. */
        val lastSyncedAt: Long? = null,
        /** The last acknowledgement could not be written; it is sent again with the next one. */
        val ackWriteFailed: Boolean = false
    )

    companion object {
        /** The wearable sends twice a second; ten silent seconds is a dead link. */
        const val DEFAULT_STALE_AFTER_MILLIS = 10_000L
        const val DEFAULT_INITIAL_BACKOFF_MILLIS = 1_000L
        const val DEFAULT_MAX_BACKOFF_MILLIS = 30_000L
        /** A failed time-sync write is retried this often while data arrives. */
        const val CLOCK_RETRY_MILLIS = 2_000L
        /** The wearable still says its clock is unset this long after a sync: send it again. */
        const val CLOCK_RESYNC_MILLIS = 10_000L

        /** `T:<epoch milliseconds>\n` — sets the wearable's clock. */
        fun timeSyncCommand(nowMillis: Long): ByteArray = "T:$nowMillis\n".toByteArray(Charsets.US_ASCII)

        /** `ACK:<n>\n` — every record up to `n` is stored on the phone. */
        fun ackCommand(seq: Long): ByteArray = "ACK:$seq\n".toByteArray(Charsets.US_ASCII)

        /** `STOP\n` — monitoring was stopped on purpose: record nothing until the app connects again. */
        fun stopCommand(): ByteArray = "STOP\n".toByteArray(Charsets.US_ASCII)

        const val STOP_FLUSH_MILLIS = 300L
        const val STOP_TIMEOUT_MILLIS = 1_000L
    }
}
