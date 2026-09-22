package com.gone.ai.health.service

import android.util.Log
import com.gone.ai.ai.repository.AIRepository
import com.gone.ai.health.domain.AnomalyEvidence
import com.gone.ai.health.explain.Explanation
import com.gone.ai.health.explain.HealthPromptBuilder
import com.gone.ai.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Rewrites a deterministic explanation using the on-device model.
 *
 * EVERY FAILURE PATH RETURNS null, WHICH MEANS "KEEP THE TEMPLATE".
 *
 * Model not loaded, inference times out, generation fails, output fails validation, an
 * exception anywhere — all of it collapses to null, and the deterministic explanation
 * the user already saw simply stands. There is deliberately no path where a failure
 * here degrades the alert.
 *
 * The timeout is far shorter than the chat features' 3-minute first-token watchdog.
 * That is intentional: chat has a user sitting there willing to wait, whereas this is
 * cosmetic polish on an alert that has already been delivered.
 *
 * Timing out cancels THIS request only. It used to call a shared stop that also killed
 * whatever chat reply the user was reading.
 */
class LlamaAiExplainer(
    private val repository: AIRepository,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
) : AiExplainer {

    override suspend fun explain(evidence: AnomalyEvidence, template: Explanation): String? {
        // Never trigger a model load just to polish wording; monitoring warms the model.
        if (!repository.isReady()) {
            Log.i(TAG, "Model not ready — keeping deterministic explanation")
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
                ).collect { sb.append(it) }
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
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed generation must not have its partial output validated and shown.
            Log.w(TAG, "Explanation failed: ${e.message} — keeping template")
            null
        }
    }

    companion object {
        private const val TAG = "LlamaAiExplainer"
        const val DEFAULT_TIMEOUT_MILLIS = 45_000L
    }
}
