package com.gone.ai.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "infinity_prefs")

class ThemePreference(private val context: Context) {
    companion object {
        val DARK_THEME_KEY = booleanPreferencesKey("dark_theme")
    }

    /**
     * Defaults to LIGHT.
     *
     * A health companion is read in daylight far more often than in bed, and the light
     * theme is where the vitals and charts are most legible. Anyone who prefers dark
     * still gets it from Settings, and their choice persists here.
     *
     * The key name stays `dark_theme` even though the default flipped — renaming it would
     * silently discard the preference of anyone who had already chosen a theme.
     */
    val isDarkTheme: Flow<Boolean> = context.dataStore.data
        .map { it[DARK_THEME_KEY] ?: true }

    suspend fun setDarkTheme(isDark: Boolean) {
        context.dataStore.edit { it[DARK_THEME_KEY] = isDark }
    }
}
