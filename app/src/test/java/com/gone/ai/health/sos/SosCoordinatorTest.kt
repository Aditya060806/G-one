package com.gone.ai.health.sos

import kotlinx.coroutines.ExperimentalCoroutinesApi
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

private class FakeSender(var smsResult: SosResult = SosResult.SENT) : SosSender {
    val texts = mutableListOf<Pair<String, String>>()
    val calls = mutableListOf<String>()
    override suspend fun sendSms(number: String, text: String): SosResult {
        texts += number to text
        return smsResult
    }
    override fun placeCall(number: String): SosResult {
        calls += number
        return SosResult.CALL_PLACED
    }
}

/**
 * A fall waits for "I'm OK"; every other alert sends at once. Both halves are tested, and so is
 * what happens when the two meet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SosCoordinatorTest {

    private val fallText = "Very hard impact (6.2 g); a possible fall"
    private var config: SosConfig? = SosConfig(number = "+919876543210", name = "Aditya", fallCancelSeconds = 20, callAfter = true)
    private val sender = FakeSender()
    private val records = mutableListOf<SosRecord>()

    private fun TestScope.coordinator() =
        SosCoordinator(backgroundScope, config = { config }, sender = sender, clock = { currentTime }, onRecord = { records += it })

    // ── A fall ───────────────────────────────────────────────────────────────

    @Test
    fun `a fall waits out the cancel window, then texts, then calls`() = runTest {
        val sos = coordinator()
        assertTrue(sos.raise(fallText, fall = true))
        val waiting = sos.pending.value!!
        assertEquals(20_000L, waiting.sendAt)
        assertTrue("the wearer is shown this wait", waiting.cancellable)
        assertTrue(waiting.willCall)

        advanceTimeBy(19_000)
        assertTrue(sender.texts.isEmpty())

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(1, sender.texts.size)
        assertEquals("+919876543210", sender.texts[0].first)
        assertTrue(sender.texts[0].second.contains(fallText))
        assertTrue("the call waits for the text", sender.calls.isEmpty())
        assertNull(sos.pending.value)

        advanceTimeBy(SosCoordinator.CALL_AFTER_MILLIS + 1)
        runCurrent()
        assertEquals(listOf("+919876543210"), sender.calls)
        assertEquals(SosResult.SENT, records.single().sms)
        assertEquals(SosResult.CALL_PLACED, records.single().call)
    }

    @Test
    fun `I'm OK during a fall's window sends nothing`() = runTest {
        val sos = coordinator()
        sos.raise(fallText, fall = true)
        advanceTimeBy(10_000)
        assertTrue(sos.cancel())
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(sender.texts.isEmpty())
        assertTrue(sender.calls.isEmpty())
        assertTrue(records.isEmpty())
        assertFalse("nothing left to cancel", sos.cancel())
    }

    @Test
    fun `with the window set to at once, a fall sends like any other alert`() = runTest {
        config = config!!.copy(fallCancelSeconds = 0)
        val sos = coordinator()
        sos.raise(fallText, fall = true)
        assertFalse("there is nothing to show or stop", sos.pending.value!!.cancellable)
        advanceTimeBy(SosCoordinator.BATCH_MILLIS + 1)
        runCurrent()
        assertEquals(1, sender.texts.size)
    }

    @Test
    fun `the default window is twenty seconds and is one of the choices offered`() {
        assertEquals(20, SosCoordinator.DEFAULT_FALL_CANCEL_SECONDS)
        assertTrue(SosCoordinator.DEFAULT_FALL_CANCEL_SECONDS in SosCoordinator.FALL_CANCEL_CHOICES)
        assertTrue(0 in SosCoordinator.FALL_CANCEL_CHOICES)
    }

    // ── Everything else ──────────────────────────────────────────────────────

    @Test
    fun `an alert that is not a fall sends without waiting for the wearer`() = runTest {
        val sos = coordinator()
        sos.raise("Blood oxygen 86 %")
        val waiting = sos.pending.value!!
        assertEquals(SosCoordinator.BATCH_MILLIS, waiting.sendAt)
        assertFalse("no countdown is shown for it", waiting.cancellable)

        advanceTimeBy(SosCoordinator.BATCH_MILLIS + 1)
        runCurrent()
        assertEquals(1, sender.texts.size)
        assertTrue(sender.texts[0].second.contains("Blood oxygen 86 %"))
    }

    @Test
    fun `alerts from the same reading go in one message, each listed once`() = runTest {
        config = config!!.copy(callAfter = false)
        val sos = coordinator()
        sos.raise("Heart rate 34 beats a minute, very low")
        sos.raise("Blood oxygen 86 %")
        sos.raise("Blood oxygen 86 %")
        advanceTimeBy(SosCoordinator.BATCH_MILLIS + 1)
        runCurrent()
        assertEquals(1, sender.texts.size)
        assertTrue(sender.texts[0].second.contains("Heart rate 34 beats a minute, very low; Blood oxygen 86 %"))
        assertEquals(2, records.single().reasons.size)
        assertTrue(sender.calls.isEmpty())
    }

    // ── A fall and something else together ───────────────────────────────────

    /** Low oxygen after a fall is not something to sit on for the rest of the window. */
    @Test
    fun `another alert during a fall's window ends the wait and both go together`() = runTest {
        val sos = coordinator()
        sos.raise(fallText, fall = true)
        advanceTimeBy(5_000)
        sos.raise("Blood oxygen 86 %")

        val waiting = sos.pending.value!!
        assertEquals(5_000 + SosCoordinator.BATCH_MILLIS, waiting.sendAt)
        assertFalse("the countdown comes down: it can no longer be stopped", waiting.cancellable)

        advanceTimeBy(SosCoordinator.BATCH_MILLIS + 1)
        runCurrent()
        assertEquals(1, sender.texts.size)
        assertTrue(sender.texts[0].second.contains("$fallText; Blood oxygen 86 %"))

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("the fall's own timer does not send it again", 1, sender.texts.size)
    }

    @Test
    fun `a fall arriving with another alert does not delay that alert`() = runTest {
        val sos = coordinator()
        sos.raise("Blood oxygen 86 %")
        sos.raise(fallText, fall = true)
        assertEquals(SosCoordinator.BATCH_MILLIS, sos.pending.value!!.sendAt)
        assertFalse(sos.pending.value!!.cancellable)
        advanceTimeBy(SosCoordinator.BATCH_MILLIS + 1)
        runCurrent()
        assertEquals(1, sender.texts.size)
        assertTrue(sender.texts[0].second.contains("Blood oxygen 86 %; $fallText"))
    }

    @Test
    fun `a second fall during the window does not restart it`() = runTest {
        val sos = coordinator()
        sos.raise(fallText, fall = true)
        advanceTimeBy(15_000)
        sos.raise("Very hard impact (7.0 g); a possible fall", fall = true)
        assertEquals(20_000L, sos.pending.value!!.sendAt)
        assertTrue(sos.pending.value!!.cancellable)
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(1, sender.texts.size)
    }

    // ── Failures and switches ────────────────────────────────────────────────

    @Test
    fun `sender exception is recorded instead of losing the SOS worker`() = runTest {
        val failing = object : SosSender {
            override suspend fun sendSms(number: String, text: String): SosResult = error("radio failure")
            override fun placeCall(number: String): SosResult = error("call failure")
        }
        val sos = SosCoordinator(backgroundScope, { config }, failing,
            clock = { currentTime }, onRecord = { records += it })
        sos.raise("Fever")
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(SosResult.CANNOT_SEND, records.single().sms)
        assertEquals(SosResult.CANNOT_SEND, records.single().call)
    }

    @Test
    fun `switched off, nothing starts`() = runTest {
        config = null
        val sos = coordinator()
        assertFalse(sos.raise("Blood oxygen 86 %"))
        assertFalse(sos.raise(fallText, fall = true))
        assertNull(sos.pending.value)
        advanceTimeBy(120_000)
        assertTrue(sender.texts.isEmpty())
    }

    @Test
    fun `switched off during a fall's window, nothing is sent`() = runTest {
        val sos = coordinator()
        sos.raise(fallText, fall = true)
        advanceTimeBy(10_000)
        config = null
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(sender.texts.isEmpty())
    }

    @Test
    fun `with calling off it only texts`() = runTest {
        config = config!!.copy(callAfter = false)
        val sos = coordinator()
        sos.raise("Blood oxygen 86 %")
        assertFalse(sos.pending.value!!.willCall)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, sender.texts.size)
        assertTrue(sender.calls.isEmpty())
        assertNull(records.single().call)
    }

    @Test
    fun `a text that failed is recorded as failed, and the call still goes`() = runTest {
        sender.smsResult = SosResult.NO_SERVICE
        val sos = coordinator()
        sos.raise("Blood oxygen 86 %")
        advanceTimeBy(40_000)
        runCurrent()
        assertEquals(SosResult.NO_SERVICE, records.single().sms)
        assertEquals(1, sender.calls.size)
    }

    @Test
    fun `when monitoring is ended mid-window by the system, the SOS goes at once`() = runTest {
        val sos = coordinator()
        sos.raise(fallText, fall = true)
        advanceTimeBy(3_000)
        sos.sendPendingNow()
        assertEquals(1, sender.texts.size)
        assertNull(sos.pending.value)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("the cancelled window does not send it twice", 1, sender.texts.size)
    }

    @Test
    fun `a test text goes at once, marked as a test, with no call`() = runTest {
        val record = sendTestSos(sender, "+919876543210", "Aditya", now = 0)
        assertTrue(sender.texts.single().second.startsWith("TEST from G-one"))
        assertTrue(sender.calls.isEmpty())
        assertTrue(record.test)
    }
}
