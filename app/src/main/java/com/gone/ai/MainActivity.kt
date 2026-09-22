package com.gone.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.gone.ai.ui.navigation.AppNavigation
import com.gone.ai.ui.navigation.AskRequest
import com.gone.ai.ui.navigation.SharedContent
import android.net.Uri
import androidx.core.content.IntentCompat
import com.gone.ai.ui.theme.GoneTheme
import com.gone.ai.viewmodel.ThemeViewModel

class MainActivity : ComponentActivity() {

    companion object {
        /** Intent extra naming a screen to open, e.g. from Circle Learn's "open in app". */
        const val EXTRA_ROUTE = "route"

        /** Title and text of a document to discuss in a new chat, e.g. a Circle Learn answer. */
        const val EXTRA_ASK_TITLE = "ask_title"
        const val EXTRA_ASK_TEXT = "ask_text"
    }

    private val themeViewModel: ThemeViewModel by viewModels()

    /** A route requested by the launching intent, consumed once navigation is ready. */
    private var pendingRoute by mutableStateOf<String?>(null)

    /** A document to open a chat about, consumed once navigation is ready. */
    private var pendingAsk by mutableStateOf<AskRequest?>(null)

    /** Something shared into G-one from another app, consumed once navigation is ready. */
    private var pendingShare by mutableStateOf<SharedContent?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Alerts degrade to "recorded but not shown" if this is denied. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.gone.ai.health.emergency.EmergencySync.request(applicationContext)

        if (savedInstanceState == null) {
            pendingRoute = intent?.getStringExtra(EXTRA_ROUTE)
            pendingAsk = intent?.let(::askRequestOf)
            pendingShare = intent?.let(::sharedContentOf)
            requestNotificationPermission()
        }

        // Allow content to draw behind system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        setContent {
            val isDarkTheme by themeViewModel.isDarkTheme.collectAsState()
            // System bar icons follow the app's theme, not the phone's dark mode; otherwise a light
            // app on a dark-mode phone gets white status bar icons on a white background.
            DisposableEffect(isDarkTheme) {
                val transparent = android.graphics.Color.TRANSPARENT
                val style = if (isDarkTheme) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            GoneTheme(darkTheme = isDarkTheme) {
                AppNavigation(
                    isDarkTheme = isDarkTheme,
                    onToggleTheme = themeViewModel::toggleTheme,
                    pendingRoute = pendingRoute,
                    onRouteHandled = { pendingRoute = null },
                    pendingAsk = pendingAsk,
                    onAskHandled = { pendingAsk = null },
                    pendingShare = pendingShare,
                    onShareHandled = { pendingShare = null }
                )
            }
        }
    }

    /** Circle Learn relaunches with FLAG_ACTIVITY_SINGLE_TOP, which lands here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_ROUTE)?.let { pendingRoute = it }
        askRequestOf(intent)?.let { pendingAsk = it }
        sharedContentOf(intent)?.let { pendingShare = it }
    }

    /** A PDF, image or text sent with the Android share sheet; anything else is ignored. */
    private fun sharedContentOf(intent: Intent): SharedContent? {
        if (intent.action != Intent.ACTION_SEND) return null
        val type = intent.type ?: return null
        val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        return when {
            type == "application/pdf" && stream != null -> SharedContent.Pdf(stream)
            type.startsWith("image/") && stream != null -> SharedContent.Image(stream)
            type.startsWith("text/") -> intent.getStringExtra(Intent.EXTRA_TEXT)
                ?.takeIf { it.isNotBlank() }
                ?.let { text ->
                    val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() }
                    SharedContent.Text(subject ?: "Shared text", text)
                }
            else -> null
        }
    }

    private fun askRequestOf(intent: Intent): AskRequest? {
        val text = intent.getStringExtra(EXTRA_ASK_TEXT)?.takeIf { it.isNotBlank() } ?: return null
        return AskRequest(intent.getStringExtra(EXTRA_ASK_TITLE) ?: "Document", text)
    }

    /**
     * Only notifications are requested at launch: health alerts are the app's core job.
     *
     * Bluetooth and microphone used to be requested here too, before onboarding had even
     * explained why. Each is now requested where it is used — onboarding's device step,
     * monitoring start, and the voice screen.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
