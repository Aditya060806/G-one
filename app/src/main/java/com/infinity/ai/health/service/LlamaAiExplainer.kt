package com.infinity.ai.health.service

import android.util.Log
import com.infinity.ai.ai.repository.AIRepository
import com.infinity.ai.ai.state.AIInferenceState
import com.infinity.ai.health.domain.AnomalyEvidence
import com.infinity.ai.health.explain.Explanation
import com.infinity.ai.health.explain.HealthPromptBuilder
import com.infinity.ai.model.ChatMessage
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withTimeout

/**
 * Rewrites a deterministic explanation using the on-device model.
 *
 * EVERY FAILURE PATH RETURNS null, WHICH MEANS "KEEP THE TEMPLATE".
 *
 * Model not loaded, engine in an error state, inference times out, output fails
 * validation, an exception anywhere — all of it collapses to null, and the
 * deterministic explanation the user already saw simply stands. There is deliberately
 * no path where a failure here degrades the alert.
 *
 * The timeout is far shorter than the chat features' 3-minute first-token watchdog.
 * That is intentional: chat has a user sitting there willing to wait, whereas this is
 * cosmetic polish on an alert that has already been delivered. Holding the single
 * mutex-serialized inference slot for minutes would block the next anomaly's rewrite
 * for no benefit.
 */
class LlamaAiExplainer(
    private val repository: AIRepository,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
) : AiExplainer {

    override suspend fun explain(evidence: AnomalyEvidence, template: Explanation): String? {
        if (!repository.isReady()) {
            Log.i(TAG, "Model not ready — keeping deterministic explanation")
            return null
        }
        if (repository.aiState.value is AIInferenceState.Error) {
            Log.w(TAG, "Engine in error state — keeping deterministic explanation")
            return null
        }

        val userPrompt = HealthPromptBuilder.buildUserPrompt(evidence, template)

        return try {
            val sb = StringBuilder()
            withTimeout(timeoutMillis) {
                repository.generate(
                    history      = emptyList<ChatMessage>(),
                    userInput    = userPrompt,
                    systemPrompt = HealthPromptBuilder.SYSTEM_PROMPT
                )
                    .catch { e -> Log.w(TAG, "Generation failed: ${e.message}") }
                    .collect { sb.append(it) }
            }

            val validated = HealthPromptBuilder.validate(sb.toString(), evidence)
            if (validated == null) {
                Log.w(
                    TAG,
                    "Model output rejected by validation (${sb.length} chars) — " +
                        "keeping deterministic explanation"
                )
            }
            validated
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Explanation timed out after ${timeoutMillis}ms — keeping template")
            repository.stop()
            null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during explanation", e)
            null
        }
    }

    companion object {
        private const val TAG = "LlamaAiExplainer"
        const val DEFAULT_TIMEOUT_MILLIS = 45_000L
    }
}
