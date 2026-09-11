package com.infinity.ai.health.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AsciiKeyValuePacketParserTest {

    private val now = 1_700_000_000_000L

    private fun bytes(s: String) = s.toByteArray(Charsets.US_ASCII)

    @Test
    fun `parses a complete line`() {
        val p = AsciiKeyValuePacketParser()
        val out = p.feed(bytes("HR:78,SPO2:97,TEMP:36.8,MOT:1.02,ATEMP:41.2,HUM:38,AQI:210\n"), now)

        assertEquals(1, out.size)
        val s = out.first()
        assertEquals(78, s.heartRate)
        assertEquals(97, s.spo2)
        assertEquals(36.8f, s.bodyTempC!!, 0.001f)
        assertEquals(1.02f, s.motionMagnitudeG!!, 0.001f)
        assertEquals(41.2f, s.ambientTempC!!, 0.001f)
        assertEquals(38f, s.ambientHumidityPct!!, 0.001f)
        assertEquals(210, s.aqi)
        assertEquals(now, s.timestamp)
        assertEquals(1L, p.stats.linesAccepted)
    }

    /**
     * THE reason the interface is `feed` and not `parse`. With the BLE default MTU of
     * 23 bytes (20 usable), a reading this long always spans several notifications. A
     * stateless parser would silently drop every one of them.
     */
    @Test
    fun `reassembles a line split across several feeds`() {
        val p = AsciiKeyValuePacketParser()
        assertTrue(p.feed(bytes("HR:78,SP"), now).isEmpty())
        assertTrue(p.feed(bytes("O2:96,TE"), now).isEmpty())
        assertTrue(p.feed(bytes("MP:36.9"), now).isEmpty())

        val out = p.feed(bytes("\n"), now)
        assertEquals(1, out.size)
        assertEquals(78, out.first().heartRate)
        assertEquals(96, out.first().spo2)
        assertEquals(36.9f, out.first().bodyTempC!!, 0.001f)
    }

    /** And the inverse: one notification may carry several complete readings. */
    @Test
    fun `returns every complete line in a single feed`() {
        val p = AsciiKeyValuePacketParser()
        val out = p.feed(bytes("HR:70,SPO2:98\nHR:72,SPO2:97\nHR:74,SPO2:96\n"), now)
        assertEquals(3, out.size)
        assertEquals(listOf(70, 72, 74), out.map { it.heartRate })
    }

    @Test
    fun `handles carriage return line endings`() {
        val p = AsciiKeyValuePacketParser()
        val out = p.feed(bytes("HR:80,SPO2:95\r\n"), now)
        assertEquals(1, out.size)
        assertEquals(80, out.first().heartRate)
        assertEquals(95, out.first().spo2)
    }

    @Test
    fun `keeps a trailing partial line buffered for the next feed`() {
        val p = AsciiKeyValuePacketParser()
        val first = p.feed(bytes("HR:70,SPO2:98\nHR:72,SP"), now)
        assertEquals(1, first.size)
        assertTrue(p.stats.bytesBuffered > 0)

        val second = p.feed(bytes("O2:97\n"), now)
        assertEquals(1, second.size)
        assertEquals(97, second.first().spo2)
    }

    /**
     * Firmware evolves. An unknown key must be ignorable rather than shifting the
     * meaning of every other field, which is exactly what a positional CSV would do.
     */
    @Test
    fun `ignores unknown keys`() {
        val p = AsciiKeyValuePacketParser()
        val out = p.feed(bytes("HR:75,BATT:88,FIRMWARE:2,SPO2:96\n"), now)
        assertEquals(1, out.size)
        assertEquals(75, out.first().heartRate)
        assertEquals(96, out.first().spo2)
    }

    /**
     * Per-field validation, not per-line. One bad optical SpO2 read must not discard a
     * heart rate measured in the same instant.
     */
    @Test
    fun `drops only the out-of-range field and keeps the rest`() {
        val p = AsciiKeyValuePacketParser()
        val out = p.feed(bytes("HR:75,SPO2:600,TEMP:36.7\n"), now)
        assertEquals(1, out.size)
        assertEquals(75, out.first().heartRate)
        assertNull("implausible SpO2 must be dropped", out.first().spo2)
        assertEquals(36.7f, out.first().bodyTempC!!, 0.001f)
    }

    @Test
    fun `rejects physiologically impossible values`() {
        val p = AsciiKeyValuePacketParser()
        val out = p.feed(bytes("HR:5,SPO2:20,TEMP:80,MOT:99\n"), now)
        // Every field is out of range, so the line yields nothing usable.
        assertTrue(out.isEmpty() || out.first().let {
            it.heartRate == null && it.spo2 == null && it.bodyTempC == null
        })
    }

    @Test
    fun `tolerates malformed fields without throwing`() {
        val p = AsciiKeyValuePacketParser()
        val out = p.feed(bytes("HR:abc,SPO2:,:::,=,TEMP:36.5,,,\n"), now)
        assertEquals(1, out.size)
        assertNull(out.first().heartRate)
        assertEquals(36.5f, out.first().bodyTempC!!, 0.001f)
    }

    @Test
    fun `accepts lowercase keys and surrounding whitespace`() {
        val p = AsciiKeyValuePacketParser()
        val out = p.feed(bytes("  hr : 82 , spo2 : 95 \n"), now)
        assertEquals(1, out.size)
        assertEquals(82, out.first().heartRate)
        assertEquals(95, out.first().spo2)
    }

    /**
     * Timestamp handling for SD-card backlog replay. The wearable buffers while out of
     * range and dumps the backlog on reconnect. Stamping those with arrival time would
     * compress hours into seconds and make every duration-based rule meaningless.
     */
    @Test
    fun `honours an explicit timestamp from the device`() {
        val p = AsciiKeyValuePacketParser()
        val recordedAt = now - 3_600_000     // an hour before it arrived
        val out = p.feed(bytes("HR:70,SPO2:97,TS:$recordedAt\n"), now)
        assertEquals(recordedAt, out.first().timestamp)
    }

    @Test
    fun `falls back to arrival time when no timestamp is sent`() {
        val p = AsciiKeyValuePacketParser()
        assertEquals(now, p.feed(bytes("HR:70,SPO2:97\n"), now).first().timestamp)
    }

    @Test
    fun `ignores a nonsensical timestamp`() {
        val p = AsciiKeyValuePacketParser()
        val out = p.feed(bytes("HR:70,SPO2:97,TS:-5\n"), now)
        assertEquals(now, out.first().timestamp)
    }

    /** An environment-only line would pad detection windows with no physiology. */
    @Test
    fun `rejects lines carrying no vitals and no motion`() {
        val p = AsciiKeyValuePacketParser()
        assertTrue(p.feed(bytes("ATEMP:40,HUM:60,AQI:200\n"), now).isEmpty())
        assertEquals(1L, p.stats.linesRejected)
    }

    @Test
    fun `accepts a motion-only line since falls matter`() {
        val p = AsciiKeyValuePacketParser()
        val out = p.feed(bytes("MOT:3.4\n"), now)
        assertEquals(1, out.size)
        assertEquals(3.4f, out.first().motionMagnitudeG!!, 0.001f)
    }

    @Test
    fun `skips blank lines silently`() {
        val p = AsciiKeyValuePacketParser()
        val out = p.feed(bytes("\n\n\nHR:70,SPO2:97\n\n"), now)
        assertEquals(1, out.size)
        assertEquals(0L, p.stats.linesRejected)
    }

    @Test
    fun `empty feed is a no-op`() {
        val p = AsciiKeyValuePacketParser()
        assertTrue(p.feed(ByteArray(0), now).isEmpty())
    }

    /** A missing delimiter must not turn the buffer into an unbounded memory leak. */
    @Test
    fun `bounds the buffer when no delimiter ever arrives`() {
        val p = AsciiKeyValuePacketParser(maxBufferedBytes = 256)
        repeat(50) { p.feed(bytes("HR:70,SPO2:97,TEMP:36.6,"), now) }
        assertTrue("buffer grew to ${p.stats.bytesBuffered}", p.stats.bytesBuffered <= 256)
    }

    /** Reset prevents a half-line from merging with the first line of a new session. */
    @Test
    fun `reset discards buffered partial input`() {
        val p = AsciiKeyValuePacketParser()
        p.feed(bytes("HR:99,SPO"), now)
        assertTrue(p.stats.bytesBuffered > 0)

        p.reset()
        assertEquals(0, p.stats.bytesBuffered)

        val out = p.feed(bytes("HR:70,SPO2:97\n"), now)
        assertEquals(1, out.size)
        assertEquals(70, out.first().heartRate)   // not 99, and not corrupt
    }

    @Test
    fun `counts accepted and rejected lines`() {
        val p = AsciiKeyValuePacketParser()
        p.feed(bytes("HR:70,SPO2:97\nATEMP:40\nHR:72,SPO2:96\n"), now)
        assertEquals(2L, p.stats.linesAccepted)
        assertEquals(1L, p.stats.linesRejected)
    }
}
