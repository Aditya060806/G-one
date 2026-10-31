package com.gone.ai.voice

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The wearer's language, and whether alerts are read aloud.
 *
 * Observable, so Settings, the voice screen and "Ask your health" change together. Choosing a
 * language also switches the shared voice at once, so the next sentence is spoken in it.
 */
object LanguagePreferences {

    private const val PREFS = "gone_language"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_READ_ALERTS = "read_alerts_aloud"

    @Volatile private var language: MutableStateFlow<AppLanguage>? = null
    @Volatile private var readAlerts: MutableStateFlow<Boolean>? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun languageFlow(context: Context) = language ?: synchronized(this) {
        language ?: MutableStateFlow(AppLanguage.fromTag(prefs(context).getString(KEY_LANGUAGE, null)))
            .also { language = it }
    }

    private fun readAlertsFlow(context: Context) = readAlerts ?: synchronized(this) {
        readAlerts ?: MutableStateFlow(prefs(context).getBoolean(KEY_READ_ALERTS, false))
            .also { readAlerts = it }
    }

    fun language(context: Context): StateFlow<AppLanguage> = languageFlow(context).asStateFlow()

    fun current(context: Context): AppLanguage = languageFlow(context).value

    fun setLanguage(context: Context, value: AppLanguage) {
        prefs(context).edit { putString(KEY_LANGUAGE, value.tag) }
        languageFlow(context).value = value
        SpeechOutput.getInstance(context).useLanguage(value)
    }

    /** Off by default: a phone that speaks without being asked would surprise people. */
    fun readAlertsAloud(context: Context): StateFlow<Boolean> = readAlertsFlow(context).asStateFlow()

    fun setReadAlertsAloud(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_READ_ALERTS, enabled) }
        readAlertsFlow(context).value = enabled
    }
}
