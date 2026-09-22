package com.gone.ai.voice

import com.gone.ai.chat.ReplyProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the recogniser reports while listening. */
sealed interface Heard {
    data object Ready : Heard
    data class Partial(val text: String) : Heard
    /** Microphone level, 0 to 1. */
    data class Level(val level: Float) : Heard
    data class Final(val text: String) : Heard
    data class Failed(val code: Int) : Heard
}

/** Speech to text. */
interface Listener {
    /** True when listening uses the on-device recogniser, so nothing leaves the phone. */
    val onDevice: Boolean
    fun start(onEvent: (Heard) -> Unit)
    /** Stop and deliver what was heard so far. */
    fun stop()
    /** Stop and discard. */
    fun cancel()
    fun useDefaultService()
}

/** Text to speech. */
interface Speaker {
    /** True from the first queued sentence until the last one has been said. */
    val speaking: StateFlow<Boolean>
    fun speak(sentence: String)
    fun stop()
}

/** The chat that voice turns go into. */
interface Conversation {
    /** Sends the person's words; returns the id of the reply it starts, or null if nothing was sent. */
    fun send(text: String): Long?
    val latestReply: StateFlow<ReplyProgress?>
    fun stop()
}

/**
 * One voice conversation: listen, send what was heard to the chat, speak the reply as it
 * streams, then listen again when hands-free is on.
 *
 * Kept free of Android classes so every transition is unit-tested; [VoiceViewModel][com.gone.ai.viewmodel.VoiceViewModel]
 * connects it to the recogniser, the text-to-speech voice and the chat. Call from one thread.
 */
class VoiceLoop(
    private val scope: CoroutineScope,
    private val listener: Listener,
    private val speaker: Speaker,
    private val conversation: Conversation
) {

    enum class Phase { Idle, Listening, Thinking, Answering }

    data class State(
        val phase: Phase = Phase.Idle,
        /** The person's words: partial while listening. */
        val heard: String = "",
        /** G-one's reply so far. */
        val reply: String = "",
        val level: Float = 0f,
        val handsFree: Boolean = true,
        val muted: Boolean = false,
        val onDevice: Boolean = true,
        /** Something to tell the person, e.g. why listening stopped. */
        val message: String? = null,
        val needsPermission: Boolean = false
    )

    private val _state = MutableStateFlow(State(onDevice = listener.onDevice))
    val state: StateFlow<State> = _state.asStateFlow()

    private val speakable = SpeakableText()

    /** Recogniser events from an earlier listen are ignored. */
    private var listenRun = 0
    private var replyId: Long? = null
    private var replyFinished = false
    private var finishJob: Job? = null

    init {
        scope.launch { conversation.latestReply.collect { onReply(it) } }
    }

    /** The main button. */
    fun tap() {
        when (_state.value.phase) {
            Phase.Idle -> listen()
            Phase.Listening -> listener.stop()
            Phase.Thinking -> stop()
            // Interrupt G-one and speak.
            Phase.Answering -> listen()
        }
    }

    fun listen() {
        endTurn()
        val run = ++listenRun
        _state.update {
            it.copy(
                phase = Phase.Listening, heard = "", reply = "", level = 0f,
                message = null, needsPermission = false, onDevice = listener.onDevice
            )
        }
        listener.start { event -> if (run == listenRun) onHeard(event) }
    }

    /** Stop listening, thinking and speaking. */
    fun stop() {
        listenRun++
        listener.cancel()
        endTurn()
        _state.update { it.copy(phase = Phase.Idle, level = 0f) }
    }

    /** The screen is going away: stop without leaving the recogniser open. The chat keeps its reply. */
    fun close() {
        listenRun++
        listener.cancel()
        finishJob?.cancel()
        replyId = null
        speaker.stop()
    }

    fun setHandsFree(on: Boolean) = _state.update { it.copy(handsFree = on) }

    fun setMuted(on: Boolean) {
        _state.update { it.copy(muted = on) }
        if (on) speaker.stop()
    }

    /** Tell the person something without changing what is happening. */
    fun show(message: String?) = _state.update { it.copy(message = message) }

    private fun onHeard(event: Heard) {
        when (event) {
            Heard.Ready -> Unit
            is Heard.Partial -> _state.update { it.copy(heard = event.text) }
            is Heard.Level -> _state.update { it.copy(level = event.level) }
            is Heard.Final -> {
                listenRun++
                val text = event.text.trim()
                if (text.isEmpty()) {
                    val described = SpeechErrors.describe(SpeechErrors.ERROR_NO_MATCH, listener.onDevice)
                    _state.update { it.copy(phase = Phase.Idle, level = 0f, message = described.message) }
                } else {
                    send(text)
                }
            }
            is Heard.Failed -> {
                listenRun++
                val described = SpeechErrors.describe(event.code, listener.onDevice)
                if (described.tryDefaultService) {
                    listener.useDefaultService()
                    listen()
                    _state.update { it.copy(message = described.message) }
                } else {
                    // Hearing nothing ends hands-free too, so the microphone never stays on by itself.
                    _state.update {
                        it.copy(
                            phase = Phase.Idle, level = 0f,
                            message = described.message, needsPermission = described.needsPermission
                        )
                    }
                }
            }
        }
    }

    private fun send(text: String) {
        val id = conversation.send(text)
        if (id == null) {
            _state.update { it.copy(phase = Phase.Idle, level = 0f, message = "That could not be sent. Please try again.") }
            return
        }
        replyId = id
        replyFinished = false
        speakable.reset()
        _state.update { it.copy(phase = Phase.Thinking, heard = text, reply = "", level = 0f) }
    }

    private fun onReply(progress: ReplyProgress?) {
        val id = replyId ?: return
        if (progress == null || progress.messageId != id || replyFinished) return
        val muted = _state.value.muted
        // Sentences are taken even when muted, so unmuting continues from here instead of repeating.
        val sentences = if (progress.finished) speakable.finish(progress.text) else speakable.update(progress.text)
        if (!muted) sentences.forEach(speaker::speak)
        if (progress.text.isNotEmpty() || progress.finished) {
            _state.update { it.copy(phase = Phase.Answering, reply = progress.text) }
        }
        if (progress.finished) {
            replyFinished = true
            finishJob = scope.launch {
                speaker.speaking.first { !it }
                turnDone()
            }
        }
    }

    private fun turnDone() {
        finishJob = null
        replyId = null
        if (_state.value.handsFree) {
            listen()
        } else {
            _state.update { it.copy(phase = Phase.Idle, level = 0f) }
        }
    }

    /** Stop the current reply and its speech; the chat keeps what was written. */
    private fun endTurn() {
        finishJob?.cancel()
        finishJob = null
        if (replyId != null && !replyFinished) conversation.stop()
        replyId = null
        replyFinished = false
        speaker.stop()
        speakable.reset()
    }
}
