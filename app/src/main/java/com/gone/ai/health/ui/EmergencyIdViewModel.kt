package com.gone.ai.health.ui

import android.app.Application
import android.graphics.Bitmap
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gone.ai.data.UserProfile
import com.gone.ai.data.library.GoneDatabase
import com.gone.ai.health.data.EmergencyProfileEntity
import com.gone.ai.health.data.EmergencyRepository
import com.gone.ai.health.domain.EmergencyHtmlRenderer
import com.gone.ai.health.domain.EmergencyIdGenerator
import com.gone.ai.health.nfc.NfcTagWriter
import com.gone.ai.health.nfc.QrCodeGenerator
import com.gone.ai.health.service.HealthMonitoringService
import com.gone.ai.health.emergency.EmergencySync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the Emergency Medical ID screen.
 *
 * CONCURRENCY MODEL
 *
 * - [generateQr] and [onTagDiscovered] both run on Dispatchers.IO (ZXing encoding and NFC
 *   writes are blocking operations that must not block the main thread).
 * - [nfcState] is updated on the main thread via [MutableStateFlow].
 * - NFC reader mode is enabled/disabled by the Composable screen via a DisposableEffect;
 *   the ViewModel does not hold an Activity reference.
 *
 * NFC READER MODE LIFECYCLE
 *
 * The Composable calls [startNfcWrite] to transition to WaitingForTag. It enables reader
 * mode on the NfcAdapter using the Activity. When a tag is discovered, the Activity's
 * onNewIntent or the reader-mode callback delivers the tag; the Composable calls
 * [onTagDiscovered]. After success or error, [clearNfcState] resets to Idle.
 */
class EmergencyIdViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "EmergencyIdVM"
        /**
         * URL written to the NFC tag.
         * Emergency web route. The actual public URL uses a separate 256-bit read capability.
         */
        const val EMERGENCY_URL_BASE = "https://g--one.vercel.app/e/"
    }

    // ── Repository ────────────────────────────────────────────────────────────

    private val db     = GoneDatabase.getInstance(application)
    private val repo   = EmergencyRepository(
        dao        = db.emergencyDao(),
        vitalsDao  = db.vitalsDao(),
        anomalyDao = db.anomalyDao(),
        patientId  = HealthMonitoringService.DEFAULT_PATIENT_ID
    )

    // ── Profile state ─────────────────────────────────────────────────────────

    val profile: StateFlow<EmergencyProfileEntity?> = repo.observeProfile()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val sharing = EmergencySync.observe(application)
    fun setOnlineSharing(enabled: Boolean) = EmergencySync.setEnabled(getApplication(), enabled)
    fun retrySync() = EmergencySync.request(getApplication())
    private var writeTarget = NfcTarget.WEB_URL

    // ── QR state ──────────────────────────────────────────────────────────────

    private val _qrBitmap = MutableStateFlow<Bitmap?>(null)
    val qrBitmap: StateFlow<Bitmap?> = _qrBitmap.asStateFlow()

    private val _qrError = MutableStateFlow<String?>(null)
    val qrError: StateFlow<String?> = _qrError.asStateFlow()

    private val _qrGenerating = MutableStateFlow(false)
    val qrGenerating: StateFlow<Boolean> = _qrGenerating.asStateFlow()

    // ── NFC target mode ───────────────────────────────────────────────────────

    enum class NfcTarget {
        /** Writes Emergency Medical Record directly to the tag memory (100% offline, zero internet). */
        OFFLINE_CHIP,
        /** Writes the published emergency record's unique HTTPS URL. */
        WEB_URL
    }

    private val _nfcTarget = MutableStateFlow(NfcTarget.WEB_URL)
    val nfcTarget: StateFlow<NfcTarget> = _nfcTarget.asStateFlow()

    fun setNfcTarget(target: NfcTarget) {
        _nfcTarget.value = target
        _qrBitmap.value = null
    }

    // ── NFC state machine ─────────────────────────────────────────────────────

    sealed class NfcState {
        /** Default — no NFC write in progress. */
        object Idle : NfcState()
        /** Screen is showing the "hold phone near tag" prompt; reader mode is active. */
        object WaitingForTag : NfcState()
        /** A tag was found; writing the message now. */
        object Writing : NfcState()
        /** Write completed successfully. */
        object Success : NfcState()
        /** Write failed. [message] is shown to the user. */
        data class Error(val message: String) : NfcState()
    }

    private val _nfcState = MutableStateFlow<NfcState>(NfcState.Idle)
    val nfcState: StateFlow<NfcState> = _nfcState.asStateFlow()

    // ── NFC availability ──────────────────────────────────────────────────────

    /** Whether the device has an NFC adapter (not whether it's currently enabled). */
    val nfcAvailable: Boolean =
        NfcAdapter.getDefaultAdapter(application) != null

    /** Whether NFC is currently enabled on the device (adapter present AND enabled). */
    val nfcEnabled: Boolean
        get() = NfcAdapter.getDefaultAdapter(getApplication())?.isEnabled == true

    // ── Actions ───────────────────────────────────────────────────────────────

    /**
     * Save the edited profile.
     *
     * Clears the QR bitmap — the current bitmap was built from the previous profile
     * state and is now stale. The user must regenerate it after saving.
     */
    fun save(profile: EmergencyProfileEntity) {
        _qrBitmap.value = null
        viewModelScope.launch(Dispatchers.IO) {
            repo.save(profile)
            EmergencySync.request(getApplication())
        }
    }

    /**
     * Generate (or regenerate) the offline QR bitmap from the current profile + latest vitals.
     *
     * The bitmap is always freshly built — never cached from a previous call — so it
     * reflects the latest reading and the current profile settings at the moment of the tap.
     */
    fun generateQr() {
        _qrGenerating.value = true
        _qrError.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx     = getApplication<Application>()
                val profile = repo.ensureProfile()
                val up      = UserProfile.load(ctx)
                val patient = db.patientDao().byId(HealthMonitoringService.DEFAULT_PATIENT_ID)

                val name = patient?.name ?: up.name
                val age  = patient?.age  ?: up.age

                val payload = repo.buildPayload(profile, name, age)
                val content = if (_nfcTarget.value == NfcTarget.WEB_URL) {
                    val state = EmergencySync.current(ctx)
                    check(state.enabled && !state.removing && state.syncedAt != null) { "Enable online sharing and complete the first upload before generating a web QR." }
                    requireNotNull(state.url)
                } else payload.toOfflineText()
                val bitmap = QrCodeGenerator.encode(content)
                if (bitmap != null) {
                    _qrBitmap.value = bitmap
                    repo.markQrGenerated()
                } else {
                    _qrError.value = "Could not encode QR — the payload may be too large. " +
                        "Try turning off some optional fields."
                }
            } catch (e: Exception) {
                _qrError.value = "Failed to generate QR: ${e.message}"
            } finally {
                _qrGenerating.value = false
            }
        }
    }

    fun clearQrError() { _qrError.value = null }

    // ── NFC write flow ────────────────────────────────────────────────────────

    /**
     * Transition to [NfcState.WaitingForTag].
     *
     * The Composable must enable NFC reader mode on the Activity's adapter
     * in a DisposableEffect keyed on this state, and disable it when any
     * other state is reached.
     */
    fun startNfcWrite() {
        writeTarget = _nfcTarget.value
        if (writeTarget == NfcTarget.WEB_URL) {
            val state = EmergencySync.current(getApplication())
            if (!state.enabled || state.removing || state.syncedAt == null) {
                _nfcState.value = NfcState.Error("Enable online sharing and complete the first upload before writing your unique link.")
                return
            }
        }
        _nfcState.value = NfcState.WaitingForTag
    }

    /**
     * Handle a discovered tag.
     *
     * Called by the Composable's NFC reader-mode callback. Runs the write on IO,
     * then stamps the timestamp in the DB.
     */
    fun onTagDiscovered(tag: Tag) {
        if (!_nfcState.compareAndSet(NfcState.WaitingForTag, NfcState.Writing)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val profile = repo.ensureProfile()
                val ctx     = getApplication<Application>()
                val up      = UserProfile.load(ctx)
                val patient = db.patientDao().byId(HealthMonitoringService.DEFAULT_PATIENT_ID)
                val name    = patient?.name ?: up.name
                val age     = patient?.age  ?: up.age
                val payload = repo.buildPayload(profile, name, age)

                val result = when (writeTarget) {
                    NfcTarget.OFFLINE_CHIP -> {
                        NfcTagWriter().writeOfflineMedicalId(tag, payload)
                    }
                    NfcTarget.WEB_URL -> {
                        val state = EmergencySync.current(ctx)
                        check(state.enabled && !state.removing && state.syncedAt != null) { "Online sharing is not ready." }
                        NfcTagWriter().write(tag, requireNotNull(state.url))
                    }
                }
                withContext(Dispatchers.Main) {
                    _nfcState.value = when (result) {
                        is NfcTagWriter.WriteResult.Success -> {
                            viewModelScope.launch(Dispatchers.IO) { repo.markTagWritten() }
                            NfcState.Success
                        }
                        is NfcTagWriter.WriteResult.Failure -> NfcState.Error(result.reason)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _nfcState.value = NfcState.Error(e.message ?: "Unknown NFC error")
                }
            }
        }
    }

    fun cancelNfcWrite() { _nfcState.value = NfcState.Idle }
    fun clearNfcState()  { _nfcState.value = NfcState.Idle }

    // ── ID regeneration ───────────────────────────────────────────────────────

    /**
     * Generate a new local ID after successful online revocation, if any.
     * Offline tag/QR contents cannot be remotely revoked or erased.
     */
    fun regenerateId() {
        _qrBitmap.value = null
        viewModelScope.launch(Dispatchers.IO) {
            if (!EmergencySync.forgetRevokedLink(getApplication())) {
                _qrError.value = "Turn off online sharing and wait for removal to finish before regenerating."
                return@launch
            }
            repo.regenerateId(repo.ensureProfile())
        }
    }

    // ── Ensure profile exists on first open ───────────────────────────────────

    init {
        viewModelScope.launch(Dispatchers.IO) { repo.ensureProfile() }
    }
}
