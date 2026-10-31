package com.gone.ai.voice

import java.util.Locale

/**
 * The language G-one listens and speaks in.
 *
 * PURE KOTLIN, so everything that words a sentence by language can be tested on the JVM.
 *
 * It sets the phone's speech recogniser and voice, the language the assistant answers in,
 * spoken alerts and "Ask your health". Alert and answer sentences are written out for each
 * language here in the app, not translated by the model, so a spoken alert never depends on
 * the model getting a translation right.
 */
enum class AppLanguage(
    /** BCP-47 tag for the recogniser and the voice. */
    val tag: String,
    /** The name in English, for anyone who cannot read the native one. */
    val englishName: String,
    /** The name as a speaker of the language writes it. */
    val nativeName: String
) {
    ENGLISH("en-IN", "English", "English"),
    HINDI("hi-IN", "Hindi", "हिन्दी");

    val locale: Locale get() = Locale.forLanguageTag(tag)

    companion object {
        val DEFAULT = ENGLISH

        fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag } ?: DEFAULT
    }
}
