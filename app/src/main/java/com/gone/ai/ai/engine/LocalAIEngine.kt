package com.gone.ai.ai.engine

import com.gone.ai.ai.prompts.PromptFormatter
import com.gone.ai.ai.state.AIInferenceState
import com.gone.ai.model.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * LocalAIEngine
 *
 * This interface defines the contract for local AI inference.
 * The Repository talks to this interface — it never knows about llama.cpp directly.
 *
 * This design means:
 * - We can swap llama.cpp for another engine without touching the ViewModel
 * - We can create a FakeAIEngine for testing
 * - The architecture stays clean
 */
interface LocalAIEngine {

    /**
     * Engine-wide state: Loading while a model loads, Thinking/Responding while ANY
     * request is in flight, Error when the model could not be loaded.
     *
     * Several components share one engine, so this is not "the state of my request".
     * A screen showing its own progress should track its own collection instead.
     */
    val state: StateFlow<AIInferenceState>

    /**
     * Load the model from the given file path into memory.
     * @return true on success
     */
    suspend fun loadModel(modelPath: String): Boolean

    /**
     * Generate a response. A cold flow: collecting it starts one request, and
     * cancelling the collector cancels exactly that request and nothing else.
     *
     * The flow completes when generation ends and fails with an exception when the
     * request fails. History is trimmed oldest-first to fit the context window.
     *
     * @param history  previous chat messages for context
     * @param userInput the new user message
     * @param systemPrompt model framing; the health-explanation path overrides it to
     *   install medical guardrails at the system level.
     */
    fun generate(
        history: List<ChatMessage>,
        userInput: String,
        systemPrompt: String = PromptFormatter.DEFAULT_SYSTEM_PROMPT
    ): Flow<String>

    /** Cancel every request and free the model. Blocking; call off the main thread. */
    fun unload()

    /** Returns true if the model is loaded and ready to generate */
    fun isReady(): Boolean

    /** Token count of [text], or -1 when no model is loaded. */
    fun countTokens(text: String): Int
}
