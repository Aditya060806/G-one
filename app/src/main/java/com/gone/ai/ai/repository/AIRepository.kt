package com.gone.ai.ai.repository

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.gone.ai.ai.engine.LlamaEngine
import com.gone.ai.ai.engine.LocalAIEngine
import com.gone.ai.ai.prompts.PromptFormatter
import com.gone.ai.ai.state.AIInferenceState
import com.gone.ai.ai.storage.ModelStorageManager
import com.gone.ai.ai.streaming.TokenStreamBuffer
import com.gone.ai.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * AIRepository
 *
 * The single source of truth for all AI operations. ViewModels and the health
 * explainer talk only to this class — never to LlamaEngine or ModelStorageManager.
 *
 * Responsibilities:
 * 1. Coordinate model extraction (storage) + model loading (engine)
 * 2. Expose engine state
 * 3. Route generation requests to the engine, loading the model on demand
 * 4. Decide when the model may be released, via [acquire] leases
 *
 * Lifecycle: one process-wide instance, shared by every screen and by the health
 * monitoring service.
 *
 * CANCELLATION: there is deliberately no `stop()`. A request is cancelled by cancelling
 * the coroutine collecting [generate]. A shared stop used to cancel whatever was
 * running, so one screen could kill another component's generation.
 */
class AIRepository private constructor(context: Context) {

    companion object {
        private const val TAG = "AIRepository"

        /** How long the model stays loaded after the last lease closes. */
        private const val IDLE_UNLOAD_MILLIS = 60_000L

        /**
         * A failed load is retried on the next request, but not more often than this.
         * Failures used to be cached forever, so a transient problem (low memory during
         * a cold start) disabled every AI feature until the app was restarted.
         */
        private const val RETRY_AFTER_FAILURE_MILLIS = 15_000L

        @Volatile private var INSTANCE: AIRepository? = null

        fun getInstance(context: Context): AIRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AIRepository(context.applicationContext).also { INSTANCE = it }
            }
    }

    private val storageManager = ModelStorageManager(context)
    private val engine: LocalAIEngine = LlamaEngine()

    /** Outlives any single screen, so shared work is not abandoned by one caller leaving. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val initializationLock = Mutex()
    private var lastFailure: Throwable? = null
    private var lastFailureAt = 0L

    private val leases: ModelLeaseTracker = ModelLeaseTracker(scope, IDLE_UNLOAD_MILLIS) { unloadIfIdle() }

    /** Engine-wide state. See [LocalAIEngine.state] for why it is not per request. */
    val aiState: StateFlow<AIInferenceState> = engine.state

    /**
     * Declare that [owner] needs the model. Close the lease when it no longer does —
     * typically in `ViewModel.onCleared()` or `Service.onDestroy()`.
     *
     * Holding a lease does not load the model; call [warmUp] or [initialize] for that.
     * It prevents the model being released while the holder is alive.
     */
    fun acquire(owner: String): ModelLeaseTracker.Lease = leases.acquire(owner)

    /** Start loading the model in the background, if it is not loaded already. */
    fun warmUp() {
        scope.launch { initialize() }
    }

    /**
     * Extract (first launch only) and load the model. Idempotent and safe to call from
     * several places at once; concurrent callers share one load.
     *
     * The work runs in the repository's own scope, so a screen being closed mid-load
     * does not abandon a half-finished load that another component is waiting for.
     *
     * @param onExtractionProgress called during first-launch copy (0.0 to 1.0)
     */
    suspend fun initialize(onExtractionProgress: (Float) -> Unit = {}): Result<Unit> =
        scope.async { initializeLocked(onExtractionProgress) }.await()

    private suspend fun initializeLocked(onExtractionProgress: (Float) -> Unit): Result<Unit> =
        initializationLock.withLock {
            if (engine.isReady()) return@withLock Result.success(Unit)

            lastFailure?.let { failure ->
                if (SystemClock.elapsedRealtime() - lastFailureAt < RETRY_AFTER_FAILURE_MILLIS) {
                    return@withLock Result.failure(failure)
                }
            }

            try {
                Log.i(TAG, "Initializing AI repository")
                val modelPath = storageManager.extractModelIfNeeded(onExtractionProgress).getOrThrow()
                if (!engine.loadModel(modelPath)) {
                    throw IllegalStateException("Failed to load AI model")
                }
                lastFailure = null
                Log.i(TAG, "AI initialization complete")
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Initialization failed", e)
                lastFailure = e
                lastFailureAt = SystemClock.elapsedRealtime()
                Result.failure(e)
            }
        }

    /**
     * Generate a response, loading the model first if needed.
     *
     * Emits tokens as they are generated. Cancel the collecting coroutine to stop — that
     * cancels this request and no other. A load failure surfaces as the flow's error.
     */
    fun generate(
        history: List<ChatMessage>,
        userInput: String,
        systemPrompt: String = PromptFormatter.DEFAULT_SYSTEM_PROMPT
    ): Flow<String> = TokenStreamBuffer.clean(
        flow {
            initialize().getOrThrow()
            emitAll(engine.generate(history, userInput, systemPrompt))
        }
    )

    /**
     * Token count of [text] under the loaded model, or -1 if none is loaded.
     * Call [initialize] first when an exact count matters.
     */
    fun countTokensNow(text: String): Int = engine.countTokens(text)

    private suspend fun unloadIfIdle(): Unit = initializationLock.withLock {
        if (!leases.isIdle || !engine.isReady()) return@withLock
        Log.i(TAG, "No component holds the model — releasing it")
        engine.unload()
    }

    /** True if model is loaded and ready */
    fun isReady(): Boolean = engine.isReady()

    /** True if model file exists on disk (already extracted) */
    fun isModelOnDisk(): Boolean = storageManager.isModelExtracted()
}
