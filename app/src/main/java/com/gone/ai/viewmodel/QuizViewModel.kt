package com.gone.ai.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gone.ai.ai.repository.AIRepository
import com.gone.ai.data.library.EntryType
import com.gone.ai.ocr.AiTextProcessor
import com.gone.ai.ocr.DocumentTask
import com.gone.ai.ocr.OcrTextExtractor
import com.gone.ai.ocr.SaveTarget
import com.gone.ai.quiz.QuizParser
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
 * Writes a multiple-choice quiz from pasted text or a photo of a page.
 *
 * The model is asked for [QuizParser.PROMPT]'s layout so the finished quiz can be played
 * question by question; if its output cannot be parsed, the text is shown as written.
 */
class QuizViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "QuizViewModel"
        /** Pasted text beyond this is not kept; only the part that fits a prompt is used anyway. */
        const val MAX_INPUT_CHARS = AiTextProcessor.MAX_EXTRACTED_CHARS
    }

    private val repository  = AIRepository.getInstance(app)
    private val modelLease  = repository.acquire("quiz")
    private val extractor   = OcrTextExtractor()

    val task = DocumentTask.create(viewModelScope + Dispatchers.IO, app)

    private val _uiState   = MutableStateFlow<QuizUiState>(QuizUiState.Idle)
    val uiState: StateFlow<QuizUiState> = _uiState.asStateFlow()

    /** The text the questions were written from, as it went to the model. */
    private val _sourceText = MutableStateFlow("")
    val sourceText: StateFlow<String> = _sourceText.asStateFlow()

    private val sourceTruncated = MutableStateFlow(false)
    val truncationNotice: StateFlow<Boolean> =
        combine(sourceTruncated, task.truncated) { read, sent -> read || sent }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var extractJob: Job? = null
    private var lastSource: String? = null

    init { repository.warmUp() }

    /** Generate a quiz from pasted or typed text. */
    fun generateFromText(text: String) {
        if (text.isBlank() || _uiState.value is QuizUiState.Extracting) return
        sourceTruncated.value = text.length > MAX_INPUT_CHARS
        start(text.take(MAX_INPUT_CHARS))
    }

    /** Generate a quiz from an image via OCR. */
    fun generateFromImage(uri: Uri) {
        if (_uiState.value is QuizUiState.Extracting) return
        extractJob?.cancel()
        task.reset()
        extractJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = QuizUiState.Extracting
            extractor.extract(getApplication(), uri).fold(
                onSuccess = { (text, truncated) ->
                    sourceTruncated.value = truncated
                    start(text)
                },
                onFailure = { e ->
                    Log.e(TAG, "OCR failed", e)
                    _uiState.value = QuizUiState.Error(e.message ?: "Failed to read image")
                }
            )
        }
    }

    private fun start(text: String) {
        lastSource = text
        _sourceText.value = text
        val title = "Quiz – " + text.trim().lineSequence().first().take(40).trim()
        _uiState.value = QuizUiState.Quiz
        task.start(QuizParser.PROMPT, text, SaveTarget(EntryType.QUIZ, title)) { prepared ->
            _sourceText.value = prepared.document
        }
    }

    fun retry() {
        lastSource?.let { start(it) }
    }

    fun stop() = task.stop()

    fun saveResult() = task.requestSave()

    fun reset() {
        extractJob?.cancel()
        task.reset()
        lastSource = null
        sourceTruncated.value = false
        _sourceText.value = ""
        _uiState.value = QuizUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        extractJob?.cancel()
        task.reset()
        extractor.close()
        modelLease.close()
    }
}

sealed class QuizUiState {
    object Idle       : QuizUiState()
    object Extracting : QuizUiState()
    /** A quiz is being written or is ready; see [QuizViewModel.task]. */
    object Quiz       : QuizUiState()
    data class Error(val message: String) : QuizUiState()
}
