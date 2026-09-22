package com.gone.ai.ai.state

/**
 * AIInferenceState represents the state of AI work, as shown by the orb and status rows.
 *
 * Two scopes use it:
 *  - The ENGINE publishes it for all work in flight (see LocalAIEngine.state). There,
 *    [Responding] carries no text, because several components share the engine.
 *  - A screen may publish its own view of its own request — ChatViewModel does — and
 *    then [Responding.partialText] is that request's text.
 */
sealed class AIInferenceState {

    /** Nothing in flight. Orb: slow breathing. */
    object Idle : AIInferenceState()

    /** Model is being copied from assets or loaded into RAM. Orb: soft pulse. */
    object Loading : AIInferenceState()

    /** A prompt is being processed (prefill phase). Orb: rotating energy rings. */
    object Thinking : AIInferenceState()

    /** Tokens are being generated and streamed. Orb: waveform activity. */
    data class Responding(val partialText: String = "") : AIInferenceState()

    /**
     * The model could not be loaded. Retried automatically on the next request.
     * A single failed generation is reported to its own caller, not here.
     */
    data class Error(val message: String) : AIInferenceState()
}
