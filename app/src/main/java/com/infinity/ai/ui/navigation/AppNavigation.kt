package com.infinity.ai.ui.navigation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset
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
import com.infinity.ai.health.ui.HealthDashboardScreen
import com.infinity.ai.health.ui.HealthViewModel
import com.infinity.ai.health.ui.InAppAlertStack
import com.infinity.ai.health.ui.LiveMonitorScreen
import com.infinity.ai.health.ui.TrailsScreen
import com.infinity.ai.ui.components.toOrbState
import com.infinity.ai.ui.screens.*
import com.infinity.ai.viewmodel.ChatViewModel
import com.infinity.ai.viewmodel.LibraryViewModel

/**
 * Bottom-navigation destinations.
 *
 * 5 primary tabs: Health (Dashboard), Monitor (Live), Trails (Combined History & Alerts),
 * Tools (AI capabilities hub), and Assistant (Chat).
 */
sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Health    : Screen("health",  "Health",  Icons.Default.Favorite)
    object Monitor   : Screen("monitor", "Monitor", Icons.Default.MonitorHeart)
    object Trails    : Screen("trails",  "Trails",  Icons.AutoMirrored.Filled.ShowChart)
    object Tools     : Screen("tools",   "Tools",   Icons.Default.Apps)
    object Assistant : Screen("chat",    "Assist",  Icons.AutoMirrored.Filled.Chat)
}

private val navItems =
    listOf(Screen.Health, Screen.Monitor, Screen.Trails, Screen.Tools, Screen.Assistant)

/** Routes that keep the bottom bar visible. Everything else is a pushed detail screen. */
private val navRoutes = navItems.map { it.route }

/**
 * Navigation transitions:
 *
 * 1. Bottom-nav tab switching behaves like a directional carousel:
 *    Tabs are ordered: Health (0), Monitor (1), Trails (2), Tools (3), Assistant (4).
 *    - Moving right (targetIndex > initialIndex): new tab slides in from right, old slides out to left.
 *    - Moving left (targetIndex < initialIndex): new tab slides in from left, old slides out to right.
 *    - Uses a spring animation (damping = 0.85f, stiffness = Spring.StiffnessMediumLow).
 *
 * 2. Detail screen push/pop:
 *    - Pushing a detail screen slides in from right (pushIn) while current tab fades out (fadeThroughOut).
 *    - Popping back slides out to right (pushOutBack) while destination tab fades in (fadeThroughIn).
 */
private fun tabIndex(route: String?): Int? = when (route) {
    Screen.Health.route    -> 0
    Screen.Monitor.route   -> 1
    Screen.Trails.route    -> 2
    Screen.Tools.route     -> 3
    Screen.Assistant.route -> 4
    else -> null
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabEnterTransition(): EnterTransition {
    val initialIndex = tabIndex(initialState.destination.route)
    val targetIndex = tabIndex(targetState.destination.route)
    return when {
        initialIndex != null && targetIndex != null -> {
            val slideSpec = tween<IntOffset>(durationMillis = 260, easing = FastOutSlowInEasing)
            val fadeSpec = tween<Float>(durationMillis = 200, easing = FastOutSlowInEasing)
            if (targetIndex > initialIndex) {
                slideInHorizontally(animationSpec = slideSpec) { width -> (width * 0.35f).toInt() } + fadeIn(animationSpec = fadeSpec)
            } else if (targetIndex < initialIndex) {
                slideInHorizontally(animationSpec = slideSpec) { width -> (-width * 0.35f).toInt() } + fadeIn(animationSpec = fadeSpec)
            } else {
                fadeThroughIn()
            }
        }
        else -> fadeThroughIn()
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabExitTransition(): ExitTransition {
    val initialIndex = tabIndex(initialState.destination.route)
    val targetIndex = tabIndex(targetState.destination.route)
    return when {
        initialIndex != null && targetIndex != null -> {
            val slideSpec = tween<IntOffset>(durationMillis = 260, easing = FastOutSlowInEasing)
            val fadeSpec = tween<Float>(durationMillis = 180, easing = FastOutSlowInEasing)
            if (targetIndex > initialIndex) {
                slideOutHorizontally(animationSpec = slideSpec) { width -> (-width * 0.35f).toInt() } + fadeOut(animationSpec = fadeSpec)
            } else if (targetIndex < initialIndex) {
                slideOutHorizontally(animationSpec = slideSpec) { width -> (width * 0.35f).toInt() } + fadeOut(animationSpec = fadeSpec)
            } else {
                fadeThroughOut()
            }
        }
        else -> fadeThroughOut()
    }
}

private fun fadeThroughIn() = fadeIn(
    animationSpec = tween(280, easing = FastOutSlowInEasing)
) + scaleIn(
    initialScale = 0.96f,
    animationSpec = tween(280, easing = FastOutSlowInEasing)
)

private fun fadeThroughOut() = fadeOut(
    animationSpec = tween(220, easing = FastOutSlowInEasing)
) + scaleOut(
    targetScale = 1.02f,
    animationSpec = tween(220, easing = FastOutSlowInEasing)
)

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppNavigation(isDarkTheme: Boolean, onToggleTheme: () -> Unit) {
    val navController = rememberNavController()
    val backStack     by navController.currentBackStackEntryAsState()
    val currentRoute  = backStack?.destination?.route
    val isImeVisible  = WindowInsets.isImeVisible
    val showNav       = currentRoute in navRoutes && !isImeVisible

    val context = LocalContext.current
    val chatViewModel: ChatViewModel = viewModel()
    val libraryViewModel: LibraryViewModel = viewModel()
    val healthViewModel: HealthViewModel = viewModel()
    val aiState by chatViewModel.aiState.collectAsState()
    val activeEvents by healthViewModel.activeEvents.collectAsState()
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

    var showSettingsSheet by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            AnimatedVisibility(
                visible = showNav,
                enter = androidx.compose.animation.slideInVertically(animationSpec = tween(220)) { it } + fadeIn(tween(180)),
                exit = androidx.compose.animation.slideOutVertically(animationSpec = tween(180)) { it } + fadeOut(tween(150))
            ) {
                com.infinity.ai.ui.components.FloatingIslandNav(
                    items        = navItems,
                    currentRoute = currentRoute,
                    onNavigate   = { screen ->
                        if (currentRoute != screen.route) {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState    = true
                            }
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController       = navController,
                startDestination    = "splash",
                // Carousel transitions between navbar tabs; fade-through fallback for details/splash
                enterTransition     = { tabEnterTransition() },
                exitTransition      = { tabExitTransition() },
                popEnterTransition  = { tabEnterTransition() },
                popExitTransition   = { tabExitTransition() }
            ) {
                composable("splash") {
                    SplashScreen(isDarkTheme = isDarkTheme) {
                        val prefs = context.getSharedPreferences("gone_preferences", android.content.Context.MODE_PRIVATE)
                        val isReboardingDone = prefs.getBoolean("reboarding_v2_completed", false)
                        if (isReboardingDone) {
                            navController.navigate(Screen.Health.route) {
                                popUpTo("splash") { inclusive = true }
                            }
                        } else {
                            navController.navigate("onboarding") {
                                popUpTo("splash") { inclusive = true }
                            }
                        }
                    }
                }

                composable("onboarding") {
                    OnboardingScreen(
                        isDarkTheme = isDarkTheme,
                        onToggleTheme = onToggleTheme,
                        onFinish = {
                            navController.navigate(Screen.Health.route) {
                                popUpTo("onboarding") { inclusive = true }
                            }
                        }
                    )
                }

                // ── Health surfaces ───────────────────────────────────────────────
                composable(Screen.Health.route) {
                    HealthDashboardScreen(
                        isDarkTheme            = isDarkTheme,
                        bottomPadding          = innerPadding.calculateBottomPadding(),
                        onOpenAlerts           = { navController.navigate(Screen.Trails.route) },
                        onOpenMonitor          = { navController.navigate(Screen.Monitor.route) },
                        onOpenHistory          = { navController.navigate(Screen.Trails.route) },
                        onOpenTools            = { navController.navigate(Screen.Tools.route) },
                        onOpenSettings         = { showSettingsSheet = true },
                        onOpenAssist           = { navController.navigate(Screen.Assistant.route) },
                        onNavigateToPdf        = { navController.navigate("pdf_summary") },
                        onNavigateToOcr        = { navController.navigate("ocr") },
                        onNavigateToScreenshot = { navController.navigate("screenshot") },
                        onNavigateToQuiz       = { navController.navigate("quiz") },
                        vm                     = healthViewModel
                    )
                }
                composable(Screen.Monitor.route) {
                    LiveMonitorScreen(
                        isDarkTheme        = isDarkTheme,
                        bottomPadding      = innerPadding.calculateBottomPadding(),
                        onNavigateHome     = { navController.navigate(Screen.Health.route) },
                        onOpenAlerts       = { navController.navigate(Screen.Trails.route) },
                        onNavigateToTrails = { navController.navigate(Screen.Trails.route) },
                        vm                 = healthViewModel
                    )
                }
                composable(Screen.Trails.route) {
                    TrailsScreen(
                        isDarkTheme    = isDarkTheme,
                        bottomPadding  = innerPadding.calculateBottomPadding(),
                        onNavigateHome = { navController.navigate(Screen.Health.route) },
                        vm             = healthViewModel
                    )
                }

                // ── Tools surface (Main Tab) ───────────────────────────────────────
                composable(Screen.Tools.route) {
                    ToolsScreen(
                        isDarkTheme            = isDarkTheme,
                        bottomPadding          = innerPadding.calculateBottomPadding(),
                        onNavigateHome         = { navController.navigate(Screen.Health.route) },
                        onNavigateToPdf        = { navController.navigate("pdf_summary") },
                        onNavigateToOcr        = { navController.navigate("ocr") },
                        onNavigateToScreenshot = { navController.navigate("screenshot") },
                        onNavigateToQuiz       = { navController.navigate("quiz") },
                        onNavigateToCircle     = { navController.navigate("circle_learn") },
                        onNavigateToLibrary    = { navController.navigate("library") },
                        onNavigateToSettings   = { showSettingsSheet = true }
                    )
                }

                // ── Assistant surface ─────────────────────────────────────────────
                composable(Screen.Assistant.route) {
                    ChatScreen(
                        isDarkTheme       = isDarkTheme,
                        bottomPadding     = innerPadding.calculateBottomPadding(),
                        onNavigateHome    = { navController.navigate(Screen.Health.route) },
                        onNavigateToVoice = { navController.navigate("voice") },
                        chatViewModel     = chatViewModel
                    )
                }
                detailScreen("pdf_summary") {
                    PdfSummaryScreen(
                        isDarkTheme    = isDarkTheme,
                        bottomPadding  = innerPadding.calculateBottomPadding(),
                        onNavigateBack = { navController.popBackStack() },
                        onNavigateHome = { navController.navigate(Screen.Health.route) }
                    )
                }
                detailScreen("ocr") {
                    OcrScreen(
                        isDarkTheme    = isDarkTheme,
                        bottomPadding  = innerPadding.calculateBottomPadding(),
                        onNavigateBack = { navController.popBackStack() },
                        onNavigateHome = { navController.navigate(Screen.Health.route) }
                    )
                }
                detailScreen("screenshot") {
                    ScreenshotExplainerScreen(
                        isDarkTheme    = isDarkTheme,
                        bottomPadding  = innerPadding.calculateBottomPadding(),
                        onNavigateBack = { navController.popBackStack() },
                        onNavigateHome = { navController.navigate(Screen.Health.route) }
                    )
                }
                detailScreen("quiz") {
                    QuizScreen(
                        isDarkTheme    = isDarkTheme,
                        bottomPadding  = innerPadding.calculateBottomPadding(),
                        onNavigateBack = { navController.popBackStack() },
                        onNavigateHome = { navController.navigate(Screen.Health.route) }
                    )
                }
                detailScreen("circle_learn") {
                    CircleLearnEntryScreen(
                        isDarkTheme    = isDarkTheme,
                        bottomPadding  = innerPadding.calculateBottomPadding(),
                        onNavigateBack = { navController.popBackStack() },
                        onNavigateHome = { navController.navigate(Screen.Health.route) }
                    )
                }
                detailScreen("settings") {
                    SettingsScreen(
                        isDarkTheme        = isDarkTheme,
                        bottomPadding      = innerPadding.calculateBottomPadding(),
                        aiState            = aiState,
                        onToggleTheme      = onToggleTheme,
                        onNavigateHome     = { navController.navigate(Screen.Health.route) },
                        onRedoOnboarding   = { navController.navigate("onboarding") }
                    )
                }
                detailScreen("library") {
                    LibraryScreen(
                        isDarkTheme    = isDarkTheme,
                        bottomPadding  = innerPadding.calculateBottomPadding(),
                        onOpenEntry    = { /* detail view future */ },
                        onNavigateBack = { navController.popBackStack() },
                        onNavigateHome = { navController.navigate(Screen.Health.route) },
                        vm             = libraryViewModel
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

            if (currentRoute != "splash" && currentRoute != "onboarding") {
                InAppAlertStack(
                    events = activeEvents,
                    isDarkTheme = isDarkTheme,
                    onAcknowledge = { healthViewModel.acknowledge(it) },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }

    if (showSettingsSheet) {
        SettingsBottomSheet(
            isDarkTheme      = isDarkTheme,
            aiState          = aiState,
            onToggleTheme    = onToggleTheme,
            onDismiss        = { showSettingsSheet = false },
            onRedoOnboarding = {
                showSettingsSheet = false
                navController.navigate("onboarding")
            }
        )
    }
}
