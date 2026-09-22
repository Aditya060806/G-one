package com.gone.ai.ocr

import android.content.Context
import com.gone.ai.ai.repository.AIRepository
import com.gone.ai.data.library.EntryType
import com.gone.ai.data.library.LibraryRepository
import com.gone.ai.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where a generation stands. [stage] names the step of a multi-step run, if any. */
sealed interface TaskStatus {
    data object Idle : TaskStatus
    /** Loading the model and fitting the document. */
    data class Preparing(val stage: String? = null) : TaskStatus
    data class Streaming(val tokens: Int, val stage: String? = null) : TaskStatus
    data class Finished(val result: ResultStatus) : TaskStatus
    data class Failed(val message: String) : TaskStatus
}

/** How a finished generation ended. */
enum class ResultStatus {
    /** The model finished its answer. Saved to the Vault automatically. */
    DONE,
    /** Cut short by a timeout or an error. Kept on screen; saved only if the person asks. */
    PARTIAL,
    /** The person pressed Stop. Kept on screen; saved only if the person asks. */
    STOPPED
}

/** Where a result is filed in the Vault. */
data class SaveTarget(val type: EntryType, val title: String, val sourceInfo: String = "")

val TaskStatus.isRunning: Boolean get() = this is TaskStatus.Preparing || this is TaskStatus.Streaming

/**
 * One document-based generation, with the same rules in every tool.
 *
 * The tools used to each wire [AiTextProcessor] themselves, and differed: pressing Stop in
 * OCR, Screenshot, Quiz and Circle Learn saved the half-written answer to the Vault as if it
 * were complete, while PDF did not. Here there is one rule: only a finished answer saves
 * itself. A partial or stopped one stays on screen and is saved, marked incomplete, only
 * when the person chooses [saveNow].
 */
class DocumentTask(
    private val scope: CoroutineScope,
    private val engine: Engine,
    private val save: suspend (SaveTarget, String) -> Unit
) {

    /** The model, as a task needs it. Split out so the rules above can be unit-tested. */
    interface Engine {
        suspend fun prepare(instruction: String, document: String, tokenBudget: Int): AiTextProcessor.PreparedPrompt
        fun generate(prompt: String): Flow<String>
    }

    /** What one generation step produced. */
    data class StepResult(val text: String, val outcome: AiTextProcessor.Outcome, val truncated: Boolean)

    /** The steps a run can take. A plain run is one visible step; a long document is several. */
    interface Steps {
        /** Name the current step, shown with the progress, e.g. "Part 2 of 5". */
        fun stage(label: String?)

        /**
         * Run one prompt. A [visible] step streams into [output]; a hidden one returns its
         * text without showing it, for notes a later step builds on.
         */
        suspend fun generate(
            instruction: String,
            document: String,
            visible: Boolean,
            tokenBudget: Int = AiTextProcessor.DOCUMENT_TOKEN_BUDGET
        ): StepResult
    }

    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()

    private val _status = MutableStateFlow<TaskStatus>(TaskStatus.Idle)
    val status: StateFlow<TaskStatus> = _status.asStateFlow()

    /** True when only the first part of the document fitted in the prompt. */
    private val _truncated = MutableStateFlow(false)
    val truncated: StateFlow<Boolean> = _truncated.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private var job: Job? = null
    private var target: SaveTarget? = null
    private val saveLock = Mutex()

    /**
     * Status changes go through [lock], tagged with the run they belong to. Stop is pressed on
     * the main thread while the run finishes on another, so without this a run finishing at
     * that instant could overwrite "stopped" with "done" and save itself.
     */
    private val lock = Any()
    private var currentRun = 0L
    private var stoppedRun = -1L

    private fun settle(run: Long, status: TaskStatus): Boolean = synchronized(lock) {
        if (run != currentRun || run == stoppedRun) return false
        _status.value = status
        true
    }

    /**
     * Run [instruction] over [document], unless a run is already going.
     *
     * @param saveAs where a finished answer is filed; null keeps it out of the Vault.
     * @param onPrepared called with the fitted prompt before generation starts.
     * @return false when a run was already going.
     */
    fun start(
        instruction: String,
        document: String,
        saveAs: SaveTarget?,
        onPrepared: ((AiTextProcessor.PreparedPrompt) -> Unit)? = null
    ): Boolean = launchRun(saveAs) { steps ->
        steps.generateStep(instruction, document, visible = true, onPrepared = onPrepared).outcome
    }

    /**
     * Run several generation steps as one task, e.g. notes on each part of a long document and
     * then a summary of the notes. [body] returns how the whole run ended.
     */
    fun startSteps(saveAs: SaveTarget?, body: suspend Steps.() -> AiTextProcessor.Outcome): Boolean =
        launchRun(saveAs) { steps -> steps.body() }

    private fun launchRun(saveAs: SaveTarget?, body: suspend (RunSteps) -> AiTextProcessor.Outcome): Boolean {
        val run = synchronized(lock) {
            if (_status.value.isRunning) return false
            job?.cancel()
            _output.value = ""
            _saved.value = false
            _truncated.value = false
            target = saveAs
            _status.value = TaskStatus.Preparing()
            ++currentRun
        }

        job = scope.launch {
            val outcome = try {
                body(RunSteps(run))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AiTextProcessor.Outcome.Failed(
                    e.message?.let { "AI engine unavailable: $it" } ?: "AI engine unavailable. Please try again."
                )
            }
            when (outcome) {
                AiTextProcessor.Outcome.Done ->
                    if (settle(run, TaskStatus.Finished(ResultStatus.DONE))) saveNow()
                AiTextProcessor.Outcome.Partial -> settle(run, TaskStatus.Finished(ResultStatus.PARTIAL))
                is AiTextProcessor.Outcome.Failed -> settle(run, TaskStatus.Failed(outcome.message))
            }
        }
        return true
    }

    private inner class RunSteps(private val run: Long) : Steps {
        private var stage: String? = null

        override fun stage(label: String?) {
            stage = label
            val current = _status.value
            settle(run, if (current is TaskStatus.Streaming) current.copy(stage = label) else TaskStatus.Preparing(label))
        }

        override suspend fun generate(
            instruction: String,
            document: String,
            visible: Boolean,
            tokenBudget: Int
        ): StepResult = generateStep(instruction, document, visible, tokenBudget, onPrepared = null)

        suspend fun generateStep(
            instruction: String,
            document: String,
            visible: Boolean,
            tokenBudget: Int = AiTextProcessor.DOCUMENT_TOKEN_BUDGET,
            onPrepared: ((AiTextProcessor.PreparedPrompt) -> Unit)?
        ): StepResult {
            settle(run, TaskStatus.Preparing(stage))
            val prepared = engine.prepare(instruction, document, tokenBudget)
            if (prepared.truncated) _truncated.value = true
            onPrepared?.invoke(prepared)
            settle(run, TaskStatus.Streaming(0, stage))

            val sink = if (visible) _output.also { it.value = "" } else MutableStateFlow("")
            val outcome = AiTextProcessor.stream(engine::generate, prepared.prompt, sink) { n ->
                settle(run, TaskStatus.Streaming(n, stage))
            }
            return StepResult(sink.value, outcome, prepared.truncated)
        }
    }

    /** Stop the running generation, keeping what it wrote. Cancels this request only. */
    fun stop() {
        synchronized(lock) {
            if (!_status.value.isRunning) return
            stoppedRun = currentRun
            job?.cancel()
            _status.value = TaskStatus.Finished(ResultStatus.STOPPED)
        }
    }

    /**
     * File the current answer in the Vault, once. Incomplete answers are saved with
     * "(incomplete)" in their title so they are never mistaken for finished ones.
     */
    suspend fun saveNow() = saveLock.withLock {
        val saveAs = target ?: return@withLock
        val text = _output.value
        if (text.isBlank() || _saved.value) return@withLock
        val finished = (_status.value as? TaskStatus.Finished)?.result ?: return@withLock
        val title = if (finished == ResultStatus.DONE) saveAs.title else "${saveAs.title} (incomplete)"
        save(saveAs.copy(title = title), text)
        _saved.value = true
    }

    /** [saveNow] from a click handler. */
    fun requestSave() {
        scope.launch { saveNow() }
    }

    /** Stop and forget everything, ready for a new document. */
    fun reset() {
        synchronized(lock) {
            currentRun++   // anything still finishing belongs to an old run and is ignored
            job?.cancel()
            job = null
            target = null
            _output.value = ""
            _saved.value = false
            _truncated.value = false
            _status.value = TaskStatus.Idle
        }
    }

    companion object {
        /** A task backed by the shared on-device model, saving to the Vault. */
        fun create(scope: CoroutineScope, context: Context): DocumentTask {
            val repository = AIRepository.getInstance(context)
            val library = LibraryRepository.getInstance(context)
            return DocumentTask(scope, RepositoryEngine(repository)) { target, text ->
                library.save(target.type, text, title = target.title, sourceInfo = target.sourceInfo)
            }
        }
    }

    private class RepositoryEngine(private val repository: AIRepository) : Engine {
        override suspend fun prepare(instruction: String, document: String, tokenBudget: Int) =
            AiTextProcessor.prepare(repository, instruction, document, tokenBudget)

        override fun generate(prompt: String): Flow<String> =
            repository.generate(emptyList<ChatMessage>(), prompt)
    }
}
