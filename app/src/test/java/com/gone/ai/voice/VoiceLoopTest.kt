package com.gone.ai.voice

import com.gone.ai.chat.ReplyProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceLoopTest {

    private class FakeListener : Listener {
        override var onDevice = true
        var starts = 0
        var stops = 0
        var cancels = 0
        var events: ((Heard) -> Unit)? = null
        override fun start(onEvent: (Heard) -> Unit) {
            starts++
            events = onEvent
        }
        override fun stop() { stops++ }
        override fun cancel() { cancels++ }
        override fun useDefaultService() { onDevice = false }
        fun hear(event: Heard) = events!!.invoke(event)
    }

    private class FakeSpeaker : Speaker {
        override val speaking = MutableStateFlow(false)
        val said = mutableListOf<String>()
        var stops = 0
        override fun speak(sentence: String) {
            said += sentence
            speaking.value = true
        }
        override fun stop() {
            stops++
            speaking.value = false
        }
        fun finishSpeaking() { speaking.value = false }
    }

    private class FakeConversation : Conversation {
        val sent = mutableListOf<String>()
        var stops = 0
        private var nextId = 10L
        override val latestReply = MutableStateFlow<ReplyProgress?>(null)
        override fun send(text: String): Long {
            sent += text
            val id = nextId++
            latestReply.value = ReplyProgress(id, "", finished = false)
            return id
        }
        override fun stop() {
            stops++
            latestReply.value = latestReply.value?.copy(finished = true)
        }
        fun stream(text: String, finished: Boolean = false) {
            latestReply.value = latestReply.value!!.copy(text = text, finished = finished)
        }
    }

    private class Setup(scope: TestScope) {
        val listener = FakeListener()
        val speaker = FakeSpeaker()
        val chat = FakeConversation()
        val loop = VoiceLoop(scope.backgroundScope, listener, speaker, chat)
        val state: StateFlow<VoiceLoop.State> get() = loop.state
    }

    @Test
    fun `a spoken question goes to the chat and the reply is spoken sentence by sentence`() = runTest {
        val s = Setup(this)
        runCurrent()
        s.loop.tap()
        assertEquals(VoiceLoop.Phase.Listening, s.state.value.phase)

        s.listener.hear(Heard.Partial("how did my"))
        assertEquals("how did my", s.state.value.heard)
        s.listener.hear(Heard.Final("How did my last session go?"))
        assertEquals(listOf("How did my last session go?"), s.chat.sent)
        assertEquals(VoiceLoop.Phase.Thinking, s.state.value.phase)

        s.chat.stream("Your session lasted 20 minutes. Heart")
        runCurrent()
        assertEquals(VoiceLoop.Phase.Answering, s.state.value.phase)
        assertEquals(listOf("Your session lasted 20 minutes."), s.speaker.said)

        s.chat.stream("Your session lasted 20 minutes. Heart rate stayed steady.", finished = true)
        runCurrent()
        assertEquals(listOf("Your session lasted 20 minutes.", "Heart rate stayed steady."), s.speaker.said)
    }

    @Test
    fun `hands-free listens again only after the reply has been said`() = runTest {
        val s = Setup(this)
        runCurrent()
        s.loop.listen()
        s.listener.hear(Heard.Final("Hello"))
        s.chat.stream("Hi there.", finished = true)
        runCurrent()
        assertEquals(1, s.listener.starts)
        assertEquals(VoiceLoop.Phase.Answering, s.state.value.phase)

        s.speaker.finishSpeaking()
        runCurrent()
        assertEquals(2, s.listener.starts)
        assertEquals(VoiceLoop.Phase.Listening, s.state.value.phase)
    }

    @Test
    fun `without hands-free the loop rests after the reply`() = runTest {
        val s = Setup(this)
        runCurrent()
        s.loop.setHandsFree(false)
        s.loop.listen()
        s.listener.hear(Heard.Final("Hello"))
        s.chat.stream("Hi there.", finished = true)
        runCurrent()
        s.speaker.finishSpeaking()
        runCurrent()
        assertEquals(1, s.listener.starts)
        assertEquals(VoiceLoop.Phase.Idle, s.state.value.phase)
    }

    @Test
    fun `muted replies are shown, not spoken, and the turn still ends`() = runTest {
        val s = Setup(this)
        runCurrent()
        s.loop.setMuted(true)
        s.loop.setHandsFree(false)
        s.loop.listen()
        s.listener.hear(Heard.Final("Hello"))
        s.chat.stream("Hi there. How are you?", finished = true)
        runCurrent()
        assertTrue(s.speaker.said.isEmpty())
        assertEquals("Hi there. How are you?", s.state.value.reply)
        assertEquals(VoiceLoop.Phase.Idle, s.state.value.phase)
    }

    @Test
    fun `tapping while G-one answers interrupts it and listens`() = runTest {
        val s = Setup(this)
        runCurrent()
        s.loop.listen()
        s.listener.hear(Heard.Final("Tell me a long story"))
        s.chat.stream("Once upon a time. There was")
        runCurrent()
        s.loop.tap()
        assertEquals(1, s.chat.stops)
        assertTrue(s.speaker.stops > 0)
        assertEquals(VoiceLoop.Phase.Listening, s.state.value.phase)

        // The stopped reply finishing afterwards must not end the new turn.
        runCurrent()
        assertEquals(VoiceLoop.Phase.Listening, s.state.value.phase)
        assertEquals(2, s.listener.starts)
    }

    @Test
    fun `tapping while listening sends what was heard`() = runTest {
        val s = Setup(this)
        runCurrent()
        s.loop.tap()
        s.loop.tap()
        assertEquals(1, s.listener.stops)
    }

    @Test
    fun `hearing nothing stops hands-free with a gentle message`() = runTest {
        val s = Setup(this)
        runCurrent()
        s.loop.listen()
        s.listener.hear(Heard.Failed(SpeechErrors.ERROR_SPEECH_TIMEOUT))
        assertEquals(VoiceLoop.Phase.Idle, s.state.value.phase)
        assertTrue(s.state.value.message!!.contains("did not hear"))
        assertTrue(s.chat.sent.isEmpty())
    }

    @Test
    fun `an empty result is treated like hearing nothing`() = runTest {
        val s = Setup(this)
        runCurrent()
        s.loop.listen()
        s.listener.hear(Heard.Final("   "))
        assertEquals(VoiceLoop.Phase.Idle, s.state.value.phase)
        assertTrue(s.chat.sent.isEmpty())
    }

    @Test
    fun `a missing offline pack retries with the phone's service and says so`() = runTest {
        val s = Setup(this)
        runCurrent()
        s.loop.listen()
        s.listener.hear(Heard.Failed(SpeechErrors.ERROR_LANGUAGE_UNAVAILABLE))
        assertEquals(2, s.listener.starts)
        assertEquals(VoiceLoop.Phase.Listening, s.state.value.phase)
        assertFalse(s.state.value.onDevice)
        assertTrue(s.state.value.message!!.contains("offline speech pack"))
    }

    @Test
    fun `a permission error asks for the microphone`() = runTest {
        val s = Setup(this)
        runCurrent()
        s.loop.listen()
        s.listener.hear(Heard.Failed(SpeechErrors.ERROR_INSUFFICIENT_PERMISSIONS))
        assertTrue(s.state.value.needsPermission)
    }

    @Test
    fun `events from an earlier listen are ignored`() = runTest {
        val s = Setup(this)
        runCurrent()
        s.loop.listen()
        val old = s.listener.events!!
        s.loop.stop()
        old(Heard.Final("late words"))
        assertTrue(s.chat.sent.isEmpty())
        assertEquals(VoiceLoop.Phase.Idle, s.state.value.phase)
    }

    @Test
    fun `stop while thinking stops the chat reply`() = runTest {
        val s = Setup(this)
        runCurrent()
        s.loop.listen()
        s.listener.hear(Heard.Final("Hello"))
        s.loop.tap()
        assertEquals(1, s.chat.stops)
        assertEquals(VoiceLoop.Phase.Idle, s.state.value.phase)
    }

    @Test
    fun `closing leaves the chat reply running but stops listening and speech`() = runTest {
        val s = Setup(this)
        runCurrent()
        s.loop.listen()
        s.listener.hear(Heard.Final("Hello"))
        s.loop.close()
        assertEquals(0, s.chat.stops)
        assertEquals(1, s.listener.cancels)
        assertTrue(s.speaker.stops > 0)
        s.chat.stream("Hi there.", finished = true)
        runCurrent()
        assertTrue(s.speaker.said.isEmpty())
    }

    @Test
    fun `unmuting mid-reply continues from the next sentence`() = runTest {
        val s = Setup(this)
        runCurrent()
        s.loop.setMuted(true)
        s.loop.listen()
        s.listener.hear(Heard.Final("Hello"))
        s.chat.stream("First part. ")
        runCurrent()
        s.loop.setMuted(false)
        s.chat.stream("First part. Second part.", finished = true)
        runCurrent()
        assertEquals(listOf("Second part."), s.speaker.said)
    }

    @Test
    fun `a reply that finishes before it is observed is still spoken in full`() = runTest {
        val s = Setup(this)
        runCurrent()
        s.loop.setHandsFree(false)
        s.loop.listen()
        s.listener.hear(Heard.Final("Hello"))
        s.chat.stream("One.")
        s.chat.stream("One. Two.", finished = true)
        runCurrent()
        assertEquals(listOf("One.", "Two."), s.speaker.said)
        assertNull(s.state.value.message)
    }
}
