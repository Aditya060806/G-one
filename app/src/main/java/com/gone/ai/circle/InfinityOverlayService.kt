package com.gone.ai.circle

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.graphics.createBitmap
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.gone.ai.MainActivity
import com.gone.ai.R
import com.gone.ai.ui.theme.GoneTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * InfinityOverlayService
 *
 * Owns the Circle Learn flow: the floating bubble, screen capture, region selection, OCR
 * and the answer sheet, all as WindowManager overlays.
 *
 * SCREEN CAPTURE, ANDROID 14 AND LATER
 *
 * One screen-capture consent gives one [MediaProjection], and that projection may create
 * ONE [VirtualDisplay]. The old code kept the projection and created a new virtual display
 * for every tap, and on failure asked for the projection again with the same consent token.
 * Android 14+ rejects both with a SecurityException, so the second capture always failed.
 *
 * Now a consent creates the projection and its single virtual display once, and the display
 * mirrors into an [ImageReader] for as long as Circle Learn runs. The reader keeps only the
 * newest frame (a buffer handle, no pixel copying); a tap hides the bubble, waits for a frame
 * drawn without it, and copies just that one.
 *
 * Android also ends a projection by itself — when the phone locks, or the person stops
 * sharing from the status bar. A new consent is then required. The bubble and notification
 * say so, and a tap opens Circle Learn in the app to ask again.
 *
 * Pipeline per tap:
 *   Bubble tap → freshFrame() → attachSelectionOverlay() → vm.processRegion()
 *   → attachBottomSheetOverlay() → onDismiss: remove overlays, bubble stays for the next tap
 */
class InfinityOverlayService : Service() {

    companion object {
        const val ACTION_START       = "com.gone.ai.circle.START"
        const val ACTION_STOP        = "com.gone.ai.circle.STOP"
        const val EXTRA_RESULT_CODE  = "result_code"
        const val EXTRA_RESULT_DATA  = "result_data"
        private const val NOTIF_ID   = 9001
        private const val CHANNEL_ID = "infinity_overlay"
        private const val TAG        = "CircleLearnService"

        /** How long a tap waits for a frame drawn after the bubble was hidden. */
        private const val FRESH_FRAME_TIMEOUT_MS = 700L

        @Volatile var pendingScreenshot: Bitmap? = null

        private val _running = MutableStateFlow(false)
        /** True while the service runs and the bubble can be shown. */
        val isRunning: StateFlow<Boolean> = _running.asStateFlow()

        private val _captureLost = MutableStateFlow(false)
        /**
         * True when Android has ended screen capture — the phone was locked, or sharing was
         * stopped from the status bar. Starting Circle Learn again asks for consent anew.
         */
        val captureLost: StateFlow<Boolean> = _captureLost.asStateFlow()
    }

    // ── WindowManager ──────────────────────────────────────────────────────────
    private lateinit var wm: WindowManager
    private val overlayType
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    // ── Overlay views ──────────────────────────────────────────────────────────
    private var bubble:           FloatingBubbleView? = null
    private var selectionOverlay: RegionSelectionView? = null
    private var sheetHost:        OverlayComposeHost? = null

    // ── Capture: one projection, one virtual display, per consent ─────────────
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var frameThread: HandlerThread? = null
    private var captureSize = Triple(0, 0, 0)   // width, height, dpi

    /** Guards [latestImage], [latestFrameAt] and [imageReader] against the frame thread. */
    private val frameLock = Any()
    private var latestImage: Image? = null
    private var latestFrameAt = 0L

    /** Set while this service ends the projection itself, so onStop is not read as a loss. */
    @Volatile private var stoppingCapture = false

    // ── ViewModel (service-scoped) ─────────────────────────────────────────────
    private val vmStore = ViewModelStore()
    private lateinit var vm: CircleLearnViewModel

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentScreenshot: Bitmap? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()

        vm = ViewModelProvider(
            vmStore,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        )[CircleLearnViewModel::class.java]
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Before getMediaProjection: Android 14+ requires the mediaProjection
                // foreground service to be running first.
                if (!enterForeground(captureLost = false)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (code != Activity.RESULT_OK || data == null || !startCapture(code, data)) {
                    markCaptureLost()
                }
                _running.value = true
                showBubble()
                Log.i(TAG, "Circle Learn ready, capture=${virtualDisplay != null}")
            }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed — releasing screen capture")
        scope.cancel()
        hideBubble()
        removeAllOverlays()
        currentScreenshot?.recycle(); currentScreenshot = null
        stopCapture()
        vmStore.clear()
        _running.value = false
        _captureLost.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** The screen rotated or changed size: resize the one virtual display, never make another. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val display = virtualDisplay ?: return
        val thread = frameThread ?: return
        val size = screenSize()
        if (size == captureSize) return
        val (width, height, dpi) = size
        val reader = newReader(width, height, Handler(thread.looper))
        synchronized(frameLock) {
            latestImage?.close(); latestImage = null
            imageReader?.close()
            imageReader = reader
        }
        display.resize(width, height, dpi)
        display.surface = reader.surface
        captureSize = size
    }

    // ── Screen capture ─────────────────────────────────────────────────────────

    private fun startCapture(resultCode: Int, data: Intent): Boolean {
        stopCapture()   // a new consent replaces any earlier session
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            manager.getMediaProjection(resultCode, data)
        } catch (e: SecurityException) {
            Log.e(TAG, "Consent could not be used", e)
            null
        } ?: return false

        // Android 14+ requires the callback before capture starts.
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (stoppingCapture) return
                Log.w(TAG, "Screen capture ended by the system")
                scope.launch { onCaptureEnded() }
            }
        }, Handler(Looper.getMainLooper()))
        mediaProjection = projection

        val thread = HandlerThread("circle-learn-frames").also { it.start() }
        frameThread = thread
        val size = screenSize()
        val (width, height, dpi) = size
        val reader = newReader(width, height, Handler(thread.looper))
        synchronized(frameLock) { imageReader = reader }
        virtualDisplay = try {
            projection.createVirtualDisplay(
                "CircleLearn", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Virtual display refused", e)
            null
        }
        if (virtualDisplay == null) {
            stopCapture()
            return false
        }
        captureSize = size
        _captureLost.value = false
        return true
    }

    /** A reader that always holds just the newest frame, as a buffer handle. */
    private fun newReader(width: Int, height: Int, handler: Handler): ImageReader =
        // 3: one frame held here, plus room for acquireLatestImage to skip older ones.
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ reader ->
                val image = try { reader.acquireLatestImage() } catch (e: IllegalStateException) { null }
                    ?: return@setOnImageAvailableListener
                synchronized(frameLock) {
                    if (imageReader !== reader) {
                        image.close()
                    } else {
                        latestImage?.close()
                        latestImage = image
                        latestFrameAt = SystemClock.uptimeMillis()
                    }
                }
            }, handler)
        }

    private fun stopCapture() {
        stoppingCapture = true
        try {
            releaseCapture()
            mediaProjection?.stop()
            mediaProjection = null
        } finally {
            stoppingCapture = false
        }
    }

    private fun releaseCapture() {
        virtualDisplay?.release(); virtualDisplay = null
        synchronized(frameLock) {
            latestImage?.close(); latestImage = null
            imageReader?.close(); imageReader = null
        }
        frameThread?.quitSafely(); frameThread = null
    }

    private fun onCaptureEnded() {
        releaseCapture()
        mediaProjection = null
        markCaptureLost()
    }

    private fun markCaptureLost() {
        _captureLost.value = true
        enterForeground(captureLost = true)
    }

    /**
     * A copy of a frame drawn after [notBefore], waiting up to [FRESH_FRAME_TIMEOUT_MS] for
     * one. Removing the bubble changes the screen, so a new frame normally arrives within a
     * frame or two; the newest frame is used if none does.
     */
    private suspend fun freshFrame(notBefore: Long): Bitmap? {
        val deadline = notBefore + FRESH_FRAME_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val fresh = synchronized(frameLock) {
                if (latestImage != null && latestFrameAt > notBefore + 16) copyLatestLocked() else null
            }
            if (fresh != null) return fresh
            delay(20)
        }
        return synchronized(frameLock) { copyLatestLocked() }
    }

    /** Must hold [frameLock], so the frame thread cannot close the image mid-copy. */
    private fun copyLatestLocked(): Bitmap? {
        val image = latestImage ?: return null
        return try {
            val plane = image.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * image.width
            val buffer = plane.buffer.apply { rewind() }
            val padded = createBitmap(image.width + rowPadding / plane.pixelStride, image.height)
            padded.copyPixelsFromBuffer(buffer)
            if (padded.width == image.width) padded
            else Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
        } catch (e: Exception) {
            Log.e(TAG, "Frame copy failed", e)
            null
        }
    }

    /** Full display size in the current rotation, system bars included. */
    private fun screenSize(): Triple<Int, Int, Int> {
        val dpi = resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            Triple(bounds.width(), bounds.height(), dpi)
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
            Triple(metrics.widthPixels, metrics.heightPixels, dpi)
        }
    }

    // ── Bubble ─────────────────────────────────────────────────────────────────

    private fun showBubble() {
        if (!OverlayPermissionHelper.hasOverlayPermission(this)) {
            Log.w(TAG, "No overlay permission — bubble not shown"); return
        }
        if (bubble?.isShowing() == true) return
        bubble = FloatingBubbleView(this) { onBubbleTapped() }
        bubble?.show()
    }

    private fun hideBubble() { bubble?.hide(); bubble = null }

    /** Ensure bubble is present — re-attaches if it was removed unexpectedly. */
    private fun ensureBubble() {
        if (bubble?.isShowing() == true) return
        bubble = FloatingBubbleView(this) { onBubbleTapped() }
        bubble?.show()
    }

    // ── Step 1: Bubble tapped ──────────────────────────────────────────────────

    private fun onBubbleTapped() {
        if (virtualDisplay == null) {
            // Capture ended (screen locked, sharing stopped): consent must be given again,
            // which only the app can ask for.
            launchMainActivity("circle_learn")
            return
        }
        if (!OverlayPermissionHelper.hasOverlayPermission(this)) {
            fallbackToActivity(); return
        }
        val hiddenAt = SystemClock.uptimeMillis()
        bubble?.hide()

        scope.launch {
            val bitmap = withContext(Dispatchers.Default) { freshFrame(hiddenAt) }
            if (bitmap == null) {
                vm.setError("The screen could not be captured. Please try again.")
                attachBottomSheetOverlay()
                return@launch
            }
            currentScreenshot?.recycle()
            currentScreenshot = bitmap
            attachSelectionOverlay(bitmap)
        }
    }

    // ── Step 2: Selection overlay ──────────────────────────────────────────────

    private fun attachSelectionOverlay(screenshot: Bitmap) {
        val params = fullScreenParams(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        val view = RegionSelectionView(this).apply {
            setScreenshot(screenshot)
            onCancel = {
                scope.launch(Dispatchers.Main) {
                    removeSelectionOverlay()
                    ensureBubble()
                }
            }
            onRegionSelected = { region ->
                scope.launch(Dispatchers.Main) {
                    removeSelectionOverlay()
                    vm.processRegion(screenshot, region)
                    attachBottomSheetOverlay()
                }
            }
        }

        selectionOverlay = view
        runCatching { wm.addView(view, params) }
            .onFailure {
                Log.e(TAG, "Failed to add selection overlay", it)
                selectionOverlay = null
                ensureBubble()
            }
    }

    private fun removeSelectionOverlay() {
        selectionOverlay?.let { runCatching { wm.removeView(it) } }
        selectionOverlay = null
    }

    // ── Step 3: Bottom-sheet overlay ──────────────────────────────────────────

    private fun attachBottomSheetOverlay() {
        val params = fullScreenParams(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        val host = OverlayComposeHost(this) {
            GoneTheme(darkTheme = true) {
                CircleLearnBottomSheetHost(
                    vm          = vm,
                    onDismiss   = {
                        scope.launch(Dispatchers.Main) {
                            removeBottomSheetOverlay()
                            vm.reset()
                            currentScreenshot?.recycle(); currentScreenshot = null
                            ensureBubble()
                        }
                    },
                    onOpenInApp = { route ->
                        scope.launch(Dispatchers.Main) {
                            removeAllOverlays()
                            vm.reset()
                            // The bubble stays in the background while the app is open.
                            launchMainActivity(route)
                        }
                    },
                    onAskInApp = { title, text ->
                        scope.launch(Dispatchers.Main) {
                            removeAllOverlays()
                            vm.reset()
                            launchMainActivity(route = null, askTitle = title, askText = text)
                            ensureBubble()
                        }
                    }
                )
            }
        }

        sheetHost = host
        runCatching {
            wm.addView(host.view, params)
            host.start()
        }.onFailure {
            Log.e(TAG, "Failed to add bottom sheet overlay", it)
            sheetHost = null
            ensureBubble()
        }
    }

    private fun removeBottomSheetOverlay() {
        sheetHost?.let { it.stop(); runCatching { wm.removeView(it.view) } }
        sheetHost = null
    }

    private fun removeAllOverlays() {
        removeSelectionOverlay()
        removeBottomSheetOverlay()
    }

    // ── Fallback: launch CircleLearnActivity when overlay permission missing ───

    private fun fallbackToActivity() {
        Log.w(TAG, "Overlay permission unavailable — falling back to CircleLearnActivity")
        val requestedAt = SystemClock.uptimeMillis()
        scope.launch {
            val bitmap = withContext(Dispatchers.Default) { freshFrame(requestedAt) } ?: return@launch
            pendingScreenshot?.recycle()
            pendingScreenshot = bitmap
            startActivity(
                Intent(this@InfinityOverlayService, CircleLearnActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // ── Open the app (explicit user request only) ──────────────────────────────

    private fun launchMainActivity(route: String?, askTitle: String? = null, askText: String? = null) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (route != null) putExtra(MainActivity.EXTRA_ROUTE, route)
            if (askText != null) {
                putExtra(MainActivity.EXTRA_ASK_TITLE, askTitle)
                putExtra(MainActivity.EXTRA_ASK_TEXT, askText)
            }
        }
        startActivity(intent)
    }

    // ── WindowManager helpers ──────────────────────────────────────────────────

    /** Covers the whole display, cutouts and system bars included, so a selection maps 1:1. */
    private fun fullScreenParams(flags: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        0, 0,
        overlayType,
        flags,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fitInsetsTypes = 0
        }
    }

    // ── Notification ───────────────────────────────────────────────────────────

    private fun enterForeground(captureLost: Boolean): Boolean {
        val notification = buildNotification(captureLost)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIF_ID, notification)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not enter the foreground", e)
            false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "G-one Circle Learn", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Circle Learn overlay service" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(captureLost: Boolean): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, InfinityOverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                if (captureLost) putExtra(MainActivity.EXTRA_ROUTE, "circle_learn")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (captureLost) "Circle Learn is paused" else "G-one Circle Learn")
            .setContentText(
                if (captureLost) "Screen capture was turned off. Tap to turn it back on."
                else "Tap the bubble to circle anything on screen"
            )
            .setSmallIcon(R.drawable.ic_stat_gone)
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(0, "Stop", stopPi)
            .build()
    }
}
