package com.gone.ai.voice

import com.gone.ai.ui.components.MarkdownBlocks

/**
 * Turns a reply that is still streaming into sentences a text-to-speech engine can say.
 *
 * Speech starts with the first finished sentence instead of waiting for the whole reply,
 * which on a phone CPU can take most of a minute. Markup is removed ("**", "###", list
 * markers), and code is not read out character by character.
 */
class SpeakableText {

    private var consumed = 0
    private var inCode = false
    private var mentionedCode = false

    /**
     * Sentences completed in [fullText] since the last call. [fullText] is the whole reply so
     * far, as the chat shows it; only what is new is considered.
     */
    fun update(fullText: String): List<String> {
        if (fullText.length < consumed) reset()
        val out = mutableListOf<String>()
        while (true) {
            val boundary = nextBoundary(fullText, consumed) ?: break
            out += speakable(fullText.substring(consumed, boundary))
            consumed = boundary
        }
        return out.filter { it.isNotBlank() }
    }

    /** Whatever remains once the reply is complete. */
    fun finish(fullText: String): List<String> {
        val out = update(fullText).toMutableList()
        if (consumed < fullText.length) {
            out += speakable(fullText.substring(consumed))
            consumed = fullText.length
        }
        return out.filter { it.isNotBlank() }
    }

    fun reset() {
        consumed = 0
        inCode = false
        mentionedCode = false
    }

    /** End of the next sentence or line at or after [from], or null if none is finished yet. */
    private fun nextBoundary(text: String, from: Int): Int? {
        var i = from
        while (i < text.length) {
            if (text.startsWith("```", i)) {
                // A fence is its own segment, so code never merges into a spoken sentence.
                val lineEnd = text.indexOf('\n', i)
                return if (lineEnd < 0) null else lineEnd + 1
            }
            val c = text[i]
            if (c == '\n') return i + 1
            if ((c == '.' || c == '!' || c == '?') && i + 1 < text.length && text[i + 1].isWhitespace() &&
                !(c == '.' && isListNumber(text, i))
            ) return i + 1
            i++
        }
        return null
    }

    /** The "1." of a numbered list line is not the end of a sentence. */
    private fun isListNumber(text: String, dot: Int): Boolean {
        val lineStart = text.lastIndexOf('\n', dot - 1) + 1
        val before = text.substring(lineStart, dot).trim()
        return before.length in 1..3 && before.all { it.isDigit() }
    }

    private fun speakable(segment: String): String {
        val trimmed = segment.trim()
        if (trimmed.startsWith("```")) {
            inCode = !inCode
            if (inCode && !mentionedCode) {
                mentionedCode = true
                return "There is some code in the chat."
            }
            return ""
        }
        if (inCode) return ""
        return clean(trimmed)
    }

    companion object {
        private val LIST_MARKER = Regex("""^([*\-•]|\d{1,3}[.)])\s+""")

        /** One line of markdown as plain speech. */
        fun clean(line: String): String {
            val stripped = line.trim()
                .trimStart('#', '>')
                .trim()
                .replace(LIST_MARKER, "")
            return MarkdownBlocks.inline(stripped).joinToString("") { it.text }
                .replace("SpO₂", "S P O 2")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }
}
