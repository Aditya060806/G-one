package com.gone.ai.health.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fields of docs/WEARABLE_PROTOCOL.md that let the phone acknowledge stored records. */
class PacketParserSyncFieldsTest {

    private val now = 1_700_000_000_000L

    private fun parse(line: String) = AsciiKeyValuePacketParser().let { it to it.feedPackets("$line\n".toByteArray(), now) }

    @Test
    fun `a stored record carries its number`() {
        val (_, packets) = parse("HR:70,ST:7F,TS:${now - 60_000},BUF:1,SEQ:4711")
        val packet = packets.single()
        assertTrue(packet.backlog)
        assertEquals(4711L, packet.seq)
        assertEquals(70, packet.sample!!.heartRate)
        assertEquals(now - 60_000, packet.sample!!.timestamp)
    }

    @Test
    fun `a lost record arrives without a reading so it can be acknowledged`() {
        val (parser, packets) = parse("SEQ:4712,LOST:3,BUF:1")
        val packet = packets.single()
        assertNull(packet.sample)
        assertEquals(4712L, packet.seq)
        assertEquals(3, packet.lost)
        assertEquals(1L, parser.stats.linesAccepted)
    }

    @Test
    fun `a numbered record with an unusable time is passed on but counted as rejected`() {
        val (parser, packets) = parse("HR:70,TS:1000,BUF:1,SEQ:9")
        val packet = packets.single()
        assertNull(packet.sample)
        assertEquals(9L, packet.seq)
        assertEquals(0, packet.lost)
        assertEquals(1L, parser.stats.linesRejected)
        assertEquals(0L, parser.stats.linesAccepted)
    }

    @Test
    fun `an unnumbered unusable stored line is still dropped, as before`() {
        val (parser, packets) = parse("HR:70,BUF:1")
        assertTrue(packets.isEmpty())
        assertEquals(1L, parser.stats.linesRejected)
    }

    @Test
    fun `the catch-up status line reports what is waiting`() {
        val (_, packets) = parse("ST:7F,PEND:120,LAST:4830")
        val packet = packets.single()
        assertFalse(packet.backlog)
        assertNull(packet.sample)
        assertEquals(0x7F, packet.status!!.bits)
        assertEquals(120L, packet.pending)
        assertEquals(4830L, packet.newestSeq)
        assertFalse("only the link decides this", packet.caughtUp)
    }

    @Test
    fun `malformed sync fields are ignored and counted`() {
        val (parser, packets) = parse("HR:70,TS:${now - 1_000},BUF:1,SEQ:abc")
        assertNull(packets.single().seq)
        assertEquals(1L, parser.stats.fieldsRejected)
    }

    @Test
    fun `live lines are unchanged`() {
        val (_, packets) = parse("HR:72,ST:7F,TS:$now")
        val packet = packets.single()
        assertFalse(packet.backlog)
        assertNull(packet.seq)
        assertNull(packet.pending)
        assertEquals(72, packet.sample!!.heartRate)
    }
}
