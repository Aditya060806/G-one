package com.gone.ai.ai.engine

import android.util.Log
import com.gone.ai.ai.prompts.PromptFormatter
import com.gone.ai.ai.runtime.LlamaCallback
import com.gone.ai.ai.runtime.LlamaJniBridge
import com.gone.ai.ai.state.AIInferenceState
import com.gone.ai.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class LlamaEngine : LocalAIEngine {

    companion object {
        private const val TAG        = "LlamaEngine"

        /**
         * Context window in tokens. 4096 leaves room for personal health context and an
         * attached document next to the conversation; the KV cache for Qwen2.5-1.5B
         * (28 layers, 2 KV heads of 128 dims, 16-bit) costs about 28 KB per token, so the
         * window takes roughly 115 MB. The native side reuses the shared start of consecutive
         * prompts, so a longer window does not mean re-reading all of it every turn.
         */
        const val N_CTX              = 4096
        private const val N_THREADS  = 4
        const val MAX_TOKENS         = 512

        /**
         * Prompt tokens allowed so that a full-length answer still fits the window.
         * The native side clamps output to whatever context remains, but a prompt this
         * close to the limit would leave room for only a clipped answer.
         */
        const val PROMPT_TOKEN_BUDGET = N_CTX - MAX_TOKENS - 16
    }

    /** One request in flight. Guarded by [lock]. */
    private class InFlight {
        var hasTokens = false
    }

    // Engine state is derived from the set of requests in flight rather than set by
    // whichever request happened to change last. With one shared state and several
    // clients, a health explanation finishing used to flip the engine to Idle while a
    // chat reply was still streaming, and a chat error left a sticky Error that made
    // every screen tell the user to restart the app.
    private val lock = Any()
    private val inFlight = LinkedHashMap<Long, InFlight>()
    private var nextLocalId = 1L
    private var loading = false
    private var loadError: String? = null

    private val _state = MutableStateFlow<AIInferenceState>(AIInferenceState.Idle)
    override val state: StateFlow<AIInferenceState> = _state.asStateFlow()

    /** Must be called with [lock] held. */
    private fun publishLocked() {
        _state.value = when {
            loading -> AIInferenceState.Loading
            inFlight.isNotEmpty() ->
                if (inFlight.values.any { it.hasTokens }) AIInferenceState.Responding()
                else AIInferenceState.Thinking
            loadError != null -> AIInferenceState.Error(loadError!!)
            else -> AIInferenceState.Idle
        }
    }

    override suspend fun loadModel(modelPath: String): Boolean {
        synchronized(lock) {
            loading = true
            loadError = null
            publishLocked()
        }
        Log.i(TAG, "Loading model: $modelPath")
        // Blocking native call. Cancellation cannot interrupt it, so it runs to completion
        // on IO and the caller decides what to do with the result.
        val ok = withContext(Dispatchers.IO) {
            LlamaJniBridge.loadModel(modelPath, N_CTX, N_THREADS)
        }
        synchronized(lock) {
            loading = false
            loadError = if (ok) null else "Failed to load AI model"
            publishLocked()
        }
        if (ok) Log.i(TAG, "Model loaded successfully") else Log.e(TAG, "Model load failed")
        return ok
    }

    override fun generate(
        history: List<ChatMessage>,
        userInput: String,
        systemPrompt: String
    ): Flow<String> =
        callbackFlow {
            val prompt = PromptFormatter.buildPromptWithinBudget(
                history      = history,
                newInput     = userInput,
                systemPrompt = systemPrompt,
                tokenBudget  = PROMPT_TOKEN_BUDGET,
                countTokens  = LlamaJniBridge::countTokens
            )
            Log.d(TAG, "Prompt length: ${prompt.length} chars")

            val request = InFlight()
            val localId = synchronized(lock) {
                val id = nextLocalId++
                inFlight[id] = request
                publishLocked()
                id
            }

            fun finish() = synchronized(lock) {
                if (inFlight.remove(localId) != null) publishLocked()
            }

            val nativeId = LlamaJniBridge.generate(
                prompt    = prompt,
                maxTokens = MAX_TOKENS,
                callback  = object : LlamaCallback {
                    override fun onToken(token: String) {
                        synchronized(lock) {
                            if (!request.hasTokens && inFlight.containsKey(localId)) {
                                request.hasTokens = true
                                publishLocked()
                            }
                        }
                        trySend(token)
                    }

                    override fun onComplete() {
                        finish()
                        channel.close()          // no-op if the collector already left
                    }

                    override fun onError(message: String) {
                        Log.e(TAG, "Generation error: $message")
                        finish()
                        channel.close(IllegalStateException(message))
                    }
                }
            )

            awaitClose {
                // Cancels THIS request only. Other clients of the engine are unaffected.
                if (nativeId > 0) LlamaJniBridge.stopGeneration(nativeId)
                finish()
            }
        }
            .buffer(Channel.UNLIMITED)
            // Prompt trimming tokenizes through JNI; keep it off whatever thread collects.
            .flowOn(Dispatchers.IO)

    override fun unload() {
        LlamaJniBridge.unloadModel()   // cancels every request natively, then frees
        synchronized(lock) {
            loadError = null
            publishLocked()
        }
    }

    override fun isReady(): Boolean = LlamaJniBridge.isModelLoaded()

    override fun countTokens(text: String): Int = LlamaJniBridge.countTokens(text)
}
