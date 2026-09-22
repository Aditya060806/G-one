package com.gone.ai.health.source.ble

import com.gone.ai.health.source.SourceStatus
import com.gone.ai.health.source.WearablePacket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A link the test drives by hand. */
private class FakeConnection : WearableConnection {
    val channel = Channel<LinkEvent>(Channel.UNLIMITED)
    val writes = mutableListOf<String>()
    var writeSucceeds = true
    var closed = false

    override val incoming: ReceiveChannel<LinkEvent> get() = channel

    override suspend fun write(bytes: ByteArray): Boolean {
        writes += String(bytes, Charsets.US_ASCII)
        return writeSucceeds
    }

    override fun close() {
        closed = true
        channel.close()
    }

    fun send(text: String) {
        channel.trySend(LinkEvent.Data(text.toByteArray(Charsets.US_ASCII)))
    }

    fun drop(reason: String = "out of range") {
        channel.trySend(LinkEvent.Closed(reason))
    }
}

/** Hands out scripted outcomes, one per connect attempt. */
private class ScriptedConnector(vararg outcomes: Any) : WearableConnector {
    private val script = ArrayDeque(outcomes.toList())
    val attempts = mutableListOf<Long>()
    var clock: () -> Long = { 0L }

    override suspend fun connect(): WearableConnection {
        attempts += clock()
        return when (val next = script.removeFirstOrNull() ?: WearableLinkException("no more scripted links")) {
            is WearableConnection -> next
            is WearableLinkException -> throw next
            else -> error("bad script entry $next")
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BleUartVitalsSourceTest {

    private val now = 1_700_000_000_000L

    private fun TestScope.source(connector: ScriptedConnector) =
        BleUartVitalsSource(
            connector = connector,
            address = "AA:BB:CC:DD:EE:FF",
            displayName = "G-one wearable",
            clock = { now + currentTime }
        ).also { connector.clock = { currentTime } }

    private fun TestScope.collectInto(src: BleUartVitalsSource, out: MutableList<WearablePacket>) =
        backgroundScope.launch { src.packets().collect { out += it } }

    @Test
    fun `streams parsed lines and syncs the wearable clock on connect`() = runTest {
        val link = FakeConnection()
        val src = source(ScriptedConnector(link))
        val out = mutableListOf<WearablePacket>()
        collectInto(src, out)
        runCurrent()

        assertEquals(listOf("T:$now\n"), link.writes)
        assertTrue(src.status.value is SourceStatus.Streaming)

        link.send("HR:72,SPO2:97,ST:7F\nEMG:1200\n")
        runCurrent()

        assertEquals(2, out.size)
        assertEquals(72, out[0].sample!!.heartRate)
        assertEquals(1200, out[1].sample!!.emgMean)
        assertEquals(0x7F, src.deviceStatus.value!!.bits)
        assertEquals(2L, src.parserStats.value.linesAccepted)
        assertFalse(src.clockSyncFailed.value)
    }

    @Test
    fun `reports a clock sync that could not be sent`() = runTest {
        val link = FakeConnection().apply { writeSucceeds = false }
        val src = source(ScriptedConnector(link))
        collectInto(src, mutableListOf())
        runCurrent()
        assertTrue(src.clockSyncFailed.value)
    }

    @Test
    fun `a dropped link recovers and reconnects after backing off`() = runTest {
        val first = FakeConnection()
        val second = FakeConnection()
        val connector = ScriptedConnector(first, second)
        val src = source(connector)
        val out = mutableListOf<WearablePacket>()
        collectInto(src, out)
        runCurrent()

        first.drop("The wearable disconnected (link timed out, likely out of range)")
        runCurrent()
        val status = src.status.value
        assertTrue("was $status", status is SourceStatus.Recovering)
        assertEquals(1, (status as SourceStatus.Recovering).attempt)
        assertTrue(status.reason.contains("out of range"))
        assertTrue("the old link is closed", first.closed)

        advanceTimeBy(BleUartVitalsSource.DEFAULT_INITIAL_BACKOFF_MILLIS + 1)
        runCurrent()
        assertEquals(2, connector.attempts.size)
        assertTrue(src.status.value is SourceStatus.Streaming)

        second.send("HR:80\n")
        runCurrent()
        assertEquals(80, out.single().sample!!.heartRate)
    }

    /** REFERENCE's timeout wrapped a blocking read and never fired. This one must. */
    @Test
    fun `a silent link is closed and reopened`() = runTest {
        val silent = FakeConnection()
        val connector = ScriptedConnector(silent, FakeConnection())
        val src = source(connector)
        collectInto(src, mutableListOf())
        runCurrent()

        advanceTimeBy(BleUartVitalsSource.DEFAULT_STALE_AFTER_MILLIS + 1)
        runCurrent()

        assertTrue(silent.closed)
        val status = src.status.value
        assertTrue("was $status", status is SourceStatus.Recovering)
        assertTrue((status as SourceStatus.Recovering).reason.contains("No data"))
    }

    /** A half-line from the dropped link must not merge with the first line of the next. */
    @Test
    fun `the parser is reset on reconnect`() = runTest {
        val first = FakeConnection()
        val second = FakeConnection()
        val src = source(ScriptedConnector(first, second))
        val out = mutableListOf<WearablePacket>()
        collectInto(src, out)
        runCurrent()

        first.send("HR:99,SP")
        first.drop()
        runCurrent()
        advanceTimeBy(BleUartVitalsSource.DEFAULT_INITIAL_BACKOFF_MILLIS + 1)
        runCurrent()

        second.send("O2:97\nHR:70\n")
        runCurrent()

        // "O2:97" alone has no known key and is rejected; the corrupt "HR:99,SPO2:97" never forms.
        assertEquals(listOf(70), out.map { it.sample!!.heartRate })
    }

    @Test
    fun `failed connection attempts back off exponentially up to the cap`() = runTest {
        val connector = ScriptedConnector(
            *Array<Any>(8) { WearableLinkException("The wearable did not answer") }
        )
        val src = source(connector)
        collectInto(src, mutableListOf())

        advanceTimeBy(200_000)
        runCurrent()

        val gaps = connector.attempts.zipWithNext { a, b -> b - a }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), gaps.take(7))
        assertEquals("Recovering", src.status.value::class.simpleName)
    }

    @Test
    fun `bluetooth being off is retried, not treated as final`() = runTest {
        val link = FakeConnection()
        val connector = ScriptedConnector(WearableLinkException("Bluetooth is off"), link)
        val src = source(connector)
        collectInto(src, mutableListOf())
        runCurrent()

        val status = src.status.value
        assertTrue("was $status", status is SourceStatus.Recovering)
        assertEquals("Bluetooth is off", (status as SourceStatus.Recovering).reason)

        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(src.status.value is SourceStatus.Streaming)
    }

    /** Retrying cannot grant a permission; the wearer has to be told. */
    @Test
    fun `a permanent failure stops the stream with the reason`() = runTest {
        val connector = ScriptedConnector(
            WearableLinkException("The Nearby devices permission is off", permanent = true)
        )
        val src = source(connector)
        var error: Throwable? = null
        backgroundScope.launch {
            try {
                src.packets().collect { }
            } catch (e: WearableLinkException) {
                error = e
            }
        }
        runCurrent()
        advanceTimeBy(60_000)

        assertEquals(1, connector.attempts.size)
        assertEquals(SourceStatus.Failed("The Nearby devices permission is off"), src.status.value)
        assertTrue(error is WearableLinkException)
    }

    @Test
    fun `cancelling the collector closes the link and reports stopped`() = runTest {
        val link = FakeConnection()
        val src = source(ScriptedConnector(link))
        val job = collectInto(src, mutableListOf())
        runCurrent()

        job.cancel()
        advanceTimeBy(BleUartVitalsSource.STOP_FLUSH_MILLIS + 1)   // the STOP is given time to leave first
        runCurrent()

        assertTrue(link.closed)
        assertEquals(SourceStatus.Stopped, src.status.value)
    }

    @Test
    fun `backlog lines keep their flag through the source`() = runTest {
        val link = FakeConnection()
        val src = source(ScriptedConnector(link))
        val out = mutableListOf<WearablePacket>()
        collectInto(src, out)
        runCurrent()

        link.send("HR:66,BUF:1,TS:${now - 3_600_000}\nHR:70\n")
        runCurrent()

        assertTrue(out[0].backlog)
        assertEquals(now - 3_600_000, out[0].sample!!.timestamp)
        assertFalse(out[1].backlog)
        assertNull(src.deviceStatus.value)
    }

    private fun stored(seq: Int) = "HR:70,TS:${now - 600_000 + seq * 500L},BUF:1,SEQ:$seq\n"

    @Test
    fun `stored records are acknowledged only once their readings are stored`() = runTest {
        val link = FakeConnection()
        val src = source(ScriptedConnector(link))
        backgroundScope.launch {
            src.packets().collect { packet -> packet.seq?.let { src.holding(it) } }
        }
        runCurrent()

        link.send((1..12).joinToString("") { stored(it) })
        runCurrent()
        assertEquals("readings still waiting are not acknowledged", listOf("T:$now\n"), link.writes)

        src.stored()
        link.send("ST:7F,PEND:12,LAST:12\n")
        runCurrent()
        assertEquals("ACK:12\n", link.writes.last())
        assertEquals(12L, src.sync.value.lastAcknowledged)
    }

    @Test
    fun `acknowledgements are batched while records flow`() = runTest {
        val link = FakeConnection()
        val src = source(ScriptedConnector(link))
        collectInto(src, mutableListOf())
        runCurrent()

        link.send((1..25).joinToString("") { stored(it) })
        runCurrent()
        assertEquals(listOf("T:$now\n", "ACK:10\n", "ACK:20\n"), link.writes)
    }

    @Test
    fun `a record resent on the same link is passed on once`() = runTest {
        val link = FakeConnection()
        val src = source(ScriptedConnector(link))
        val out = mutableListOf<WearablePacket>()
        collectInto(src, out)
        runCurrent()

        link.send(stored(1) + stored(2) + stored(3) + stored(2))
        runCurrent()
        assertEquals(listOf(1L, 2L, 3L), out.map { it.seq })
        assertEquals(3L, src.sync.value.receivedRecords)
    }

    @Test
    fun `a resend from the acknowledged point is passed on again`() = runTest {
        val link = FakeConnection()
        val src = source(ScriptedConnector(link))
        val out = mutableListOf<WearablePacket>()
        collectInto(src, out)
        runCurrent()

        link.send(stored(1) + stored(2) + stored(3) + stored(1))
        runCurrent()
        assertEquals(listOf(1L, 2L, 3L, 1L), out.map { it.seq })
    }

    @Test
    fun `a status line marks the link caught up only when everything has arrived`() = runTest {
        val link = FakeConnection()
        val src = source(ScriptedConnector(link))
        val out = mutableListOf<WearablePacket>()
        collectInto(src, out)
        runCurrent()

        link.send(stored(1) + stored(2) + "ST:7F,PEND:3,LAST:3\n")
        runCurrent()
        assertFalse(out.last().caughtUp)
        assertEquals(3L, src.sync.value.pendingOnWearable)

        link.send(stored(3) + "ST:7F,PEND:3,LAST:3\n")
        runCurrent()
        assertTrue(out.last().caughtUp)
        assertEquals("caught up is acknowledged at once", "ACK:3\n", link.writes.last())
        assertTrue(src.sync.value.lastSyncedAt != null)
    }

    @Test
    fun `lost records are counted and acknowledged`() = runTest {
        val link = FakeConnection()
        val src = source(ScriptedConnector(link))
        collectInto(src, mutableListOf())
        runCurrent()

        link.send("SEQ:4,LOST:4,BUF:1\nST:7F,PEND:4,LAST:4\n")
        runCurrent()
        assertEquals(4L, src.sync.value.lostRecords)
        assertEquals("ACK:4\n", link.writes.last())
    }

    @Test
    fun `a failed clock sync is retried while data arrives`() = runTest {
        val link = FakeConnection().apply { writeSucceeds = false }
        val src = source(ScriptedConnector(link))
        collectInto(src, mutableListOf())
        runCurrent()
        assertTrue(src.clockSyncFailed.value)

        link.writeSucceeds = true
        advanceTimeBy(2_500)
        link.send("HR:70,ST:3F\n")
        runCurrent()
        assertEquals(2, link.writes.count { it.startsWith("T:") })
        assertFalse(src.clockSyncFailed.value)
    }

    @Test
    fun `sync now retries without waiting out the backoff`() = runTest {
        val link = FakeConnection()
        val connector = ScriptedConnector(
            WearableLinkException("Bluetooth is off"),
            WearableLinkException("Bluetooth is off"),
            link
        )
        val src = source(connector)
        collectInto(src, mutableListOf())
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(2, connector.attempts.size)

        src.syncNow()
        runCurrent()
        assertEquals(3, connector.attempts.size)
        assertTrue("retried at ${connector.attempts[2]}", connector.attempts[2] < 2_000)
        assertTrue(src.status.value is SourceStatus.Streaming)
    }

    @Test
    fun `acknowledgement command is the documented wire format`() {
        assertEquals("ACK:4711\n", String(BleUartVitalsSource.ackCommand(4711), Charsets.US_ASCII))
    }

    @Test
    fun `time sync command is the documented wire format`() {
        assertEquals("T:1700000000000\n", String(BleUartVitalsSource.timeSyncCommand(now), Charsets.US_ASCII))
    }

    @Test
    fun `stop command is the documented wire format`() {
        assertEquals("STOP\n", String(BleUartVitalsSource.stopCommand(), Charsets.US_ASCII))
    }

    @Test
    fun `stopping monitoring tells the wearable before the link is closed`() = runTest {
        val link = FakeConnection()
        val src = source(ScriptedConnector(link))
        val collector = launch { src.packets().collect { } }
        runCurrent()
        link.send("HR:72,ST:7F\n")
        runCurrent()

        collector.cancel()
        runCurrent()
        assertEquals("STOP\n", link.writes.last())
        assertFalse("closed before the stop could leave the phone", link.closed)

        advanceTimeBy(BleUartVitalsSource.STOP_FLUSH_MILLIS + 1)
        runCurrent()
        assertTrue(link.closed)
        assertTrue(src.status.value is SourceStatus.Stopped)
    }

    @Test
    fun `a stop that cannot be written does not hold up closing the link`() = runTest {
        val link = FakeConnection()
        val src = source(ScriptedConnector(link))
        val collector = launch { src.packets().collect { } }
        runCurrent()

        link.writeSucceeds = false
        collector.cancel()
        runCurrent()
        assertEquals("STOP\n", link.writes.last())
        assertTrue(link.closed)
    }

    @Test
    fun `a link lost out of range does not tell the wearable to stop`() = runTest {
        val first = FakeConnection()
        val second = FakeConnection()
        val src = source(ScriptedConnector(first, second))
        collectInto(src, mutableListOf())
        runCurrent()

        first.drop()
        runCurrent()
        assertTrue(first.closed)
        assertFalse(first.writes.any { it.startsWith("STOP") })

        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(src.status.value is SourceStatus.Streaming)
        assertFalse(second.writes.any { it.startsWith("STOP") })
    }
}
