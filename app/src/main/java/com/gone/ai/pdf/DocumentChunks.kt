package com.gone.ai.pdf

/**
 * Splits a long document into parts that each fit one prompt, for "whole document" summaries.
 *
 * A part ends at a paragraph break when one is near, otherwise at a sentence end, otherwise at
 * a space, so the model is not handed half a sentence. Lengths are in characters: roughly four
 * characters to a token for prose, which keeps [TARGET_CHARS] well inside the document budget,
 * and the real tokenizer still trims any part that turns out denser.
 */
object DocumentChunks {

    /** About 550 tokens of prose: below the 640-token document budget with room to spare. */
    const val TARGET_CHARS = 2_200

    /** Most parts summarised in one run; ten parts already take several minutes on a phone. */
    const val MAX_PARTS = 10

    /** The parts to summarise, and whether the document had more than [MAX_PARTS] could hold. */
    data class Plan(val parts: List<String>, val truncated: Boolean)

    fun plan(text: String, targetChars: Int = TARGET_CHARS, maxParts: Int = MAX_PARTS): Plan {
        val clean = text.trim()
        if (clean.isEmpty()) return Plan(emptyList(), truncated = false)
        val parts = mutableListOf<String>()
        var start = 0
        while (start < clean.length && parts.size < maxParts) {
            val remaining = clean.length - start
            if (remaining <= targetChars) {
                parts += clean.substring(start).trim()
                start = clean.length
                break
            }
            val end = cutPoint(clean, start, start + targetChars)
            parts += clean.substring(start, end).trim()
            start = end
            while (start < clean.length && clean[start].isWhitespace()) start++
        }
        return Plan(parts.filter { it.isNotEmpty() }, truncated = start < clean.length)
    }

    /** True when [text] needs more than one part. */
    fun isLong(text: String, targetChars: Int = TARGET_CHARS) = text.trim().length > targetChars

    /** The best place to end a part that must end by [limit]; never earlier than half way. */
    private fun cutPoint(text: String, start: Int, limit: Int): Int {
        val floor = start + (limit - start) / 2
        val window = text.substring(start, limit)
        val paragraph = window.lastIndexOf("\n\n")
        if (paragraph >= 0 && start + paragraph > floor) return start + paragraph
        val sentence = SENTENCE_END.findAll(window).lastOrNull()
        if (sentence != null && start + sentence.range.last + 1 > floor) return start + sentence.range.last + 1
        val space = window.lastIndexOfAny(charArrayOf(' ', '\n', '\t'))
        if (space >= 0 && start + space > floor) return start + space
        return limit
    }

    private val SENTENCE_END = Regex("""[.!?](?=\s)""")
}
