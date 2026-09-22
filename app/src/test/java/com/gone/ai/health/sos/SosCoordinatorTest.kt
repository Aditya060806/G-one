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

@OptIn(ExperimentalCoroutinesApi::class)
class SosCoordinatorTest {

    private var config: SosConfig? = SosConfig(number = "+919876543210", name = "Aditya", countdownSeconds = 30, callAfter = true)
    private val sender = FakeSender()
    private val records = mutableListOf<SosRecord>()

    private fun TestScope.coordinator() =
        SosCoordinator(backgroundScope, config = { config }, sender = sender, clock = { currentTime }, onRecord = { records += it })

    @Test
    fun `after the countdown it texts, then calls`() = runTest {
        val sos = coordinator()
        assertTrue(sos.raise("Blood oxygen 86 %"))
        assertEquals(30_000L, sos.pending.value!!.sendAt)

        advanceTimeBy(29_000)
        assertTrue(sender.texts.isEmpty())

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(1, sender.texts.size)
        assertEquals("+919876543210", sender.texts[0].first)
        assertTrue(sender.texts[0].second.contains("Blood oxygen 86 %"))
        assertTrue("the call waits for the text", sender.calls.isEmpty())
        assertNull(sos.pending.value)

        advanceTimeBy(SosCoordinator.CALL_AFTER_MILLIS + 1)
        runCurrent()
        assertEquals(listOf("+919876543210"), sender.calls)
        assertEquals(SosResult.SENT, records.single().sms)
        assertEquals(SosResult.CALL_PLACED, records.single().call)
    }

    @Test
    fun `I'm OK during the countdown sends nothing`() = runTest {
        val sos = coordinator()
        sos.raise("Possible fall: a hard impact, then no movement")
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
    fun `alerts during one countdown go in one message`() = runTest {
        val sos = coordinator()
        sos.raise("Heart rate 158 beats a minute, very high")
        advanceTimeBy(5_000)
        sos.raise("Blood oxygen 86 %")
        sos.raise("Blood oxygen 86 %")
        advanceTimeBy(26_000)
        runCurrent()
        assertEquals(1, sender.texts.size)
        assertTrue(sender.texts[0].second.contains("Heart rate 158 beats a minute, very high; Blood oxygen 86 %"))
        advanceTimeBy(SosCoordinator.CALL_AFTER_MILLIS + 1)   // recorded once the call has gone too
        runCurrent()
        assertEquals(2, records.single().reasons.size)
    }

    @Test
    fun `set to send at once, it still gathers alerts from the same reading`() = runTest {
        config = config!!.copy(countdownSeconds = 0)
        val sos = coordinator()
        sos.raise("Heart rate 34 beats a minute, very low")
        sos.raise("Blood oxygen 86 %")
        advanceTimeBy(SosCoordinator.BATCH_MILLIS + 1)
        runCurrent()
        assertEquals(1, sender.texts.size)
    }

    @Test
    fun `automatic default sends without any user response and without a countdown`() = runTest {
        config = config!!.copy(countdownSeconds = SosCoordinator.DEFAULT_COUNTDOWN_SECONDS, callAfter = false)
        val sos = coordinator()
        sos.raise("Fever")
        sos.raise("Blood oxygen 86 %")
        advanceTimeBy(SosCoordinator.BATCH_MILLIS + 1)
        runCurrent()
        assertEquals(0, SosCoordinator.DEFAULT_COUNTDOWN_SECONDS)
        assertEquals(1, sender.texts.size)
        assertEquals(2, records.single().reasons.size)
        assertTrue(sender.calls.isEmpty())
    }

    @Test
    fun `sender exception is recorded instead of losing the SOS worker`() = runTest {
        val failing = object : SosSender {
            override suspend fun sendSms(number: String, text: String): SosResult = error("radio failure")
            override fun placeCall(number: String): SosResult = error("call failure")
        }
        val sos = SosCoordinator(backgroundScope, { config!!.copy(countdownSeconds = 0) }, failing,
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
        assertNull(sos.pending.value)
        advanceTimeBy(120_000)
        assertTrue(sender.texts.isEmpty())
    }

    @Test
    fun `switched off during the countdown, nothing is sent`() = runTest {
        val sos = coordinator()
        sos.raise("Blood oxygen 86 %")
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
    fun `when monitoring is ended mid-countdown by the system, the SOS goes at once`() = runTest {
        val sos = coordinator()
        sos.raise("Blood oxygen 86 %")
        advanceTimeBy(3_000)
        sos.sendPendingNow()
        assertEquals(1, sender.texts.size)
        assertNull(sos.pending.value)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("the cancelled countdown does not send it twice", 1, sender.texts.size)
    }

    @Test
    fun `a test text goes at once, marked as a test, with no call`() = runTest {
        val record = sendTestSos(sender, "+919876543210", "Aditya", now = 0)
        assertTrue(sender.texts.single().second.startsWith("TEST from G-one"))
        assertTrue(sender.calls.isEmpty())
        assertTrue(record.test)
    }
}
