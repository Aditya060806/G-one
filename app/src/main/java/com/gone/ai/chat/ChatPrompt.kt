package com.gone.ai.chat

import com.gone.ai.ai.prompts.PromptFormatter

/**
 * The system prompt for one chat reply.
 *
 * The base rules always come first. Facts about the person follow, when they allow it, and
 * then a document the conversation is about. Both are fenced and come with instructions to
 * use only what is written and to say when something is missing — a 1.5B model otherwise
 * fills gaps with invented detail.
 */
object ChatPrompt {

    /** Most document tokens sent with each reply; the rest of the window is chat and answer. */
    const val ATTACHMENT_TOKEN_BUDGET = 900

    /** Most personal-context tokens sent with each reply. */
    const val HEALTH_CONTEXT_TOKEN_BUDGET = 600

    /** Characters kept when a document is attached; tokens are fitted later, per reply. */
    const val MAX_ATTACHMENT_CHARS = 12_000

    const val HEALTH_RULES: String =
        "Below are facts about the person you are talking to, from their own data on this phone. " +
            "Use them when a question is about them. Quote their numbers exactly as written. " +
            "If a fact is not listed, say you do not have it — never invent readings, results, " +
            "alerts or history. Say so when data is marked simulated. Do not diagnose."

    fun system(
        attachmentTitle: String? = null,
        attachmentText: String? = null,
        healthContext: String? = null,
        base: String = PromptFormatter.DEFAULT_SYSTEM_PROMPT
    ): String = buildString {
        append(base)
        if (!healthContext.isNullOrBlank()) {
            append("\n\n").append(HEALTH_RULES).append('\n')
            append("--- PERSON START ---\n")
            append(healthContext.trim())
            append("\n--- PERSON END ---")
        }
        if (!attachmentText.isNullOrBlank()) {
            append("\n\nThe user shared a document")
            if (!attachmentTitle.isNullOrBlank()) append(" titled \"").append(attachmentTitle.trim()).append('"')
            append(". Answer questions about it from its text below. If the text does not contain ")
            append("the answer, say so instead of guessing. Quote numbers exactly as written.\n")
            append("--- DOCUMENT START ---\n")
            append(attachmentText.trim())
            append("\n--- DOCUMENT END ---")
        }
    }
}
