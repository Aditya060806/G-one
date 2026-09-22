package com.gone.ai.quiz

/** One multiple-choice question read from the model's quiz. */
data class QuizQuestion(
    val number: Int,
    val question: String,
    val options: List<String>,
    /** Index into [options]. */
    val correctIndex: Int,
    val explanation: String?
)

/**
 * Turns the model's quiz text into questions a person can answer.
 *
 * The prompt asks for a strict layout, but a 1.5B model drifts: bold markers, "Question 1:",
 * "(b)", "Correct answer: B". The parser accepts those variations and drops any question it
 * cannot trust — fewer than two options, or an answer letter that names no option. If none
 * survive, the screen falls back to showing the text as written.
 */
object QuizParser {

    /** Asks for the layout [parse] reads best. Used by the Quiz tool. */
    const val PROMPT: String =
        "Write exactly 5 multiple-choice questions about the text below, using only facts stated in it. " +
            "Use exactly this format for each question, with a blank line between questions:\n" +
            "Q1. question\n" +
            "A) option\n" +
            "B) option\n" +
            "C) option\n" +
            "D) option\n" +
            "Answer: letter\n" +
            "Why: one sentence from the text\n\n" +
            "Text:"

    private val QUESTION = Regex("""^(?:Q(?:uestion)?\s*)?(\d{1,2})\s*[.):\-]\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val OPTION = Regex("""^\(?([A-D])\s*[).:\]]\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val ANSWER = Regex("""^(?:correct\s+)?answer\s*(?:is)?\s*[:\-]?\s*\(?([A-D])\b.*$""", RegexOption.IGNORE_CASE)
    private val WHY = Regex("""^(?:why|explanation|reason)\s*[:\-]\s*(.+)$""", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<QuizQuestion> {
        val questions = mutableListOf<QuizQuestion>()
        var draft: Draft? = null

        fun close() {
            draft?.toQuestion()?.let { questions += it }
            draft = null
        }

        for (line in text.lines().map(::clean).flatMap(::splitInlineOptions)) {
            if (line.isEmpty()) continue

            val answer = ANSWER.find(line)
            val why = WHY.find(line)
            val option = OPTION.find(line)
            val question = QUESTION.find(line)
            val current = draft
            when {
                answer != null && current != null -> current.answer = answer.groupValues[1].uppercase()[0] - 'A'
                why != null && current != null -> current.why = why.groupValues[1].trim()
                option != null && current != null && current.answer == null -> {
                    val index = option.groupValues[1].uppercase()[0] - 'A'
                    if (index == current.options.size) current.options += option.groupValues[2].trim()
                }
                question != null -> {
                    close()
                    draft = Draft(question.groupValues[1].toInt(), question.groupValues[2].trim())
                }
                // A question the model wrote over two lines.
                current != null && current.options.isEmpty() -> current.text = current.text + " " + line
            }
        }
        close()
        return questions
    }

    private val INLINE_OPTION = Regex("""(?<=^|\s)\(?([A-D])[).]\s+""")

    /**
     * "A) x B) y C) z D) w" on one line becomes four option lines. Split only on letters in
     * order, so an option such as "B) Vitamin A. deficiency" is left whole.
     */
    private fun splitInlineOptions(line: String): List<String> {
        if (!OPTION.matches(line)) return listOf(line)
        val marks = INLINE_OPTION.findAll(line).toList()
        if (marks.size < 2 || marks.first().range.first != 0) return listOf(line)
        val inOrder = marks.zipWithNext().all { (a, b) -> b.groupValues[1][0] - a.groupValues[1][0] == 1 }
        if (!inOrder) return listOf(line)
        return marks.mapIndexed { i, mark ->
            line.substring(mark.range.first, if (i + 1 < marks.size) marks[i + 1].range.first else line.length).trim()
        }
    }

    /** Strips list and emphasis markup the model adds around otherwise valid lines. */
    private fun clean(line: String): String = line.trim()
        .removePrefix("- ")
        .replace("**", "")
        .replace("__", "")
        .trim()

    private class Draft(val number: Int, var text: String) {
        val options = mutableListOf<String>()
        var answer: Int? = null
        var why: String? = null

        fun toQuestion(): QuizQuestion? {
            val correct = answer ?: return null
            if (options.size < 2 || correct !in options.indices || text.isBlank()) return null
            return QuizQuestion(number, text, options.toList(), correct, why?.takeIf { it.isNotBlank() })
        }
    }
}
