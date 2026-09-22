package com.gone.ai.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Speaks text with the phone's text-to-speech engine.
 *
 * One engine for the whole app, shared by voice chat and "Read aloud", because it takes a
 * moment to start. Sentences queue in order, including any queued before the engine is
 * ready. [speaking] stays true until the last queued sentence has been said. While speaking,
 * other audio is ducked.
 */
class SpeechOutput private constructor(context: Context) : Speaker {

    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)

    /** Guards the queue counters and audio focus; engine callbacks arrive on another thread. */
    private val lock = Any()
    /** Bumped by [stop], so callbacks for utterances it discarded are ignored. */
    private var epoch = 0
    private var nextId = 0L
    private var pending = 0
    private val waitingForEngine = mutableListOf<String>()
    private var focusRequest: AudioFocusRequest? = null

    @Volatile private var ready = false

    private val _unavailable = MutableStateFlow<String?>(null)
    /** Why replies cannot be spoken, if they cannot. */
    val unavailable: StateFlow<String?> = _unavailable.asStateFlow()

    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val attributes = AudioAttributes.Builder()
        .setUsage(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) AudioAttributes.USAGE_ASSISTANT
            else AudioAttributes.USAGE_MEDIA
        )
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) configure() else fail("No text-to-speech engine is available on this phone.")
    }

    private fun configure() {
        tts.setAudioAttributes(attributes)
        if (!languageSet(Locale.getDefault()) && !languageSet(Locale.US)) {
            fail("The phone's text-to-speech voice is not installed. Add one in the phone's text-to-speech settings.")
            return
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = ended(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = ended(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "Speech error $errorCode")
                ended(utteranceId)
            }
            override fun onStop(utteranceId: String?, interrupted: Boolean) = ended(utteranceId)
        })
        synchronized(lock) {
            ready = true
            waitingForEngine.forEach(::sayLocked)
            waitingForEngine.clear()
        }
    }

    private fun languageSet(locale: Locale): Boolean =
        tts.setLanguage(locale) !in setOf(TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED)

    private fun fail(reason: String) {
        _unavailable.value = reason
        synchronized(lock) {
            waitingForEngine.clear()
            pending = 0
            _speaking.value = false
        }
    }

    /** Add [sentence] to what is being said. */
    override fun speak(sentence: String) {
        if (sentence.isBlank() || _unavailable.value != null) return
        synchronized(lock) {
            pending++
            _speaking.value = true
            if (ready) sayLocked(sentence) else waitingForEngine += sentence
        }
    }

    private fun sayLocked(sentence: String) {
        requestFocusLocked()
        val id = "$epoch:${nextId++}"
        if (tts.speak(sentence, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.ERROR) endedLocked(id)
    }

    private fun ended(utteranceId: String?) = synchronized(lock) { endedLocked(utteranceId) }

    private fun endedLocked(utteranceId: String?) {
        if (utteranceId?.substringBefore(':') != epoch.toString()) return
        pending = (pending - 1).coerceAtLeast(0)
        if (pending == 0) {
            _speaking.value = false
            abandonFocusLocked()
        }
    }

    /** Stop now and forget anything queued. */
    override fun stop() {
        synchronized(lock) {
            epoch++
            pending = 0
            waitingForEngine.clear()
            _speaking.value = false
            abandonFocusLocked()
            if (ready) tts.stop()
        }
    }

    private fun requestFocusLocked() {
        if (focusRequest != null || audioManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        audioManager.requestAudioFocus(request)
        focusRequest = request
    }

    private fun abandonFocusLocked() {
        val request = focusRequest ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) audioManager?.abandonAudioFocusRequest(request)
        focusRequest = null
    }

    companion object {
        private const val TAG = "SpeechOutput"

        @Volatile private var instance: SpeechOutput? = null

        fun getInstance(context: Context): SpeechOutput =
            instance ?: synchronized(this) {
                instance ?: SpeechOutput(context).also { instance = it }
            }
    }
}
