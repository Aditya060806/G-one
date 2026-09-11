package com.infinity.ai.ai.prompts

import com.infinity.ai.model.ChatMessage

/**
 * PromptFormatter
 *
 * Qwen 2.5 uses the ChatML prompt format:
 *
 *   <|im_start|>system
 *   You are a helpful assistant.<|im_end|>
 *   <|im_start|>user
 *   Hello!<|im_end|>
 *   <|im_start|>assistant
 *   Hi there!<|im_end|>
 *   <|im_start|>assistant
 *   ← generation starts here
 *
 * If the format is wrong, the model will produce garbage output.
 * This formatter ensures the exact format Qwen 2.5 expects.
 */
object PromptFormatter {

    /**
     * Conversational framing for G-one, used by chat and the document features.
     *
     * Public so callers can fall back to it explicitly, and so a caller that needs
     * different framing can substitute its own. The alert path does exactly that:
     * `HealthPromptBuilder.SYSTEM_PROMPT` applies far stricter rules, because
     * restating a detected event is a narrower and higher-stakes task than chatting.
     * The two are deliberately separate — do not merge them.
     *
     * DELIBERATELY SHORT. Every caller pays for this prompt on every single
     * generation, and the context window is 2048 tokens shared with chat history and
     * up to 800 characters of extracted document text. A thorough-sounding
     * 300-token persona would measurably shorten how much conversation fits.
     *
     * The safety clauses are not decoration. This model is a 1.5B parameter network
     * being asked health-adjacent questions; without an explicit refusal boundary it
     * will confidently invent diagnoses and dosages. Naming the red-flag symptoms
     * explicitly matters more than a general "be careful" instruction, because it
     * gives the model concrete triggers to escalate on rather than leaving the
     * judgement to it.
     */
    const val DEFAULT_SYSTEM_PROMPT: String =
        "You are G-one, a calm personal health companion running entirely offline " +
            "on the user's device. Explain health information in plain language a " +
            "person with no medical training can follow. " +
            "Never diagnose a condition, and never name a medicine or a dose. " +
            "If the user describes chest pain, trouble breathing, fainting, heavy " +
            "bleeding, or signs of a stroke, tell them plainly to seek emergency " +
            "care now instead of explaining further. " +
            "You are not a doctor and must say so if asked for a diagnosis. " +
            "For everyday non-health requests, just help normally. " +
            "Be direct and concise."

    /**
     * Build the full prompt string from chat history.
     *
     * @param history  list of previous messages (user + assistant)
     * @param newInput the new user message to respond to
     * @param systemPrompt framing for the model. Defaults to
     *   [DEFAULT_SYSTEM_PROMPT], so every existing caller is unaffected.
     * @return formatted prompt string ready for llama.cpp
     */
    fun buildPrompt(
        history: List<ChatMessage>,
        newInput: String,
        systemPrompt: String = DEFAULT_SYSTEM_PROMPT
    ): String {
        val sb = StringBuilder()

        // System message — sets the AI's personality
        sb.append("<|im_start|>system\n")
        sb.append(systemPrompt)
        sb.append("<|im_end|>\n")

        // Chat history — last N messages to fit in context window
        // We keep the last 10 exchanges to avoid exceeding 2048 tokens
        val recentHistory = history.takeLast(20)
        for (msg in recentHistory) {
            val role = if (msg.isUser) "user" else "assistant"
            sb.append("<|im_start|>$role\n")
            sb.append(msg.text)
            sb.append("<|im_end|>\n")
        }

        // New user message
        sb.append("<|im_start|>user\n")
        sb.append(newInput)
        sb.append("<|im_end|>\n")

        // Assistant turn start — model generates from here
        sb.append("<|im_start|>assistant\n")

        return sb.toString()
    }
}
