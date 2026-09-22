package com.gone.ai.circle

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.gone.ai.ui.theme.GoneTheme

/**
 * CircleLearnActivity — FALLBACK ONLY.
 *
 * Used when SYSTEM_ALERT_WINDOW permission is not granted and the overlay
 * pipeline cannot run. In that case InfinityOverlayService captures the
 * screenshot, stores it in [InfinityOverlayService.pendingScreenshot], and
 * launches this transparent activity.
 *
 * Normal flow: everything runs entirely inside InfinityOverlayService via
 * WindowManager overlays and this activity is never launched.
 */
class CircleLearnActivity : ComponentActivity() {

    companion object { private const val TAG = "CircleLearnActivity" }

    private val vm: CircleLearnViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val screenshot = InfinityOverlayService.pendingScreenshot
        if (screenshot == null || screenshot.width <= 1) {
            Log.e(TAG, "No screenshot available")
            showErrorAndFinish("Could not capture screen. Please try again.")
            return
        }

        showSelectionView(screenshot)
    }

    private fun showSelectionView(screenshot: Bitmap) {
        val selectionView = RegionSelectionView(this).apply {
            setScreenshot(screenshot)
            onCancel = { finish() }
            onRegionSelected = { region ->
                setContentView(FrameLayout(this@CircleLearnActivity))
                vm.processRegion(screenshot, region)
                showBottomSheet()
            }
        }
        setContentView(selectionView)
    }

    private fun showBottomSheet() {
        setContent {
            GoneTheme(darkTheme = true) {
                CircleLearnBottomSheetHost(
                    vm          = vm,
                    onDismiss   = { finish() },
                    onOpenInApp = null,  // already inside the app
                    onAskInApp  = { title, text ->
                        startActivity(
                            android.content.Intent(this, com.gone.ai.MainActivity::class.java)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                .putExtra(com.gone.ai.MainActivity.EXTRA_ASK_TITLE, title)
                                .putExtra(com.gone.ai.MainActivity.EXTRA_ASK_TEXT, text)
                        )
                        finish()
                    }
                )
            }
        }
    }

    private fun showErrorAndFinish(msg: String) {
        setContent {
            GoneTheme(darkTheme = true) {
                CircleErrorScreen(message = msg, onDismiss = { finish() })
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clear the static bitmap; don't recycle — the bitmap may still be in use
        // by the VM's OCR pipeline. GC will collect it.
        InfinityOverlayService.pendingScreenshot = null
    }
}

