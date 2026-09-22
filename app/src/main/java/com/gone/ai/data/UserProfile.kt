package com.gone.ai.data

import android.content.Context
import androidx.core.content.edit

/**
 * The profile collected during onboarding, read in one place.
 *
 * Every field is nullable and blank values read as null, so a screen shows "not set"
 * instead of inventing a person. The dashboard and Settings used to fall back to
 * hardcoded placeholder names and ages.
 */
data class UserProfile(
    val name: String?,
    val age: Int?,
    val emergencyContact: String?,
    val connectedDeviceName: String?
) {
    companion object {
        const val PREFS_NAME = "gone_preferences"

        fun load(context: Context): UserProfile {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            fun text(key: String): String? = prefs.getString(key, null)?.trim()?.takeIf { it.isNotEmpty() }
            return UserProfile(
                name = text("user_name"),
                age = if (prefs.contains("user_age")) prefs.getInt("user_age", 0).takeIf { it > 0 } else null,
                emergencyContact = text("emergency_contact") ?: text("family_contact"),
                connectedDeviceName = text("connected_device_name")
            )
        }

        /**
         * Saves the fields Settings edits. The contact is written under both keys onboarding
         * uses, so SOS and the alert banner agree.
         */
        fun save(context: Context, name: String, age: Int?, emergencyContact: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putString("user_name", name)
                if (age != null) putInt("user_age", age) else remove("user_age")
                putString("emergency_contact", emergencyContact)
                putString("family_contact", emergencyContact)
            }
            com.gone.ai.health.emergency.EmergencySync.request(context)
        }
    }
}
