package com.infinity.ai.ui.navigation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.*
import com.infinity.ai.ui.theme.GoneMotion
import com.infinity.ai.health.ui.AlertsScreen
import com.infinity.ai.health.ui.HealthDashboardScreen
import com.infinity.ai.health.ui.HealthHistoryScreen
import com.infinity.ai.health.ui.LiveMonitorScreen
import com.infinity.ai.ui.components.toOrbState
import com.infinity.ai.ui.screens.*
import com.infinity.ai.viewmodel.ChatViewModel
import com.infinity.ai.viewmodel.LibraryViewModel

/**
 * Bottom-navigation destinations.
 *
 * Health-first ordering: the four monitoring surfaces come before the assistant, because
 * G-one is a health companion that happens to contain an assistant, not the reverse.
 *
 * Tools, Library and Settings are intentionally NOT in the bottom bar — five tabs is the
 * practical ceiling before labels truncate. They are reachable in one tap from the
 * Dashboard header instead, which keeps the bar focused on real-time health status.
 */
sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Health    : Screen("health",  "Health",  Icons.Default.Favorite)
    object Monitor   : Screen("monitor", "Monitor", Icons.Default.MonitorHeart)
    object History   : Screen("history", "History", Icons.AutoMirrored.Filled.ShowChart)
    object Alerts    : Screen("alerts",  "Alerts",  Icons.Default.NotificationsActive)
    object Assistant : Screen("chat",    "Assist",  Icons.AutoMirrored.Filled.Chat)
}

private val navItems =
    listOf(Screen.Health, Screen.Monitor, Screen.History, Screen.Alerts, Screen.Assistant)

/** Routes that keep the bottom bar visible. Everything else is a pushed detail screen. */
private val navRoutes = navItems.map { it.route }

/**
 * Two different transitions, because the two navigation gestures mean different things.
 *
 * Switching bottom-nav tabs is lateral movement between peers, so it gets a fade-through
 * (fade plus a slight scale) with no directional slide — sliding implies an ordering the
 * tabs do not have, and picking a direction would be arbitrary. Opening a detail screen
 * is a push deeper, so it slides in from the right and back out to the right, which is
 * what makes the back gesture feel like reversal rather than another forward step.
 */
private fun fadeThroughIn() =
    fadeIn(tween(GoneMotion.Medium, delayMillis = 50, easing = GoneMotion.EaseOutSoft)) +
        scaleIn(
            initialScale = 0.97f,
            animationSpec = tween(GoneMotion.Medium, delayMillis = 50, easing = GoneMotion.EaseOutSoft)
        )

private fun fadeThroughOut() = fadeOut(tween(GoneMotion.Quick, easing = LinearEasing))

private fun pushIn() = slideInHorizontally(
    animationSpec = tween(GoneMotion.Medium, easing = GoneMotion.EaseOutSoft)
) { full -> full / 4 } + fadeIn(tween(GoneMotion.Medium))

private fun pushOutBack() = slideOutHorizontally(
    animationSpec = tween(GoneMotion.Medium, easing = GoneMotion.EaseInOutSoft)
) { full -> full / 4 } + fadeOut(tween(GoneMotion.Quick))

/**
 * A pushed detail destination: slides in from the right, and on back slides back out the
 * same way. Going *deeper* from here only fades, so a three-level push does not turn into
 * a conveyor belt of sliding panels.
 */
private fun NavGraphBuilder.detailScreen(
    route: String,
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit
) = composable(
    route = route,
    enterTransition    = { pushIn() },
    exitTransition     = { fadeThroughOut() },
    popEnterTransition = { fadeThroughIn() },
    popExitTransition  = { pushOutBack() },
    content            = content
)

@Composable
fun AppNavigation(isDarkTheme: Boolean, onToggleTheme: () -> Unit) {
    val navController = rememberNavController()
    val backStack     by navController.currentBackStackEntryAsState()
    val currentRoute  = backStack?.destination?.route
    val showNav       = currentRoute in navRoutes

    val context = LocalContext.current
    val chatViewModel: ChatViewModel = viewModel()
    val libraryViewModel: LibraryViewModel = viewModel()
    val aiState by chatViewModel.aiState.collectAsState()
    val orbState = aiState.toOrbState()

    // ── Mic permission + SpeechRecognizer ─────────────────────────────────────
    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* permission result handled silently; VoiceScreen reacts to aiState */ }

    val speechRecognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context))
            SpeechRecognizer.createSpeechRecognizer(context)
        else null
    }
    DisposableEffect(Unit) { onDispose { speechRecognizer?.destroy() } }

    val startListening: () -> Unit = {
        val hasMic = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            speechRecognizer?.let { sr ->
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
                sr.setRecognitionListener(object : android.speech.RecognitionListener {
                    override fun onReadyForSpeech(p: android.os.Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(v: Float) {}
                    override fun onBufferReceived(b: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(code: Int) {}
                    override fun onResults(results: android.os.Bundle?) {
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull() ?: return
                        chatViewModel.startFromSuggestion(text)
                    }
                    override fun onPartialResults(partial: android.os.Bundle?) {}
                    override fun onEvent(type: Int, params: android.os.Bundle?) {}
                })
                sr.startListening(intent)
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showNav) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                    windowInsets   = WindowInsets.navigationBars
                ) {
                    navItems.forEach { screen ->
                        val selected = currentRoute == screen.route
                        NavigationBarItem(
                            selected = selected,
                            onClick  = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState    = true
                                }
                            },
                            icon  = { Icon(screen.icon, screen.label, modifier = Modifier.size(22.dp)) },
                            label = { Text(screen.label, style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor   = Color(0xFF4F8CFF),
                                selectedTextColor   = Color(0xFF4F8CFF),
                                indicatorColor      = Color(0xFF4F8CFF).copy(alpha = 0.10f),
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController    = navController,
            startDestination = "splash",
            // Tab-level default. Detail routes override these below with a push.
            enterTransition     = { fadeThroughIn() },
            exitTransition      = { fadeThroughOut() },
            popEnterTransition  = { fadeThroughIn() },
            popExitTransition   = { fadeThroughOut() }
        ) {
            composable("splash") {
                SplashScreen(isDarkTheme = isDarkTheme) {
                    navController.navigate(Screen.Health.route) {
                        popUpTo("splash") { inclusive = true }
                    }
                }
            }

            // ── Health surfaces ───────────────────────────────────────────────
            composable(Screen.Health.route) {
                HealthDashboardScreen(
                    isDarkTheme    = isDarkTheme,
                    bottomPadding  = innerPadding.calculateBottomPadding(),
                    onOpenAlerts   = { navController.navigate(Screen.Alerts.route) },
                    onOpenMonitor  = { navController.navigate(Screen.Monitor.route) },
                    onOpenHistory  = { navController.navigate(Screen.History.route) },
                    onOpenTools    = { navController.navigate("tools") },
                    onOpenSettings = { navController.navigate("settings") }
                )
            }
            composable(Screen.Monitor.route) {
                LiveMonitorScreen(
                    isDarkTheme   = isDarkTheme,
                    bottomPadding = innerPadding.calculateBottomPadding()
                )
            }
            composable(Screen.History.route) {
                HealthHistoryScreen(
                    isDarkTheme   = isDarkTheme,
                    bottomPadding = innerPadding.calculateBottomPadding()
                )
            }
            composable(Screen.Alerts.route) {
                AlertsScreen(
                    isDarkTheme   = isDarkTheme,
                    bottomPadding = innerPadding.calculateBottomPadding()
                )
            }

            // ── Inherited assistant surfaces ──────────────────────────────────
            composable(Screen.Assistant.route) {
                ChatScreen(
                    isDarkTheme       = isDarkTheme,
                    bottomPadding     = innerPadding.calculateBottomPadding(),
                    onNavigateToVoice = { navController.navigate("voice") },
                    chatViewModel     = chatViewModel
                )
            }
            detailScreen("tools") {
                ToolsScreen(
                    isDarkTheme            = isDarkTheme,
                    bottomPadding          = innerPadding.calculateBottomPadding(),
                    onNavigateToPdf        = { navController.navigate("pdf_summary") },
                    onNavigateToOcr        = { navController.navigate("ocr") },
                    onNavigateToScreenshot = { navController.navigate("screenshot") },
                    onNavigateToQuiz       = { navController.navigate("quiz") },
                    onNavigateToCircle     = { navController.navigate("circle_learn") },
                    onNavigateToLibrary    = { navController.navigate("library") }
                )
            }
            detailScreen("pdf_summary") {
                PdfSummaryScreen(
                    isDarkTheme    = isDarkTheme,
                    bottomPadding  = innerPadding.calculateBottomPadding(),
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            detailScreen("ocr") {
                OcrScreen(
                    isDarkTheme    = isDarkTheme,
                    bottomPadding  = innerPadding.calculateBottomPadding(),
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            detailScreen("screenshot") {
                ScreenshotExplainerScreen(
                    isDarkTheme    = isDarkTheme,
                    bottomPadding  = innerPadding.calculateBottomPadding(),
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            detailScreen("quiz") {
                QuizScreen(
                    isDarkTheme    = isDarkTheme,
                    bottomPadding  = innerPadding.calculateBottomPadding(),
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            detailScreen("circle_learn") {
                CircleLearnEntryScreen(
                    isDarkTheme    = isDarkTheme,
                    bottomPadding  = innerPadding.calculateBottomPadding(),
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            detailScreen("settings") {
                SettingsScreen(
                    isDarkTheme   = isDarkTheme,
                    bottomPadding = innerPadding.calculateBottomPadding(),
                    aiState       = aiState,
                    onToggleTheme = onToggleTheme
                )
            }
            detailScreen("library") {
                LibraryScreen(
                    isDarkTheme   = isDarkTheme,
                    bottomPadding = innerPadding.calculateBottomPadding(),
                    onOpenEntry   = { /* detail view future */ },
                    vm            = libraryViewModel
                )
            }
            detailScreen("voice") {
                VoiceScreen(
                    isDarkTheme    = isDarkTheme,
                    orbState       = orbState,
                    aiState        = aiState,
                    onSetListening = startListening,
                    onSetIdle      = {
                        speechRecognizer?.stopListening()
                        chatViewModel.stopGeneration()
                    },
                    onDismiss      = { navController.popBackStack() }
                )
            }
        }
    }
}
