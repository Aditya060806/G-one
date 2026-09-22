package com.gone.ai.health.service

import android.util.Log
import com.gone.ai.ai.repository.AIRepository
import com.gone.ai.model.ChatMessage
import com.gone.ai.health.data.SessionReportEntity
import com.gone.ai.health.session.ReportSummaryPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Rewords a stored report's observations with the on-device model, on request.
 *
 * Only ever run because the user asked, so unlike alert explanations it may load the model.
 * It holds its own lease while it runs and releases it after, so asking for a summary never
 * leaves the model loaded or unloads it from under chat. REFERENCE called a global
 * `engine.stop()` here, cancelling whatever else was generating.
 */
class LlamaReportSummarizer(
    private val repository: AIRepository,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
) {

    /** Outcome shown to the user. */
    sealed interface Result {
        data class Written(val text: String) : Result
        data class NotWritten(val reason: String) : Result
    }

    suspend fun summarize(report: SessionReportEntity): Result {
        val observations = report.observationLines
        if (observations.isEmpty()) return Result.NotWritten("This report has nothing to summarise.")

        repository.acquire("session-report-summary").use {
            val output = StringBuilder()
            return try {
                withTimeout(timeoutMillis) {
                    repository.generate(
                        history = emptyList<ChatMessage>(),
                        userInput = ReportSummaryPrompt.userPrompt(observations),
                        systemPrompt = ReportSummaryPrompt.SYSTEM_PROMPT
                    ).collect { output.append(it) }
                }
                ReportSummaryPrompt.validate(output.toString(), observations)
                    ?.let { Result.Written(it) }
                    ?: Result.NotWritten(
                        "The model's wording did not pass the safety check, so the report keeps its original text."
                    ).also { Log.w(TAG, "Summary rejected by validation (${output.length} chars)") }
            } catch (e: TimeoutCancellationException) {
                Result.NotWritten("The on-device model took too long. The report keeps its original text.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Summary failed", e)
                Result.NotWritten("The on-device model is not available: ${e.message ?: "unknown error"}")
            }
        }
    }

    companion object {
        private const val TAG = "ReportSummarizer"
        /** Includes loading the model if nothing else has it loaded. */
        const val DEFAULT_TIMEOUT_MILLIS = 120_000L
    }
}
