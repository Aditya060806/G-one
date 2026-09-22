package com.gone.ai.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat

/** Helpers for permissions the person has turned off for good. */
object AppSettings {

    /** Opens this app's page in system Settings, where a permission can be switched back on. */
    fun open(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * True when Android will no longer show the permission dialog for [permission]: the
     * person denied it twice, or chose "Don't allow" with no further prompts. Call right
     * after a denial; only then does a false rationale mean "blocked".
     */
    fun isBlockedAfterDenial(context: Context, permission: String): Boolean {
        val activity = context.findActivity() ?: return false
        return !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
