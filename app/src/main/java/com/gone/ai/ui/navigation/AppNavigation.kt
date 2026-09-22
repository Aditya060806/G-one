package com.gone.ai.ui.navigation

import android.widget.Toast
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.*
import com.gone.ai.ui.theme.GoneMotion
import com.gone.ai.health.ui.DeviceScreen
import com.gone.ai.health.ui.EmergencyIdScreen
import com.gone.ai.health.ui.HealthDashboardScreen
import com.gone.ai.health.ui.HealthViewModel
import com.gone.ai.health.ui.InAppAlertStack
import com.gone.ai.health.ui.LiveMonitorScreen
import com.gone.ai.health.ui.ReportPdfPreviewScreen
import com.gone.ai.health.ui.ReportScreen
import androidx.compose.foundation.layout.Row
import com.gone.ai.health.ui.ReportsScreen
import com.gone.ai.health.ui.TrailsScreen
import com.gone.ai.ui.screens.*
import com.gone.ai.viewmodel.ChatViewModel
import com.gone.ai.viewmodel.LibraryViewModel

/** A document to open a new chat about, requested from outside the navigation graph. */
data class AskRequest(val title: String, val text: String)

/** Content another app shared into G-one. */
sealed interface SharedContent {
    data class Pdf(val uri: android.net.Uri) : SharedContent
    data class Image(val uri: android.net.Uri) : SharedContent
    data class Text(val title: String, val text: String) : SharedContent
}

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

/** Routes another component may ask to open, e.g. Circle Learn's "open in app". */
private val externalRoutes = navRoutes.toSet() +
    setOf("library", "pdf_summary", "ocr", "screenshot", "quiz", "circle_learn", "device", "reports", "emergency_id")

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
fun AppNavigation(
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    pendingRoute: String? = null,
    onRouteHandled: () -> Unit = {},
    pendingAsk: AskRequest? = null,
    onAskHandled: () -> Unit = {},
    pendingShare: SharedContent? = null,
    onShareHandled: () -> Unit = {}
) {
    val navController = rememberNavController()
    val backStack     by navController.currentBackStackEntryAsState()
    val currentRoute  = backStack?.destination?.route
    val isImeVisible  = WindowInsets.isImeVisible
    val showNav       = currentRoute in navRoutes && !isImeVisible

    val context = LocalContext.current
    val chatViewModel: ChatViewModel = viewModel()
    val libraryViewModel: LibraryViewModel = viewModel()
    val healthViewModel: HealthViewModel = viewModel()
    /** Engine-wide state, for the Settings status row. */
    val engineState by chatViewModel.engineState.collectAsState()
    val activeEvents by healthViewModel.activeEvents.collectAsState()

    // One place shows monitoring notices, so a screen transition never shows one twice.
    LaunchedEffect(healthViewModel) {
        healthViewModel.notices.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    // A session just ended with a report: show it.
    LaunchedEffect(healthViewModel) {
        healthViewModel.openReport.collect { navController.navigate("report/$it") }
    }

    // A screen requested from outside the app. Waits until splash/onboarding are done,
    // so the request is not lost when the start destination replaces the back stack.
    LaunchedEffect(pendingRoute, currentRoute) {
        val route = pendingRoute ?: return@LaunchedEffect
        if (currentRoute == null || currentRoute == "splash" || currentRoute == "onboarding") return@LaunchedEffect
        if (route in externalRoutes && route != currentRoute) {
            navController.navigate(route) { launchSingleTop = true }
        }
        onRouteHandled()
    }

    /** Open the Assist tab the same way the bottom bar does. */
    fun openChat() {
        navController.navigate(Screen.Assistant.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    /** "Ask G-one" from a tool: a new conversation about its result. */
    val askAssistant: (String, String) -> Unit = { title, text ->
        chatViewModel.startConversationAbout(title, text)
        openChat()
    }

    // A document handed over from outside, e.g. a Circle Learn answer. Waits for
    // splash/onboarding like [pendingRoute].
    LaunchedEffect(pendingAsk, currentRoute) {
        val ask = pendingAsk ?: return@LaunchedEffect
        if (currentRoute == null || currentRoute == "splash" || currentRoute == "onboarding") return@LaunchedEffect
        askAssistant(ask.title, ask.text)
        onAskHandled()
    }

    // Shared files wait here until their screen has opened and taken them.
    var sharedPdf by remember { mutableStateOf<android.net.Uri?>(null) }
    var sharedImage by remember { mutableStateOf<android.net.Uri?>(null) }
    LaunchedEffect(pendingShare, currentRoute) {
        val share = pendingShare ?: return@LaunchedEffect
        if (currentRoute == null || currentRoute == "splash" || currentRoute == "onboarding") return@LaunchedEffect
        when (share) {
            is SharedContent.Text -> askAssistant(share.title, share.text)
            is SharedContent.Pdf -> {
                sharedPdf = share.uri
                navController.navigate("pdf_summary") { launchSingleTop = true }
            }
            is SharedContent.Image -> {
                sharedImage = share.uri
                navController.navigate("ocr") { launchSingleTop = true }
            }
        }
        onShareHandled()
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
                com.gone.ai.ui.components.FloatingIslandNav(
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
                        onOpenDevice           = { navController.navigate("device") },
                        onOpenEmergencyId      = { navController.navigate("emergency_id") },
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
                        onOpenDevice       = { navController.navigate("device") },
                        onOpenReports      = { navController.navigate("reports") },
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
                        onNavigateToSettings   = { showSettingsSheet = true },
                        onAskAssistant         = askAssistant
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
                        initialUri     = sharedPdf,
                        onInitialUriTaken = { sharedPdf = null },
                        isDarkTheme    = isDarkTheme,
                        bottomPadding  = innerPadding.calculateBottomPadding(),
                        onNavigateBack = { navController.popBackStack() },
                        onAskAssistant = askAssistant,
                        onNavigateHome = { navController.navigate(Screen.Health.route) }
                    )
                }
                detailScreen("ocr") {
                    OcrScreen(
                        initialUri     = sharedImage,
                        onInitialUriTaken = { sharedImage = null },
                        isDarkTheme    = isDarkTheme,
                        bottomPadding  = innerPadding.calculateBottomPadding(),
                        onNavigateBack = { navController.popBackStack() },
                        onAskAssistant = askAssistant,
                        onNavigateHome = { navController.navigate(Screen.Health.route) }
                    )
                }
                detailScreen("screenshot") {
                    ScreenshotExplainerScreen(
                        isDarkTheme    = isDarkTheme,
                        bottomPadding  = innerPadding.calculateBottomPadding(),
                        onNavigateBack = { navController.popBackStack() },
                        onAskAssistant = askAssistant,
                        onNavigateHome = { navController.navigate(Screen.Health.route) }
                    )
                }
                detailScreen("quiz") {
                    QuizScreen(
                        isDarkTheme    = isDarkTheme,
                        bottomPadding  = innerPadding.calculateBottomPadding(),
                        onNavigateBack = { navController.popBackStack() },
                        onAskAssistant = askAssistant,
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
                        aiState            = engineState,
                        onToggleTheme      = onToggleTheme,
                        onNavigateHome     = { navController.navigate(Screen.Health.route) },
                        onRedoOnboarding   = { navController.navigate("onboarding") },
                        onOpenDevice       = { navController.navigate("device") },
                        onOpenReports      = { navController.navigate("reports") },
                        onOpenEmergencyId  = { navController.navigate("emergency_id") }
                    )
                }
                detailScreen("device") {
                    DeviceScreen(
                        isDarkTheme    = isDarkTheme,
                        bottomPadding  = innerPadding.calculateBottomPadding(),
                        onNavigateHome = { navController.navigate(Screen.Health.route) },
                        vm             = healthViewModel
                    )
                }
                detailScreen("reports") {
                    ReportsScreen(
                        isDarkTheme    = isDarkTheme,
                        bottomPadding  = innerPadding.calculateBottomPadding(),
                        onNavigateHome = { navController.navigate(Screen.Health.route) },
                        onOpenReport   = { navController.navigate("report/$it") },
                        vm             = healthViewModel
                    )
                }
                detailScreen("emergency_id") {
                    EmergencyIdScreen(
                        onBack      = { navController.popBackStack() },
                        isDarkTheme = isDarkTheme
                    )
                }
                detailScreen("report/{reportId}") { entry ->
                    val reportId = entry.arguments?.getString("reportId")?.toLongOrNull()
                    if (reportId == null) {
                        LaunchedEffect(Unit) { navController.popBackStack() }
                    } else {
                        ReportScreen(
                            reportId       = reportId,
                            isDarkTheme    = isDarkTheme,
                            bottomPadding  = innerPadding.calculateBottomPadding(),
                            onNavigateHome = { navController.navigate(Screen.Health.route) },
                            onOpenReports  = {
                                if (!navController.popBackStack("reports", inclusive = false)) {
                                    navController.navigate("reports") { popUpTo("reports") { inclusive = true } }
                                }
                            },
                            onDeleted      = { navController.popBackStack() },
                            onOpenPdf      = { navController.navigate("report/$reportId/pdf") },
                            vm             = healthViewModel
                        )
                    }
                }
                detailScreen("report/{reportId}/pdf") { entry ->
                    val reportId = entry.arguments?.getString("reportId")?.toLongOrNull()
                    if (reportId == null) {
                        LaunchedEffect(Unit) { navController.popBackStack() }
                    } else {
                        ReportPdfPreviewScreen(
                            reportId       = reportId,
                            isDarkTheme    = isDarkTheme,
                            bottomPadding  = innerPadding.calculateBottomPadding(),
                            onNavigateHome = { navController.navigate(Screen.Health.route) },
                            onOpenReport   = { navController.popBackStack() },
                            vm             = healthViewModel
                        )
                    }
                }
                detailScreen("library") {
                    LibraryScreen(
                        isDarkTheme    = isDarkTheme,
                        bottomPadding  = innerPadding.calculateBottomPadding(),
                        onOpenEntry    = { navController.navigate("library/$it") },
                        onNavigateBack = { navController.popBackStack() },
                        onNavigateHome = { navController.navigate(Screen.Health.route) },
                        vm             = libraryViewModel
                    )
                }
                detailScreen("library/{entryId}") { entry ->
                    val entryId = entry.arguments?.getString("entryId")?.toLongOrNull()
                    if (entryId == null) {
                        LaunchedEffect(Unit) { navController.popBackStack() }
                    } else {
                        LibraryEntryScreen(
                            entryId        = entryId,
                            isDarkTheme    = isDarkTheme,
                            bottomPadding  = innerPadding.calculateBottomPadding(),
                            onNavigateBack = { navController.popBackStack() },
                            onNavigateHome = { navController.navigate(Screen.Health.route) },
                            onAskAssistant = askAssistant,
                            vm             = libraryViewModel
                        )
                    }
                }
                detailScreen("voice") {
                    VoiceScreen(
                        isDarkTheme   = isDarkTheme,
                        chatViewModel = chatViewModel,
                        onDismiss     = { navController.popBackStack() }
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
                // Over the alerts: while it counts down, stopping it is the one thing that matters.
                com.gone.ai.health.ui.SosCountdownBanner(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }

    val stopPrompt by healthViewModel.stopPrompt.collectAsState()
    if (stopPrompt) {
        AlertDialog(
            onDismissRequest = { healthViewModel.answerStopPrompt(context, endSession = null) },
            title = { Text("A session is running") },
            text = {
                Text(
                    "Stopping monitoring means nothing more is recorded for it. End the session now " +
                        "and make its report, or keep it open to continue later?"
                )
            },
            confirmButton = {
                TextButton(onClick = { healthViewModel.answerStopPrompt(context, endSession = true) }) {
                    Text("End session and stop")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { healthViewModel.answerStopPrompt(context, endSession = null) }) { Text("Cancel") }
                    TextButton(onClick = { healthViewModel.answerStopPrompt(context, endSession = false) }) { Text("Only stop") }
                }
            }
        )
    }

    if (showSettingsSheet) {
        SettingsBottomSheet(
            isDarkTheme      = isDarkTheme,
            aiState          = engineState,
            onToggleTheme    = onToggleTheme,
            onDismiss        = { showSettingsSheet = false },
            onRedoOnboarding = {
                showSettingsSheet = false
                navController.navigate("onboarding")
            },
            onOpenDevice     = {
                showSettingsSheet = false
                navController.navigate("device")
            },
            onOpenReports    = {
                showSettingsSheet = false
                navController.navigate("reports")
            },
            onOpenEmergencyId = {
                showSettingsSheet = false
                navController.navigate("emergency_id")
            }
        )
    }
}
