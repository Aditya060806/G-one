package com.gone.ai.ocr

import android.util.Log
import com.gone.ai.ai.prompts.PromptFormatter
import com.gone.ai.ai.repository.AIRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * AiTextProcessor
 *
 * Runs one document-based prompt through the model, with a token budget for the document
 * and watchdogs for a stalled or runaway model. [DocumentTask] builds on it for every
 * tool: PDF, OCR, screenshot, quiz and Circle Learn.
 *
 * STOPPING: callers stop a generation by cancelling the coroutine running [stream]. That
 * cancels this request only. The watchdogs stop it the same way.
 */
object AiTextProcessor {

    private const val TAG = "AiTextProcessor"

    /** Fires only if zero tokens arrive — guards a stalled prefill. */
    const val FIRST_TOKEN_TIMEOUT_MS = 180_000L

    /** Stops a generation still running after this long, keeping what it produced. */
    const val TOTAL_GENERATION_TIMEOUT_MS = 300_000L

    /**
     * Most document tokens placed in one prompt.
     *
     * Roughly 130 tokens go to the system prompt and scaffolding and 512 are reserved for
     * the answer. Prefill time on a phone CPU grows with every document token, so this is
     * kept well below what the window could hold. Longer documents are summarised part by
     * part instead (see [DocumentChunks]).
     */
    const val DOCUMENT_TOKEN_BUDGET = 640

    /**
     * Characters an extractor keeps before token fitting. A generous ceiling so a
     * pathological file cannot make tokenization itself slow; the token budget above is
     * what actually decides how much text the model sees in one prompt.
     */
    const val MAX_EXTRACTED_CHARS = 8_000

    /** A prompt ready to run, plus what was done to fit it. */
    data class PreparedPrompt(
        val prompt: String,
        /** The document text as it appears in the prompt. */
        val document: String,
        /** True when the document was cut to fit the budget. */
        val truncated: Boolean
    )

    /** How a generation that was not cancelled ended. */
    sealed interface Outcome {
        /** The model finished its answer. */
        data object Done : Outcome
        /** The answer was cut short by a timeout or an error, but some text exists. */
        data object Partial : Outcome
        data class Failed(val message: String) : Outcome
    }

    private enum class Timeout { FIRST_TOKEN, TOTAL }

    /**
     * Build `instruction + document`, cutting the document to [tokenBudget] using the real
     * tokenizer. Loads the model if needed; throws if it cannot be loaded.
     */
    suspend fun prepare(
        repository: AIRepository,
        instruction: String,
        document: String,
        tokenBudget: Int = DOCUMENT_TOKEN_BUDGET
    ): PreparedPrompt {
        repository.initialize().getOrThrow()
        val fitted = withContext(Dispatchers.IO) {
            PromptFormatter.truncateToTokenBudget(
                text        = document,
                tokenBudget = tokenBudget,
                countTokens = repository::countTokensNow
            )
        }
        val prompt = "$instruction\n\n${fitted.text}"
        Log.i(TAG, "Prompt: ${prompt.length} chars, document truncated=${fitted.truncated}")
        return PreparedPrompt(prompt, fitted.text, fitted.truncated)
    }

    /**
     * Stream [prompt] through [generate] into [outputFlow] and report how it ended.
     *
     * Cancelling the calling coroutine (the user pressed Stop, the screen closed) cancels
     * the request and rethrows; nothing is reported, because the caller already knows.
     * Text produced so far is never cleared.
     *
     * @param onToken called with the running token count after each token.
     */
    suspend fun stream(
        generate    : (String) -> Flow<String>,
        prompt      : String,
        outputFlow  : MutableStateFlow<String>,
        onToken     : ((Int) -> Unit)? = null
    ): Outcome {
        val tokenCount = AtomicInteger(0)
        val failure = AtomicReference<Throwable?>(null)
        val timeout = AtomicReference<Timeout?>(null)

        Log.i(TAG, "Generation started — prompt ${prompt.length} chars")

        coroutineScope {
            val collector = launch {
                try {
                    generate(prompt).collect { token ->
                        val n = tokenCount.incrementAndGet()
                        outputFlow.update { it + token }
                        onToken?.invoke(n)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure.set(e)
                }
            }

            // Cancelling the collector cancels the native request, and only that one.
            val watchdog = launch {
                delay(FIRST_TOKEN_TIMEOUT_MS)
                if (tokenCount.get() == 0) {
                    timeout.set(Timeout.FIRST_TOKEN)
                    collector.cancel()
                    return@launch
                }
                delay(TOTAL_GENERATION_TIMEOUT_MS - FIRST_TOKEN_TIMEOUT_MS)
                timeout.set(Timeout.TOTAL)
                collector.cancel()
            }

            collector.join()
            watchdog.cancel()
        }

        val hasText = outputFlow.value.isNotBlank()
        val error = failure.get()
        Log.i(TAG, "Generation finished — tokens=${tokenCount.get()} timeout=${timeout.get()} error=${error?.message}")

        return when {
            timeout.get() == Timeout.FIRST_TOKEN -> Outcome.Failed("Model did not respond. Please try again.")
            timeout.get() == Timeout.TOTAL ->
                if (hasText) Outcome.Partial else Outcome.Failed("Generation timed out. Please try again.")
            error != null ->
                if (hasText) Outcome.Partial
                else Outcome.Failed(error.message?.let { "Generation failed: $it" } ?: "Generation failed. Please try again.")
            !hasText -> Outcome.Failed("No response generated. Please try again.")
            else -> Outcome.Done
        }
    }
}
