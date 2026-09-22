package com.gone.ai.viewmodel

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gone.ai.ai.repository.AIRepository
import com.gone.ai.data.library.EntryType
import com.gone.ai.ocr.DocumentTask
import com.gone.ai.ocr.SaveTarget
import com.gone.ai.pdf.DocumentChunks
import com.gone.ai.pdf.PdfTextSource
import com.gone.ai.pdf.WholeDocumentSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/**
 * PDF Summary: read the file's pages, then summarise.
 *
 * A document that fits one prompt is summarised straight away. A longer one offers a choice:
 * a quick summary of its first part, or a whole-document summary that notes each part and
 * then combines the notes ([WholeDocumentSummary]).
 */
class PdfSummarizeViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        /** Text read from a PDF at most: enough for [DocumentChunks.MAX_PARTS] parts. */
        const val MAX_READ_CHARS = DocumentChunks.TARGET_CHARS * DocumentChunks.MAX_PARTS
    }

    enum class Mode { QUICK, WHOLE }

    private val repository = AIRepository.getInstance(app)
    private val modelLease = repository.acquire("pdf-summary")
    private val source     = PdfTextSource(app)

    val task = DocumentTask.create(viewModelScope + Dispatchers.IO, app)

    private val _uiState = MutableStateFlow<PdfSummarizeUiState>(PdfSummarizeUiState.Idle)
    val uiState: StateFlow<PdfSummarizeUiState> = _uiState.asStateFlow()

    private var readJob: Job? = null
    private var document: PdfSummarizeUiState.Read? = null
    private var lastMode: Mode = Mode.QUICK

    init {
        repository.warmUp()
    }

    fun open(uri: Uri) {
        if (_uiState.value is PdfSummarizeUiState.Reading) return
        readJob?.cancel()
        task.reset()
        document = null

        readJob = viewModelScope.launch(Dispatchers.IO) {
            val name = displayName(uri)
            _uiState.value = PdfSummarizeUiState.Reading(name, pagesRead = 0, totalPages = 0, readingImages = false)
            source.extract(uri, MAX_READ_CHARS) { progress ->
                _uiState.value = PdfSummarizeUiState.Reading(name, progress.pagesRead, progress.totalPages, progress.readingImages)
            }.fold(
                onSuccess = { extracted ->
                    val plan = DocumentChunks.plan(extracted.text)
                    val read = PdfSummarizeUiState.Read(
                        fileName = name,
                        text = extracted.text,
                        pagesRead = extracted.pagesRead,
                        totalPages = extracted.totalPages,
                        imagePages = extracted.imagePages,
                        parts = plan.parts.size,
                        // Pages left unread or parts beyond the limit: the whole-document
                        // summary covers less than the whole file, and says so.
                        coversWholeFile = !extracted.stoppedEarly && !plan.truncated
                    )
                    document = read
                    if (read.parts <= 1) summarize(Mode.QUICK) else _uiState.value = read
                },
                onFailure = { e ->
                    _uiState.value = PdfSummarizeUiState.Error(e.message ?: "The PDF could not be read.")
                }
            )
        }
    }

    fun summarize(mode: Mode) {
        val read = document ?: return
        lastMode = mode
        val title = if (read.parts <= 1 || mode == Mode.WHOLE) "Summary of ${read.fileName}"
        else "Summary of the start of ${read.fileName}"
        val saveAs = SaveTarget(EntryType.PDF_SUMMARY, title, sourceInfo = read.fileName)
        _uiState.value = PdfSummarizeUiState.Summary(read, mode)
        when (mode) {
            Mode.QUICK -> task.start(WholeDocumentSummary.QUICK_PROMPT, read.text, saveAs)
            Mode.WHOLE -> task.startSteps(saveAs) {
                WholeDocumentSummary.run(this, DocumentChunks.plan(read.text).parts)
            }
        }
    }

    fun retry() = summarize(lastMode)

    fun stop() = task.stop()

    fun saveResult() = task.requestSave()

    /** Back to the quick/whole choice for the same document. */
    fun chooseAgain() {
        val read = document ?: return
        task.reset()
        _uiState.value = if (read.parts > 1) read else PdfSummarizeUiState.Idle
    }

    fun reset() {
        readJob?.cancel()
        task.reset()
        document = null
        _uiState.value = PdfSummarizeUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        readJob?.cancel()
        task.reset()
        modelLease.close()
    }

    private fun displayName(uri: Uri): String = runCatching {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "PDF document"
}

// ── UI state ──────────────────────────────────────────────────────────────────

sealed class PdfSummarizeUiState {
    object Idle : PdfSummarizeUiState()

    data class Reading(
        val fileName: String,
        val pagesRead: Int,
        val totalPages: Int,
        /** True while a page with no usable text layer is being read as an image. */
        val readingImages: Boolean
    ) : PdfSummarizeUiState()

    /** The text was read and is long enough to offer a choice of summary. */
    data class Read(
        val fileName: String,
        val text: String,
        val pagesRead: Int,
        val totalPages: Int,
        val imagePages: Int,
        val parts: Int,
        val coversWholeFile: Boolean
    ) : PdfSummarizeUiState()

    /** A summary is running or finished; its progress is in [PdfSummarizeViewModel.task]. */
    data class Summary(val document: Read, val mode: PdfSummarizeViewModel.Mode) : PdfSummarizeUiState()

    data class Error(val message: String) : PdfSummarizeUiState()
}
