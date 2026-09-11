package com.infinity.ai.ai.engine

import android.util.Log
import com.infinity.ai.ai.prompts.PromptFormatter
import com.infinity.ai.ai.runtime.LlamaCallback
import com.infinity.ai.ai.runtime.LlamaJniBridge
import com.infinity.ai.ai.state.AIInferenceState
import com.infinity.ai.model.ChatMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.atomic.AtomicReference

class LlamaEngine : LocalAIEngine {

    companion object {
        private const val TAG        = "LlamaEngine"
        private const val N_CTX      = 2048
        private const val N_THREADS  = 4
        private const val MAX_TOKENS = 512
    }

    // AtomicReference is the CAS source of truth for thread-safe state transitions
    // from C++ JNI threads. MutableStateFlow is kept in sync and used for UI observation.
    // MutableStateFlow has no compareAndSet API, so we need AtomicReference for CAS.
    private val _stateRef = AtomicReference<AIInferenceState>(AIInferenceState.Idle)
    private val _state    = MutableStateFlow<AIInferenceState>(AIInferenceState.Idle)
    override val state: StateFlow<AIInferenceState> = _state.asStateFlow()

    private fun setState(new: AIInferenceState) {
        _stateRef.set(new)
        _state.value = new
    }

    /** Atomic CAS — safe to call from any thread including C++ JNI threads. */
    private fun casState(expected: AIInferenceState, new: AIInferenceState) {
        if (_stateRef.compareAndSet(expected, new)) {
            _state.value = new
        }
    }

    /**
     * Publish accumulated partial output as [AIInferenceState.Responding].
     *
     * Only advances from Thinking or Responding. If a terminal state was already
     * set concurrently — stop() → Idle, or onError() → Error — the CAS fails, we
     * observe the terminal state on retry, and bail out without clobbering it.
     *
     * This is why a plain check-then-setState would be wrong: stop() can land
     * between the check and the write, and the late token would resurrect a
     * Responding state after the engine had already gone Idle.
     */
    private fun publishPartial(partial: String) {
        while (true) {
            val current = _stateRef.get()
            if (current !is AIInferenceState.Thinking &&
                current !is AIInferenceState.Responding) return   // terminal — stop here
            val next = AIInferenceState.Responding(partial)
            if (_stateRef.compareAndSet(current, next)) {
                _state.value = next
                return
            }
            // CAS lost a race; loop re-reads and re-evaluates.
        }
    }

    override suspend fun loadModel(modelPath: String) {
        setState(AIInferenceState.Loading)
        Log.i(TAG, "Loading model: $modelPath")
        val ok = LlamaJniBridge.loadModel(modelPath, N_CTX, N_THREADS)
        setState(if (ok) {
            Log.i(TAG, "Model loaded successfully")
            AIInferenceState.Idle
        } else {
            Log.e(TAG, "Model load failed")
            AIInferenceState.Error("Failed to load AI model")
        })
    }

    override fun generate(
        history: List<ChatMessage>,
        userInput: String,
        systemPrompt: String
    ): Flow<String> =
        callbackFlow {
            setState(AIInferenceState.Thinking)
            val prompt = PromptFormatter.buildPrompt(history, userInput, systemPrompt)
            Log.d(TAG, "Prompt length: ${prompt.length} chars")

            // Accumulates the response so AIInferenceState.Responding.partialText
            // actually carries the live text. Only ever touched by the single C++
            // generation thread (serialized by g_gen_mutex), so no lock needed here.
            val partial = StringBuilder()

            LlamaJniBridge.generate(
                prompt    = prompt,
                maxTokens = MAX_TOKENS,
                callback  = object : LlamaCallback {
                    override fun onToken(token: String) {
                        partial.append(token)
                        // Advances Thinking→Responding on the first token, then keeps
                        // partialText current on every subsequent token. Bails out
                        // safely if a terminal state was set concurrently.
                        publishPartial(partial.toString())
                        trySend(token)
                    }
                    override fun onComplete() {
                        Log.i(TAG, "Generation complete")
                        setState(AIInferenceState.Idle)
                        // Guard: C++ may call onComplete after Kotlin cancellation
                        // already closed the channel — avoid ClosedSendChannelException.
                        if (!channel.isClosedForSend) channel.close()
                    }
                    override fun onError(message: String) {
                        Log.e(TAG, "Generation error: $message")
                        setState(AIInferenceState.Error(message))
                        if (!channel.isClosedForSend) channel.close(Exception(message))
                    }
                }
            )

            awaitClose {
                Log.d(TAG, "Flow closed — signalling stop")
                LlamaJniBridge.stopGeneration()
            }
        }.buffer(Channel.UNLIMITED)

    override fun stop() {
        LlamaJniBridge.stopGeneration()
        setState(AIInferenceState.Idle)
    }

    override fun unload() {
        LlamaJniBridge.unloadModel()
        setState(AIInferenceState.Idle)
    }

    override fun isReady(): Boolean = LlamaJniBridge.isModelLoaded()
}
