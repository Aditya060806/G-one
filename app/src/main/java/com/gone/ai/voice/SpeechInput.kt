package com.gone.ai.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Listens for one spoken message with the phone's speech recogniser.
 *
 * Prefers the on-device recogniser (Android 13 and later, when the phone has one), which
 * works without internet. Otherwise it uses the phone's default speech service with a
 * request to stay offline, which the service may not honour; [onDevice] says which is in use
 * so the screen can tell the person. Must be used on the main thread.
 */
class SpeechInput(private val context: Context) : Listener {

    private var recognizer: SpeechRecognizer? = null

    /** False after the on-device recogniser failed for this language; the default one is used from then. */
    private var allowOnDevice = true

    override val onDevice: Boolean get() = allowOnDevice && onDeviceAvailable()

    /** False when the phone has no speech recogniser at all. */
    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context) || onDeviceAvailable()

    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override fun start(onEvent: (Heard) -> Unit) {
        cancel()
        val created = if (onDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = created
        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onEvent(Heard.Ready)
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) {
                // Roughly -2 dB (silence) to 10 dB (speech) on most recognisers.
                onEvent(Heard.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) = onEvent(Heard.Failed(error))
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                onEvent(Heard.Final(text))
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { onEvent(Heard.Partial(it)) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        created.startListening(intent)
    }

    /** Use the phone's default speech service from now on, e.g. after the offline pack was missing. */
    override fun useDefaultService() {
        allowOnDevice = false
    }

    override fun stop() {
        recognizer?.stopListening()
    }

    override fun cancel() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }
}
