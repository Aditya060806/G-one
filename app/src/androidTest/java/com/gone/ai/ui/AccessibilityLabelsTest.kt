package com.gone.ai.ui

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gone.ai.ai.state.AIInferenceState
import com.gone.ai.health.ui.HealthDashboardScreen
import com.gone.ai.health.ui.LiveMonitorScreen
import com.gone.ai.health.ui.TrailsScreen
import com.gone.ai.ocr.ResultStatus
import com.gone.ai.ocr.TaskStatus
import com.gone.ai.ui.screens.DocumentResultSection
import com.gone.ai.ui.screens.LibraryScreen
import com.gone.ai.ui.screens.OnboardingScreen
import com.gone.ai.ui.screens.ResultActions
import com.gone.ai.ui.screens.SettingsBodyContent
import com.gone.ai.ui.screens.ToolsScreen
import com.gone.ai.ui.theme.GoneTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every control TalkBack can reach must say what it is.
 *
 * Renders the main screens in both themes and fails on any clickable element whose merged
 * semantics have neither text nor a content description, which TalkBack reads as just
 * "Button". Chat and voice are left out: opening them loads the language model.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityLabelsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var dark = false

    /** Renders [content] once (a compose rule allows one setContent per test) and checks it. */
    private fun check(name: String, content: @Composable () -> Unit) {
        // A locked or sleeping phone never brings the test screen forward, and Compose then finds
        // nothing to check. Show it over the lock screen instead of needing the phone unlocked.
        compose.activity.runOnUiThread {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                compose.activity.setShowWhenLocked(true)
                compose.activity.setTurnScreenOn(true)
            }
        }
        compose.setContent { GoneTheme(darkTheme = dark) { content() } }
        // Moving over the lock screen takes a moment on the first test of a run.
        compose.waitUntil(timeoutMillis = 15_000) {
            runCatching { compose.onAllNodes(isRoot()).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        }
        compose.waitForIdle()
        val unlabelled = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().filterNot(::hasLabel)
        assertTrue(
            "$name: ${unlabelled.size} control(s) without a label at ${unlabelled.map { it.boundsInRoot }}",
            unlabelled.isEmpty()
        )
    }

    private fun hasLabel(node: SemanticsNode): Boolean {
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()
        return text.isNotBlank() || description.isNotBlank()
    }

    @Test
    fun tools() = check("Tools") { ToolsScreen(isDarkTheme = dark, bottomPadding = 0.dp) }

    @Test
    fun settings() = check("Settings") {
        SettingsBodyContent(isDarkTheme = dark, aiState = AIInferenceState.Idle, onToggleTheme = {})
    }

    @Test
    fun onboarding() = check("Onboarding") { OnboardingScreen(isDarkTheme = dark, onFinish = {}) }

    @Test
    fun vault() = check("Vault") { LibraryScreen(isDarkTheme = dark, bottomPadding = 0.dp, onOpenEntry = {}) }

    @Test
    fun home() = check("Home") {
        HealthDashboardScreen(
            isDarkTheme = dark, bottomPadding = 0.dp,
            onOpenAlerts = {}, onOpenMonitor = {}, onOpenHistory = {}, onOpenTools = {}, onOpenSettings = {}
        )
    }

    @Test
    fun liveMonitor() = check("Live Monitor") { LiveMonitorScreen(isDarkTheme = dark, bottomPadding = 0.dp) }

    @Test
    fun trailsAlerts() = check("Trails, first tab") { TrailsScreen(isDarkTheme = dark, bottomPadding = 0.dp, initialTab = 0) }

    @Test
    fun trailsSecondTab() = check("Trails, second tab") { TrailsScreen(isDarkTheme = dark, bottomPadding = 0.dp, initialTab = 1) }

    @Test
    fun documentResult() = check("Document result") {
        DocumentResultSection(
            text = "## Summary\n- A finished result",
            status = TaskStatus.Finished(ResultStatus.DONE),
            saved = false,
            isDarkTheme = dark,
            onStop = {},
            onRetry = {},
            actions = ResultActions(shareSubject = "Summary", onSave = {}, onAskAssistant = {})
        )
    }

    @Test
    fun toolsDark() {
        dark = true
        check("Tools, dark") { ToolsScreen(isDarkTheme = true, bottomPadding = 0.dp) }
    }

    @Test
    fun homeDark() {
        dark = true
        check("Home, dark") {
            HealthDashboardScreen(
                isDarkTheme = true, bottomPadding = 0.dp,
                onOpenAlerts = {}, onOpenMonitor = {}, onOpenHistory = {}, onOpenTools = {}, onOpenSettings = {}
            )
        }
    }
}
