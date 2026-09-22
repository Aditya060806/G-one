package com.gone.ai.health.ui

import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gone.ai.ui.theme.GoneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveMonitorLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun checkHeader(dark: Boolean, fontScale: Float) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GoneTheme(darkTheme = dark) {
                    LiveMonitorScreen(isDarkTheme = dark, bottomPadding = 0.dp)
                }
            }
        }
        val header = compose.onNodeWithText("Live Monitor", substring = true)
        header.assertIsDisplayed()
        val before = header.fetchSemanticsNode().boundsInRoot
        val scroll = compose.onNode(hasScrollAction())
        assertTrue(scroll.fetchSemanticsNode().boundsInRoot.top >= before.bottom)
        scroll.performTouchInput { swipeUp() }
        compose.waitForIdle()
        val after = header.fetchSemanticsNode().boundsInRoot
        assertEquals(before.top, after.top, 0.5f)
        header.assertIsDisplayed()
        compose.onNodeWithText("Skin Temp").performScrollTo().assertIsDisplayed()
        assertTrue(scroll.fetchSemanticsNode().boundsInRoot.top >= after.bottom)
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(compose.activity.getExternalFilesDir(null), "live-monitor-$dark-$fontScale.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun headerRemainsAboveScrollInLightTheme() = checkHeader(false, 1f)
    @Test fun headerRemainsAboveScrollWithLargeDarkText() = checkHeader(true, 1.5f)
}
