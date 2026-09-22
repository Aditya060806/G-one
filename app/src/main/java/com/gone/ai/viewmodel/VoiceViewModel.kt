package com.gone.ai.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gone.ai.chat.ReplyProgress
import com.gone.ai.voice.Conversation
import com.gone.ai.voice.SpeechInput
import com.gone.ai.voice.SpeechOutput
import com.gone.ai.voice.VoiceLoop
import kotlinx.coroutines.flow.StateFlow

/**
 * The voice screen: the phone's recogniser and voice around the open chat conversation.
 *
 * Scoped to the voice screen, so leaving it closes the recogniser and stops speaking.
 * Voice turns are ordinary chat messages and use the same personal context as typed ones.
 */
class VoiceViewModel(app: Application, conversation: Conversation) : AndroidViewModel(app) {

    private val input = SpeechInput(app)
    private val output = SpeechOutput.getInstance(app)
    private val loop = VoiceLoop(viewModelScope, input, output, conversation)

    val state: StateFlow<VoiceLoop.State> = loop.state

    /** True while G-one's voice is saying something. */
    val speaking: StateFlow<Boolean> = output.speaking

    /** Why replies are shown but not spoken, if they cannot be. */
    val speechUnavailable: StateFlow<String?> = output.unavailable

    /** False when the phone has no speech recogniser at all. */
    val canListen: Boolean get() = input.isAvailable

    fun tap() = loop.tap()
    fun listen() = loop.listen()
    fun stop() = loop.stop()
    fun setHandsFree(on: Boolean) = loop.setHandsFree(on)
    fun setMuted(on: Boolean) = loop.setMuted(on)
    fun show(message: String?) = loop.show(message)

    override fun onCleared() {
        loop.close()
        super.onCleared()
    }

    companion object {
        fun factory(app: Application, chat: ChatViewModel): ViewModelProvider.Factory = viewModelFactory {
            initializer { VoiceViewModel(app, conversationOf(chat)) }
        }

        private fun conversationOf(chat: ChatViewModel) = object : Conversation {
            override fun send(text: String): Long? = chat.sendVoiceMessage(text)
            override val latestReply: StateFlow<ReplyProgress?> = chat.latestReply
            override fun stop() = chat.stopGeneration()
        }
    }
}
