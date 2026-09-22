package com.gone.ai.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gone.ai.ai.repository.AIRepository
import com.gone.ai.data.library.EntryType
import com.gone.ai.ocr.DocumentTask
import com.gone.ai.ocr.OcrTextExtractor
import com.gone.ai.ocr.SaveTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/**
 * Actions offered for a captured screenshot.
 *
 * `FIX_ERROR` ("identify the error and provide a fix") was a developer-tool action and
 * has been replaced by `DECODE_TERMS`. The realistic G-one use case is a photo of a
 * report, a pharmacy label or a hospital portal screen — not a stack trace.
 */
enum class ScreenshotAction(val label: String, val prompt: String) {
    EXPLAIN      ("Explain",          "Explain clearly what the following says and what it means."),
    SIMPLIFY     ("Simplify",         "Rewrite the following in simple plain English anyone can understand."),
    DECODE_TERMS ("Decode Terms",     "List each medical or technical term and abbreviation that appears " +
        "below and define it in one plain sentence. Definitions only — do not diagnose or advise."),
    EXTRACT      ("Extract Key Info", "Extract and list all the important information from the following text.")
}

class ScreenshotExplainerViewModel(app: Application) : AndroidViewModel(app) {

    companion object { private const val TAG = "ScreenshotVM" }

    private val repository  = AIRepository.getInstance(app)
    private val modelLease  = repository.acquire("screenshot")
    private val extractor   = OcrTextExtractor()

    val task = DocumentTask.create(viewModelScope + Dispatchers.IO, app)

    private val _uiState       = MutableStateFlow<ScreenshotUiState>(ScreenshotUiState.Idle)
    val uiState: StateFlow<ScreenshotUiState> = _uiState.asStateFlow()

    private val _extractedText = MutableStateFlow("")
    val extractedText: StateFlow<String> = _extractedText.asStateFlow()

    private val _lastAction = MutableStateFlow<ScreenshotAction?>(null)
    val lastAction: StateFlow<ScreenshotAction?> = _lastAction.asStateFlow()

    private val extractTruncated = MutableStateFlow(false)
    val truncationNotice: StateFlow<Boolean> =
        combine(extractTruncated, task.truncated) { read, sent -> read || sent }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var extractJob: Job? = null

    init { repository.warmUp() }

    fun analyzeScreenshot(uri: Uri) {
        if (_uiState.value is ScreenshotUiState.Extracting) return
        extractJob?.cancel()
        task.reset()
        _extractedText.value = ""
        _lastAction.value = null
        extractTruncated.value = false

        extractJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = ScreenshotUiState.Extracting
            extractor.extract(getApplication(), uri).fold(
                onSuccess = { (text, truncated) ->
                    extractTruncated.value = truncated
                    _extractedText.value = text
                    _uiState.value = ScreenshotUiState.TextReady
                },
                onFailure = { e ->
                    Log.e(TAG, "OCR failed", e)
                    _uiState.value = ScreenshotUiState.Error(e.message ?: "Failed to read screenshot")
                }
            )
        }
    }

    fun runAction(action: ScreenshotAction) {
        val text = _extractedText.value
        if (text.isBlank()) return
        if (task.start(action.prompt, text, SaveTarget(EntryType.SCREENSHOT, "Screenshot – ${action.label}"))) {
            _lastAction.value = action
        }
    }

    fun retry() {
        _lastAction.value?.let { runAction(it) }
    }

    fun stop() = task.stop()

    fun saveResult() = task.requestSave()

    fun reset() {
        extractJob?.cancel()
        task.reset()
        _extractedText.value = ""
        _lastAction.value = null
        extractTruncated.value = false
        _uiState.value = ScreenshotUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        extractJob?.cancel()
        task.reset()
        extractor.close()
        modelLease.close()
    }
}

sealed class ScreenshotUiState {
    object Idle                            : ScreenshotUiState()
    object Extracting                      : ScreenshotUiState()
    object TextReady                       : ScreenshotUiState()
    data class Error(val message: String)  : ScreenshotUiState()
}
