package com.gone.ai.health.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BacklogAckTrackerTest {

    private fun tracker() = BacklogAckTracker(batch = 10, maxDelayMillis = 1_000)

    @Test
    fun `records received in order and stored are acknowledged in batches`() {
        val t = tracker()
        for (seq in 101L..109L) assertTrue(t.receive(seq))
        assertNull("nine records are below the batch", t.due(now = 0))
        t.receive(110)
        assertEquals(110L, t.due(now = 0))
        t.sent(110)
        assertNull(t.due(now = 0))
    }

    @Test
    fun `a small remainder is acknowledged after the delay`() {
        val t = tracker()
        t.receive(5)
        t.receive(6)
        assertNull(t.due(now = 100))
        assertNull(t.due(now = 900))
        assertEquals(6L, t.due(now = 1_100))
    }

    @Test
    fun `a reading still waiting in a bucket holds back its record`() {
        val t = tracker()
        for (seq in 1L..20L) t.receive(seq)
        t.hold(15)
        assertEquals(14L, t.acknowledgeable)
        t.releaseAll()
        assertEquals(20L, t.acknowledgeable)
    }

    @Test
    fun `a gap stops acknowledgement until the missing record is resent`() {
        val t = tracker()
        t.receive(1)
        t.receive(2)
        t.receive(4)
        t.receive(5)
        assertEquals(2L, t.acknowledgeable)

        assertTrue("the resent record fills the gap", t.receive(3))
        assertEquals(5L, t.acknowledgeable)
        assertFalse("records after the gap were already received", t.receive(4))
    }

    @Test
    fun `resends of acknowledged or received records are skipped`() {
        val t = tracker()
        for (seq in 1L..12L) t.receive(seq)
        t.sent(t.due(0)!!)
        assertFalse(t.receive(7))
        assertFalse(t.receive(12))
        assertTrue(t.receive(13))
    }

    @Test
    fun `lost records count as received so they never block the sync`() {
        val t = tracker()
        t.receive(10)
        assertTrue(t.receive(seq = 14, lost = 4))
        assertEquals(14L, t.acknowledgeable)
        assertFalse(t.receive(12))
    }

    @Test
    fun `a lost range after a gap is kept until the gap fills`() {
        val t = tracker()
        t.receive(1)
        t.receive(seq = 6, lost = 3)   // 4..6
        assertEquals(1L, t.acknowledgeable)
        t.receive(2)
        t.receive(3)
        assertEquals(6L, t.acknowledgeable)
    }

    @Test
    fun `the first record on a link is not acknowledged below it`() {
        val t = tracker()
        t.receive(500)
        assertEquals(500L, t.acknowledgeable)
        assertEquals(500L, t.due(now = 0, force = true))
    }

    @Test
    fun `everything received means the last bucket can be stored`() {
        val t = tracker()
        for (seq in 1L..8L) t.receive(seq)
        assertFalse(t.hasEverythingThrough(9))
        assertTrue(t.hasEverythingThrough(8))
    }

    @Test
    fun `on a new link nothing counts as caught up until records arrive`() {
        val t = tracker()
        for (seq in 1L..10L) t.receive(seq)
        t.sent(10)
        assertTrue(t.hasEverythingThrough(10))
        t.reset()
        assertFalse(t.hasEverythingThrough(10))
    }

    @Test
    fun `a new link starts over`() {
        val t = tracker()
        for (seq in 1L..5L) t.receive(seq)
        t.hold(3)
        t.reset()
        assertNull(t.acknowledgeable)
        assertTrue(t.receive(3))
        assertEquals(3L, t.acknowledgeable)
    }

    @Test
    fun `the wearable going back to the last acknowledgement starts the link over`() {
        val t = tracker()
        for (seq in 1L..12L) t.receive(seq)
        t.sent(t.due(0)!!)
        for (seq in 13L..20L) t.receive(seq)
        t.hold(13)   // say storing record 13's reading failed, so it is never released

        assertTrue("the resend from 13 is taken again", t.receive(13))
        assertEquals(13L, t.acknowledgeable)
        assertTrue(t.receive(14))
    }

    @Test
    fun `a failed write is retried at the next chance`() {
        val t = tracker()
        for (seq in 1L..10L) t.receive(seq)
        assertEquals(10L, t.due(0))
        // not marked sent
        assertEquals(10L, t.due(10))
    }
}
