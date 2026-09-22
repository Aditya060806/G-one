package com.gone.ai.data.library

/**
 * Turns what a person types into the Vault's search box into a safe full-text query.
 *
 * SQLite FTS gives meaning to many characters: `:` names a column, `-` and `NOT` exclude,
 * quotes make phrases, `*` means prefix. Typed as-is — "HbA1c: 6.1", "covid-19" — they
 * produced malformed queries, and the exception escaped the list's Flow and crashed the app.
 * Only letters and digits are kept; each word becomes a prefix match, so "chol" finds
 * "cholesterol".
 */
object LibraryQuery {

    // Marks (\p{M}) stay inside words: Devanagari vowel signs and viramas are marks.
    private val SEPARATORS = Regex("[^\\p{L}\\p{M}\\p{N}]+")

    /** An FTS MATCH expression, or null when nothing searchable was typed. */
    fun ftsMatch(raw: String): String? {
        val terms = raw.split(SEPARATORS).filter { it.isNotEmpty() }
        return if (terms.isEmpty()) null else terms.joinToString(" ") { "$it*" }
    }
}
