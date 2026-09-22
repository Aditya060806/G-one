package com.gone.ai.pdf

import com.gone.ai.ocr.AiTextProcessor
import com.gone.ai.ocr.DocumentTask

/**
 * Summarises a document too long for one prompt: short notes on each part, then one summary
 * written from the notes.
 *
 * The model only ever sees one part at a time, so nothing is silently dropped the way a single
 * prompt drops everything after its budget. It is slower — one generation per part plus one —
 * which is why the person chooses it over the quick summary of the first part.
 */
object WholeDocumentSummary {

    const val QUICK_PROMPT = "Summarize the following document in 5 concise bullet points."

    const val PART_PROMPT =
        "Summarize this part of a longer document in 3 short bullet points. Keep any numbers exactly as written."

    const val COMBINE_PROMPT =
        "Below are notes on each part of one document, in order. Using only these notes, write a " +
            "summary of the whole document in 5 concise bullet points. Keep numbers exactly as written."

    /** Notes from ten parts run to about a thousand tokens; the window has room for more. */
    const val NOTES_TOKEN_BUDGET = 1_600

    suspend fun run(steps: DocumentTask.Steps, parts: List<String>): AiTextProcessor.Outcome {
        if (parts.isEmpty()) return AiTextProcessor.Outcome.Failed("The document has no text to summarise.")
        if (parts.size == 1) return steps.generate(QUICK_PROMPT, parts.single(), visible = true).outcome

        val notes = mutableListOf<String>()
        var incomplete = false
        parts.forEachIndexed { index, part ->
            steps.stage("Reading part ${index + 1} of ${parts.size}")
            val result = steps.generate(PART_PROMPT, part, visible = false)
            when (val outcome = result.outcome) {
                is AiTextProcessor.Outcome.Failed -> return AiTextProcessor.Outcome.Failed(
                    "Part ${index + 1} of ${parts.size} could not be read: ${outcome.message}"
                )
                AiTextProcessor.Outcome.Partial -> incomplete = true
                AiTextProcessor.Outcome.Done -> Unit
            }
            if (result.text.isNotBlank()) notes += "Part ${index + 1}:\n${result.text.trim()}"
        }

        steps.stage("Writing the summary")
        val summary = steps.generate(COMBINE_PROMPT, notes.joinToString("\n\n"), visible = true, tokenBudget = NOTES_TOKEN_BUDGET)
        // Notes on a part that was cut short make the summary incomplete, even if it finished.
        return if (summary.outcome == AiTextProcessor.Outcome.Done && incomplete) AiTextProcessor.Outcome.Partial
        else summary.outcome
    }
}
