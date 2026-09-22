package com.gone.ai.viewmodel

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import com.gone.ai.ocr.DocumentTask
import com.gone.ai.ocr.OcrTextExtractor
import com.gone.ai.ocr.SaveTarget
import com.gone.ai.pdf.PdfTextSource
import kotlinx.coroutines.plus
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gone.ai.data.library.EntryType
import com.gone.ai.data.library.LibraryEntry
import com.gone.ai.data.library.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = LibraryRepository.getInstance(app)

    // ── Filter + search state ─────────────────────────────────────────────────
    private val _selectedType  = MutableStateFlow<EntryType?>(null)
    val selectedType: StateFlow<EntryType?> = _selectedType.asStateFlow()

    private val _searchQuery   = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // ── Derived entries list ──────────────────────────────────────────────────
    // Reacts to both filter and search changes. flatMapLatest cancels previous
    // DB flow whenever filter or query changes — no stale data.
    val entries: StateFlow<List<LibraryEntry>> = combine(_selectedType, _searchQuery) { type, query ->
        Pair(type, query)
    }.flatMapLatest { (type, query) ->
        when {
            query.isNotBlank()  -> repo.search(query).map { found -> if (type == null) found else found.filter { it.type == type } }
            type != null        -> repo.getByType(type)
            else                -> repo.getAll()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Importing records ─────────────────────────────────────────────────────

    sealed interface ImportState {
        data object Idle : ImportState
        data class Reading(val fileName: String, val detail: String) : ImportState
        data class Done(val entryId: Long) : ImportState
        data class Failed(val message: String) : ImportState
    }

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    /**
     * Add a document the person already has — a lab report, prescription or letter — as a
     * medical record. A PDF is read page by page; a photo with OCR. The record is marked for
     * the chat to use, since that is what adding it is for, and can be switched off.
     */
    fun importRecord(uri: Uri) {
        if (_importState.value is ImportState.Reading) return
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val name = runCatching {
                app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Medical record"
            _importState.value = ImportState.Reading(name, "Opening…")

            val text = if (app.contentResolver.getType(uri) == "application/pdf") {
                PdfTextSource(app).extract(uri, MAX_RECORD_CHARS) { progress ->
                    _importState.value = ImportState.Reading(name, "Reading page ${(progress.pagesRead + 1).coerceAtMost(progress.totalPages)} of ${progress.totalPages}")
                }.map { it.text }
            } else {
                _importState.value = ImportState.Reading(name, "Reading the photo…")
                val extractor = OcrTextExtractor()
                try {
                    extractor.extract(app, uri).map { it.first }
                } finally {
                    extractor.close()
                }
            }

            text.fold(
                onSuccess = { content ->
                    val id = repo.save(
                        type = EntryType.MEDICAL_RECORD,
                        content = content,
                        title = name.substringBeforeLast('.').ifBlank { name },
                        sourceInfo = name,
                        useInAi = true
                    )
                    _importState.value = ImportState.Done(id)
                },
                onFailure = { e -> _importState.value = ImportState.Failed(e.message ?: "The document could not be read.") }
            )
        }
    }

    fun importHandled() {
        _importState.value = ImportState.Idle
    }

    fun setUseInAi(entry: LibraryEntry, use: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { repo.setUseInAi(entry.id, use) }
    }

    // ── Key values from a record ──────────────────────────────────────────────

    /** Reads the measurements out of a record against the ranges printed with them. */
    val keyValues = DocumentTask.create(viewModelScope + Dispatchers.IO, app)

    fun pullKeyValues(entry: LibraryEntry) {
        keyValues.start(
            instruction = KEY_VALUES_PROMPT,
            document = entry.content,
            saveAs = SaveTarget(EntryType.NOTE, "Key values: ${entry.title}", sourceInfo = entry.title)
        )
    }

    // ── Undo ──────────────────────────────────────────────────────────────────
    private val _lastDeleted = MutableStateFlow<LibraryEntry?>(null)
    /** The entry deleted most recently, while it can still be put back. */
    val lastDeleted: StateFlow<LibraryEntry?> = _lastDeleted.asStateFlow()

    // ── Public actions ────────────────────────────────────────────────────────

    fun setFilter(type: EntryType?) { _selectedType.value = type }

    fun setSearch(query: String) { _searchQuery.value = query }

    fun observeEntry(id: Long): Flow<LibraryEntry?> = repo.observe(id)

    fun delete(entry: LibraryEntry) {
        _lastDeleted.value = entry
        viewModelScope.launch(Dispatchers.IO) { repo.delete(entry.id) }
    }

    fun undoDelete() {
        val entry = _lastDeleted.value ?: return
        _lastDeleted.value = null
        viewModelScope.launch(Dispatchers.IO) { repo.restore(entry) }
    }

    companion object {
        /** Text kept from an imported record; the chat trims further to its token budget. */
        const val MAX_RECORD_CHARS = 12_000

        const val KEY_VALUES_PROMPT =
            "List each measurement in the following document with its value and unit. For each, say " +
                "whether it is inside or outside the reference range printed with it; if no range is " +
                "printed, say \"no range given\" rather than supplying one. Do not diagnose or advise."
    }

    /** The undo offer has been shown and let go. */
    fun undoExpired(entry: LibraryEntry) {
        if (_lastDeleted.value?.id == entry.id) _lastDeleted.value = null
    }
}
