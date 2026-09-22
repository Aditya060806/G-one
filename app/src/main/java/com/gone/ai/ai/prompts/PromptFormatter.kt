package com.gone.ai.ai.prompts

import com.gone.ai.model.ChatMessage

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
 *
 * Everything here is pure: token counting is passed in as a function, so the budget
 * logic is unit-testable without the native engine.
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
     * generation, and the context window is shared with chat history, personal context,
     * attached documents and the answer itself. A thorough-sounding 300-token
     * persona would measurably shorten how much conversation fits.
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

    /** Most history messages ever considered, before the token budget trims further. */
    const val MAX_HISTORY_MESSAGES = 20

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

        sb.append("<|im_start|>system\n")
        sb.append(systemPrompt)
        sb.append("<|im_end|>\n")

        for (msg in history.takeLast(MAX_HISTORY_MESSAGES)) {
            val role = if (msg.isUser) "user" else "assistant"
            sb.append("<|im_start|>$role\n")
            sb.append(msg.text)
            sb.append("<|im_end|>\n")
        }

        sb.append("<|im_start|>user\n")
        sb.append(newInput)
        sb.append("<|im_end|>\n")

        // Assistant turn start — model generates from here
        sb.append("<|im_start|>assistant\n")

        return sb.toString()
    }

    /**
     * Build a prompt that fits [tokenBudget], dropping the OLDEST history first.
     *
     * A fixed message count was not a budget: twenty long messages overflow the context
     * window, and the native side then failed with "Prompt decode failed" mid-chat. The
     * newest context is what the answer depends on, so trimming starts from the front.
     *
     * The system prompt and the new input are never dropped. If they alone exceed the
     * budget, the prompt is returned as-is and the engine rejects it with a clear error
     * rather than this function silently rewriting what the user asked.
     *
     * @param countTokens token count for a prompt, or a negative value when unknown
     *   (no model loaded) — in which case the untrimmed prompt is returned.
     */
    fun buildPromptWithinBudget(
        history: List<ChatMessage>,
        newInput: String,
        systemPrompt: String,
        tokenBudget: Int,
        countTokens: (String) -> Int
    ): String {
        var kept = history.takeLast(MAX_HISTORY_MESSAGES)
        while (true) {
            val prompt = buildPrompt(kept, newInput, systemPrompt)
            if (kept.isEmpty()) return prompt
            val tokens = countTokens(prompt)
            if (tokens < 0 || tokens <= tokenBudget) return prompt
            kept = kept.drop(1)
        }
    }

    /** Result of [truncateToTokenBudget]. */
    data class Fitted(val text: String, val truncated: Boolean)

    /**
     * The longest prefix of [text] that fits [tokenBudget] tokens.
     *
     * Replaces a blind 800-character cap. Characters are a poor proxy for tokens — clean
     * prose runs about four characters a token while PDF and OCR debris can run close to
     * one — so the fixed cap either wasted most of the window or overflowed it. Binary
     * search on the real tokenizer costs a dozen short tokenizations.
     *
     * The cut backs off to a nearby word boundary so the model is not handed half a word,
     * and never splits a surrogate pair.
     */
    fun truncateToTokenBudget(text: String, tokenBudget: Int, countTokens: (String) -> Int): Fitted {
        fun fits(candidate: String): Boolean {
            val n = countTokens(candidate)
            return n < 0 || n <= tokenBudget
        }
        if (fits(text)) return Fitted(text, truncated = false)

        var lo = 0              // invariant: text.take(lo) fits
        var hi = text.length    // invariant: text.take(hi + 1) does not (or hi == length)
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (fits(text.substring(0, mid))) lo = mid else hi = mid - 1
        }

        var cut = lo
        val boundary = text.lastIndexOfAny(charArrayOf(' ', '\n', '\t'), startIndex = (cut - 1).coerceAtLeast(0))
        if (boundary > 0 && boundary >= cut * 0.8) cut = boundary
        if (cut > 0 && text[cut - 1].isHighSurrogate()) cut--

        return Fitted(text.substring(0, cut).trimEnd(), truncated = true)
    }
}
