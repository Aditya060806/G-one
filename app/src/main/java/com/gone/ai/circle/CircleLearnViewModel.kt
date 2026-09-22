package com.gone.ai.circle

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gone.ai.ai.repository.AIRepository
import com.gone.ai.data.library.EntryType
import com.gone.ai.data.library.LibraryRepository
import com.gone.ai.ocr.DocumentTask
import com.gone.ai.ocr.SaveTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

class CircleLearnViewModel(app: Application) : AndroidViewModel(app) {

    companion object { private const val TAG = "CircleLearnVM" }

    private val repository  = AIRepository.getInstance(app)
    private val modelLease  = repository.acquire("circle-learn")
    private val libraryRepo = LibraryRepository.getInstance(app)
    private val processor   = CircleLearnProcessor()

    val task = DocumentTask.create(viewModelScope + Dispatchers.IO, app)

    // ── State ──────────────────────────────────────────────────────────────────
    private val _uiState = MutableStateFlow<CircleUiState>(CircleUiState.Idle)
    val uiState: StateFlow<CircleUiState> = _uiState.asStateFlow()

    private val _ocrText    = MutableStateFlow("")
    val ocrText: StateFlow<String> = _ocrText.asStateFlow()

    private val _detection  = MutableStateFlow<ContentTypeDetector.DetectionResult?>(null)
    val detection: StateFlow<ContentTypeDetector.DetectionResult?> = _detection.asStateFlow()

    /** The action whose answer is showing, or null while choosing one. */
    private val _action = MutableStateFlow<CircleAction?>(null)
    val action: StateFlow<CircleAction?> = _action.asStateFlow()

    private val _savedToVault = MutableStateFlow(false)
    val savedToVault: StateFlow<Boolean> = _savedToVault.asStateFlow()

    private var job: Job? = null

    init { repository.warmUp() }

    // ── Crop bitmap + OCR ──────────────────────────────────────────────────────

    fun processRegion(bitmap: Bitmap, region: Rect) {
        job?.cancel()
        task.reset()
        _ocrText.value    = ""
        _detection.value  = null
        _action.value     = null
        _savedToVault.value = false

        job = viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = CircleUiState.Processing

            processor.process(bitmap, region).fold(
                onSuccess = { result ->
                    _ocrText.value   = result.text
                    _detection.value = result.detection
                    _uiState.value   = CircleUiState.OcrDone(result)
                    Log.i(TAG, "OCR done: ${result.text.length} chars, type=${result.detection.type}")
                },
                onFailure = { e ->
                    Log.e(TAG, "OCR failed", e)
                    _uiState.value = CircleUiState.Error(e.message ?: "Failed to read selection")
                }
            )
        }
    }

    // ── Run AI action ──────────────────────────────────────────────────────────

    fun runAction(action: CircleAction) {
        if (action == CircleAction.SAVE_TO_VAULT) { saveSelectionToVault(); return }
        val text = _ocrText.value
        if (text.isBlank()) return
        if (task.start(action.prompt, text, SaveTarget(entryTypeFor(action), "Circle: ${action.label}", "Circle Learn"))) {
            _action.value = action
        }
    }

    fun retry() {
        _action.value?.let { runAction(it) }
    }

    fun stop() = task.stop()

    fun saveResult() = task.requestSave()

    /** Back to the action list for the same selection. */
    fun chooseAnotherAction() {
        task.reset()
        _action.value = null
    }

    // ── Save the selection itself ──────────────────────────────────────────────

    private fun saveSelectionToVault() {
        val text = _ocrText.value
        if (text.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            libraryRepo.save(
                type       = EntryType.OCR,
                content    = text,
                title      = "Circle Learn – ${_detection.value?.type?.name?.lowercase() ?: "selection"}",
                sourceInfo = "Circle Learn"
            )
            _savedToVault.value = true
            delay(2_500)
            _savedToVault.value = false
        }
    }

    private fun entryTypeFor(action: CircleAction) = when (action) {
        CircleAction.NOTES, CircleAction.FLASHCARDS -> EntryType.NOTE
        CircleAction.QUIZ, CircleAction.PRACTICE_QUESTIONS, CircleAction.VIVA -> EntryType.QUIZ
        else -> EntryType.OCR
    }

    fun reset() {
        job?.cancel()
        task.reset()
        _ocrText.value      = ""
        _detection.value    = null
        _action.value       = null
        _uiState.value      = CircleUiState.Idle
        _savedToVault.value = false
    }

    /** Called by service when screen capture fails before OCR starts. */
    fun setError(message: String) {
        _uiState.value = CircleUiState.Error(message)
    }

    override fun onCleared() {
        super.onCleared()
        job?.cancel()
        task.reset()
        processor.close()
        modelLease.close()
    }
}

// ── UI state ───────────────────────────────────────────────────────────────────

sealed class CircleUiState {
    object Idle                                              : CircleUiState()
    object Processing                                        : CircleUiState()
    /** Text was read. An action's answer, when one is chosen, is in [CircleLearnViewModel.task]. */
    data class OcrDone(val result: CircleLearnProcessor.ProcessResult) : CircleUiState()
    data class Error(val message: String)                    : CircleUiState()
}
