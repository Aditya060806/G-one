package com.gone.ai.voice

/**
 * What a speech-recognition error means for the person, in their words.
 *
 * The voice screen used to ignore every error, so a missing language pack or a busy
 * recogniser looked like the app simply not listening. The codes are Android's
 * `SpeechRecognizer.ERROR_*` values, kept as numbers here so this is unit-testable.
 */
object SpeechErrors {

    data class Described(
        val message: String,
        /** True when trying the phone's default speech service may work where on-device did not. */
        val tryDefaultService: Boolean = false,
        /** True when the microphone permission is the problem. */
        val needsPermission: Boolean = false,
        /** True when nothing went wrong beyond not hearing anything. */
        val quiet: Boolean = false
    )

    const val ERROR_NETWORK_TIMEOUT = 1
    const val ERROR_NETWORK = 2
    const val ERROR_AUDIO = 3
    const val ERROR_SERVER = 4
    const val ERROR_CLIENT = 5
    const val ERROR_SPEECH_TIMEOUT = 6
    const val ERROR_NO_MATCH = 7
    const val ERROR_RECOGNIZER_BUSY = 8
    const val ERROR_INSUFFICIENT_PERMISSIONS = 9
    const val ERROR_TOO_MANY_REQUESTS = 10
    const val ERROR_SERVER_DISCONNECTED = 11
    const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    const val ERROR_LANGUAGE_UNAVAILABLE = 13
    const val ERROR_CANNOT_CHECK_SUPPORT = 14

    fun describe(code: Int, onDevice: Boolean): Described = when (code) {
        ERROR_NETWORK, ERROR_NETWORK_TIMEOUT, ERROR_SERVER, ERROR_SERVER_DISCONNECTED ->
            Described("The phone's speech service could not be reached. Voice input works without internet only if the offline language pack is installed.")
        ERROR_AUDIO -> Described("The microphone could not be used. Another app may be using it.")
        ERROR_CLIENT -> Described("Listening stopped. Tap the microphone to try again.")
        ERROR_SPEECH_TIMEOUT -> Described("I did not hear anything. Tap the microphone and speak.", quiet = true)
        ERROR_NO_MATCH -> Described("I did not catch that. Please try again.", quiet = true)
        ERROR_RECOGNIZER_BUSY, ERROR_TOO_MANY_REQUESTS -> Described("Speech recognition is busy. Try again in a moment.")
        ERROR_INSUFFICIENT_PERMISSIONS -> Described("G-one needs the microphone permission to hear you.", needsPermission = true)
        ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE, ERROR_CANNOT_CHECK_SUPPORT ->
            if (onDevice) {
                Described(
                    "The offline speech pack for your language is not installed, so the phone's speech service will be used instead.",
                    tryDefaultService = true
                )
            } else {
                Described("Your phone's speech service does not support this language.")
            }
        else -> Described("Speech recognition failed (code $code). Please try again.")
    }
}
