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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

enum class OcrAction(val label: String, val prompt: String) {
    EXPLAIN     ("Explain",       "Explain the following text in simple, clear language."),
    // The scanned page is most often a lab report or prescription, so reading the
    // numbers against their own printed ranges is the highest-value action here.
    CHECK_RANGES("Check Ranges",  "For each measurement below, state its value and whether it falls " +
        "inside or outside the reference range printed alongside it. If no range is printed, say " +
        "\"no range given\" rather than supplying one from memory. Do not diagnose."),
    SUMMARIZE   ("Summarize",     "Summarize the following text in 5 concise bullet points:"),
    KEY_POINTS  ("Key Points",    "List the 5 most important key points from the following text."),
    TO_NOTES    ("Convert to Notes","Convert the following text into organized notes with headings.")
}

/**
 * Reads text from a photo, then runs one [OcrAction] over it through a [DocumentTask].
 *
 * [uiState] covers reading the image; [task] covers the answer, so a failed answer keeps
 * the extracted text on screen for another try instead of throwing it away.
 */
class OcrViewModel(app: Application) : AndroidViewModel(app) {

    companion object { private const val TAG = "OcrViewModel" }

    private val repository  = AIRepository.getInstance(app)
    private val modelLease  = repository.acquire("ocr")
    private val extractor   = OcrTextExtractor()

    val task = DocumentTask.create(viewModelScope + Dispatchers.IO, app)

    private val _uiState = MutableStateFlow<OcrUiState>(OcrUiState.Idle)
    val uiState: StateFlow<OcrUiState> = _uiState.asStateFlow()

    private val _extractedText = MutableStateFlow("")
    val extractedText: StateFlow<String> = _extractedText.asStateFlow()

    private val _lastAction = MutableStateFlow<OcrAction?>(null)
    val lastAction: StateFlow<OcrAction?> = _lastAction.asStateFlow()

    private val _extractTruncated = MutableStateFlow(false)

    /** True when only part of the text could be read or sent to the model. */
    val truncationNotice: StateFlow<Boolean> =
        combine(_extractTruncated, task.truncated) { read, sent -> read || sent }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var extractJob: Job? = null

    init { repository.warmUp() }

    fun extractText(uri: Uri) {
        if (_uiState.value is OcrUiState.Extracting) return
        extractJob?.cancel()
        task.reset()
        _extractedText.value = ""
        _lastAction.value = null
        _extractTruncated.value = false

        extractJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = OcrUiState.Extracting
            extractor.extract(getApplication(), uri).fold(
                onSuccess = { (text, wasTruncated) ->
                    _extractedText.value    = text
                    _extractTruncated.value = wasTruncated
                    _uiState.value          = OcrUiState.TextReady
                },
                onFailure = { e ->
                    Log.e(TAG, "OCR failed", e)
                    _uiState.value = OcrUiState.Error(e.message ?: "Failed to read image")
                }
            )
        }
    }

    fun runAction(action: OcrAction) {
        val text = _extractedText.value
        if (text.isBlank()) return
        if (task.start(action.prompt, text, SaveTarget(EntryType.OCR, "OCR – ${action.label}"))) {
            _lastAction.value = action
        }
    }

    /** Run the last action again, after a failure. */
    fun retry() {
        _lastAction.value?.let { runAction(it) }
    }

    fun stop() = task.stop()

    fun saveResult() = task.requestSave()

    fun reset() {
        extractJob?.cancel()
        task.reset()
        _extractedText.value = ""
        _extractTruncated.value = false
        _lastAction.value = null
        _uiState.value = OcrUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        extractJob?.cancel()
        task.reset()
        extractor.close()
        modelLease.close()
    }
}

sealed class OcrUiState {
    object Idle                           : OcrUiState()
    object Extracting                     : OcrUiState()
    /** Text was read; actions can run on it. The answer's state is in [OcrViewModel.task]. */
    object TextReady                      : OcrUiState()
    data class Error(val message: String) : OcrUiState()
}
