package com.gone.ai.pdf

/**
 * Whether text pulled out of a PDF is something a person could read.
 *
 * PDFs store characters in many encodings. The old content-stream parser returned glyph
 * codes for fonts it could not map (""), words glued together with no
 * spaces ("Thepatientwasadmitted"), or nothing. Each looks like "some text" to a length
 * check, and summarising it produced confident nonsense. These checks decide whether to
 * trust the text or read the page as an image instead.
 */
object TextQuality {

    /** Fewer letters than this is treated as an empty page (a page number, a stray header). */
    const val MIN_LETTERS = 20

    fun isReadable(text: String): Boolean {
        val letters = text.count { it.isLetter() }
        if (letters < MIN_LETTERS) return false

        // Mostly letters, digits, spaces and ordinary punctuation. Combining marks count as
        // letters: Devanagari vowel signs, for example, are marks, not letters.
        val ordinary = text.count { it.isLetterOrDigit() || it.isWhitespace() || it.isMark() || it in ORDINARY_PUNCTUATION }
        if (ordinary < text.length * 0.85) return false

        // Words of plausible length: glued-together text has almost no spaces.
        val words = text.split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val averageLength = words.sumOf { it.length }.toDouble() / words.size
        if (averageLength > 12.0) return false

        // Mostly real words or numbers rather than symbol runs. Numbers count: a lab report
        // is half figures.
        val wordLike = words.count { word -> word.count { it.isLetterOrDigit() || it.isMark() } >= word.length / 2 }
        return wordLike >= words.size * 0.5
    }

    private fun Char.isMark() = category == CharCategory.NON_SPACING_MARK ||
        category == CharCategory.COMBINING_SPACING_MARK || category == CharCategory.ENCLOSING_MARK

    private val WHITESPACE = Regex("\\s+")
    private const val ORDINARY_PUNCTUATION = ".,;:!?'\"()[]{}-–—/%+*=<>&#@°µ•·…’‘“”_|~^$€£¥₹§"
}
