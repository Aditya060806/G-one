package com.gone.ai.health.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ── Wearable channels: skin temperature and EMG ──────────────────────────

    @Test
    fun `parses skin temperature and EMG from the wearable line`() {
        val p = AsciiKeyValuePacketParser()
        val s = p.feed(bytes("HR:72,SPO2:97,STEMP:33.9,MOT:1.02,EMG:1234,EMGPK:1810,EMGBITS:12\n"), now).single()
        assertEquals(33.9f, s.skinTempC!!, 0.001f)
        assertNull("skin temperature must never land in the core temperature field", s.bodyTempC)
        assertEquals(1234, s.emgMean)
        assertEquals(1810, s.emgMax)
    }

    /** The EMG-only firmware REFERENCE shipped would have been dropped as "no vitals". */
    @Test
    fun `accepts a line carrying only EMG`() {
        val p = AsciiKeyValuePacketParser()
        val s = p.feed(bytes("EMG:300\n"), now).single()
        assertEquals(300, s.emgMean)
        assertEquals("peak defaults to the mean when not sent", 300, s.emgMax)
    }

    @Test
    fun `drops EMG outside the 12-bit range`() {
        val p = AsciiKeyValuePacketParser()
        val out = p.feed(bytes("HR:70,EMG:4096\n"), now)
        assertNull(out.single().emgMean)
        assertEquals(1L, p.stats.fieldsRejected)
    }

    /** 1023 is full scale on a 10-bit ADC and a quarter of it on 12-bit. */
    @Test
    fun `drops EMG sent at a resolution the thresholds were not written for`() {
        val p = AsciiKeyValuePacketParser()
        val out = p.feed(bytes("HR:70,EMG:1023,EMGPK:1023,EMGBITS:10\n"), now)
        assertNull(out.single().emgMean)
        assertNull(out.single().emgMax)
        assertEquals(70, out.single().heartRate)
        assertEquals(2L, p.stats.fieldsRejected)
    }

    @Test
    fun `drops an EMG peak lower than the mean it summarises`() {
        val p = AsciiKeyValuePacketParser()
        val s = p.feed(bytes("EMG:2000,EMGPK:1500\n"), now).single()
        assertEquals(2000, s.emgMean)
        assertEquals(2000, s.emgMax)
        assertEquals(1L, p.stats.fieldsRejected)
    }

    /** A thermometer sending Fahrenheit must not be read as a fever-range Celsius value. */
    @Test
    fun `drops a skin temperature in the wrong unit`() {
        val p = AsciiKeyValuePacketParser()
        val out = p.feed(bytes("HR:70,STEMP:92.3\n"), now)
        assertNull(out.single().skinTempC)
        assertEquals(1L, p.stats.fieldsRejected)
    }

    // ── Sensor status ────────────────────────────────────────────────────────

    @Test
    fun `carries the sensor status with the sample`() {
        val p = AsciiKeyValuePacketParser()
        val packet = p.feedPackets(bytes("HR:70,ST:5D\n"), now).single()
        val st = packet.status!!
        assertTrue(st.pulseOximeterPresent)
        assertFalse(st.skinContact)
        assertTrue(st.skinThermometerPresent)
        assertTrue(st.motionSensorPresent)
        assertTrue(st.emgElectrodesOk)
        assertFalse(st.sdCardPresent)
        assertTrue(st.clockSynced)
        assertFalse(packet.backlog)
    }

    /** No sensor has a reading yet, but the wearer still needs to hear why. */
    @Test
    fun `accepts a status-only line without inventing a sample`() {
        val p = AsciiKeyValuePacketParser()
        val packets = p.feedPackets(bytes("ST:08\n"), now)
        assertEquals(1, packets.size)
        assertNull(packets.single().sample)
        assertEquals(0x08, packets.single().status!!.bits)
        assertTrue(p.feed(bytes("ST:08\n"), now).isEmpty())
        assertEquals(2L, p.stats.linesAccepted)
    }

    @Test
    fun `ignores a malformed status but keeps the reading`() {
        val p = AsciiKeyValuePacketParser()
        val packet = p.feedPackets(bytes("HR:70,ST:ZZ\n"), now).single()
        assertNull(packet.status)
        assertEquals(70, packet.sample!!.heartRate)
        assertEquals(1L, p.stats.fieldsRejected)
    }

    // ── Timestamps and SD-card backlog ───────────────────────────────────────

    @Test
    fun `marks replayed lines as backlog and keeps their own time`() {
        val p = AsciiKeyValuePacketParser()
        val recordedAt = now - 45 * 60_000L
        val packet = p.feedPackets(bytes("HR:70,ST:7F,BUF:1,TS:$recordedAt\n"), now).single()
        assertTrue(packet.backlog)
        assertEquals(recordedAt, packet.sample!!.timestamp)
        assertNull("a buffered line does not describe the device as it is now", packet.status)
    }

    /** Stamping an old reading with arrival time would corrupt every duration rule. */
    @Test
    fun `rejects a backlog line that cannot be placed in time`() {
        val p = AsciiKeyValuePacketParser()
        assertTrue(p.feedPackets(bytes("HR:70,BUF:1\n"), now).isEmpty())
        assertTrue(p.feedPackets(bytes("HR:70,BUF:1,TS:12345\n"), now).isEmpty())
        assertEquals(2L, p.stats.linesRejected)
    }

    @Test
    fun `does not trust a timestamp from the future`() {
        val p = AsciiKeyValuePacketParser()
        val ahead = now + AsciiKeyValuePacketParser.MAX_CLOCK_AHEAD_MILLIS + 1
        assertEquals(now, p.feed(bytes("HR:70,TS:$ahead\n"), now).single().timestamp)
        assertTrue(p.feedPackets(bytes("HR:70,BUF:1,TS:$ahead\n"), now).isEmpty())
    }

    /** An unset ESP32 clock counts from boot, which reads as early 1970. */
    @Test
    fun `does not trust a timestamp from an unset clock`() {
        val p = AsciiKeyValuePacketParser()
        assertEquals(now, p.feed(bytes("HR:70,TS:86400000\n"), now).single().timestamp)
        assertEquals(1L, p.stats.fieldsRejected)
    }

    @Test
    fun `accepts a timestamp within the allowed clock skew`() {
        val p = AsciiKeyValuePacketParser()
        val slightlyAhead = now + 30_000L
        assertEquals(slightlyAhead, p.feed(bytes("HR:70,TS:$slightlyAhead\n"), now).single().timestamp)
    }

    /** The default BLE MTU carries 20 bytes a notification. */
    @Test
    fun `reassembles a full wearable line delivered in 20-byte notifications`() {
        val p = AsciiKeyValuePacketParser()
        val line = "HR:72,SPO2:97,STEMP:33.9,MOT:1.02,EMG:1234,EMGPK:1810,EMGBITS:12,ST:7F,TS:${now - 1000}\n"
        val packets = line.toByteArray(Charsets.US_ASCII).toList().chunked(20)
            .flatMap { p.feedPackets(it.toByteArray(), now) }
        val packet = packets.single()
        val s = packet.sample!!
        assertEquals(72, s.heartRate)
        assertEquals(97, s.spo2)
        assertEquals(33.9f, s.skinTempC!!, 0.001f)
        assertEquals(1.02f, s.motionMagnitudeG!!, 0.001f)
        assertEquals(1234, s.emgMean)
        assertEquals(1810, s.emgMax)
        assertEquals(now - 1000, s.timestamp)
        assertEquals(0x7F, packet.status!!.bits)
        assertEquals(0L, p.stats.fieldsRejected)
    }
}

class WearableStatusTest {

    @Test
    fun `parses hex bitmasks of one to four digits`() {
        assertEquals(0x7F, WearableStatus.parse("7F")!!.bits)
        assertEquals(0x7F, WearableStatus.parse("7f")!!.bits)
        assertEquals(0x1, WearableStatus.parse("1")!!.bits)
        assertEquals(0xFF7F, WearableStatus.parse("FF7F")!!.bits)
    }

    @Test
    fun `rejects malformed bitmasks`() {
        listOf("", "0x7F", "G1", "12345", "-1", "7 F").forEach {
            assertNull("'$it' should not parse", WearableStatus.parse(it))
        }
    }

    @Test
    fun `reports nothing wrong when every sensor is fine`() {
        assertTrue(WearableStatus(0x7F).problems().isEmpty())
    }

    @Test
    fun `explains a missing pulse oximeter rather than a lost contact`() {
        val problems = WearableStatus(0x7F and WearableStatus.PULSE_OXIMETER_PRESENT.inv()).problems()
        assertEquals(1, problems.size)
        assertTrue(problems.single().contains("not detected"))
    }

    @Test
    fun `explains lost skin contact and detached electrodes`() {
        val bits = 0x7F and WearableStatus.SKIN_CONTACT.inv() and WearableStatus.EMG_ELECTRODES_OK.inv()
        val problems = WearableStatus(bits).problems()
        assertEquals(2, problems.size)
        assertTrue(problems[0].contains("not touching skin"))
        assertTrue(problems[1].contains("electrodes"))
    }
}
