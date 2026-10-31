package com.gone.ai.health.insight

/**
 * The language model's part in "Ask your health": reading an unusual question into a
 * [HealthQuery], when [QuestionParser] could not.
 *
 * PURE KOTLIN. The model is asked for one line in a fixed form and nothing else, and
 * [parseModelLine] accepts only words from a fixed list. Anything else, including any number
 * other than a period length, is rejected and the wearer is asked to rephrase. So the model
 * picks what to look up; the database supplies every value in the answer.
 */
object QueryInterpreter {

    const val NONE = "none"

    fun prompt(question: String): String = """
        Turn the health question into one line, exactly in this form:
        metric=<metric>; op=<op>; range=<range>
        metric is one of: ${InsightMetric.entries.joinToString(", ") { it.key }}
        op is one of: ${InsightOp.entries.joinToString(", ") { it.key }}
        range is one of: today, yesterday, last_night, this_week, last_week, hours:N, days:N
        If the question is not about these measurements, write: $NONE
        Write only the line. Do not answer the question.

        Example: How was my oxygen during the night?
        metric=spo2; op=trend; range=last_night
        Example: Kya maine is hafte achhi neend li?
        metric=sleep; op=average; range=this_week
        Example: Did I fall today?
        metric=alerts; op=count; range=today

        Question: ${question.replace('\n', ' ').take(MAX_QUESTION_CHARS)}
    """.trimIndent()

    /** The model's reply as a query, or null if it said [NONE] or anything not in the allowed form. */
    fun parseModelLine(reply: String): HealthQuery? {
        val line = reply.lineSequence()
            .map { it.trim().trim('`', '"', '.').lowercase() }
            .firstOrNull { it.startsWith("metric") || it == NONE }
            ?: return null
        if (line == NONE) return null

        val fields = line.split(';').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq < 0) null else part.substring(0, eq).trim() to part.substring(eq + 1).trim()
        }.toMap()
        if (fields.keys != setOf("metric", "op", "range")) return null

        val metric = InsightMetric.fromKey(fields.getValue("metric")) ?: return null
        var op = InsightOp.fromKey(fields.getValue("op")) ?: return null
        val range = range(fields.getValue("range")) ?: return null
        if (op == InsightOp.COUNT && metric != InsightMetric.ALERTS) op = InsightOp.TREND
        return HealthQuery(metric, op, range)
    }

    private fun range(text: String): TimeRange? = when (text) {
        "today" -> TimeRange.Today
        "yesterday" -> TimeRange.Yesterday
        "last_night" -> TimeRange.LastNight
        "this_week" -> TimeRange.ThisWeek
        "last_week" -> TimeRange.PreviousWeek
        else -> {
            val match = NUMBERED.matchEntire(text)
            val n = match?.groupValues?.get(2)?.toIntOrNull()
            when {
                match == null || n == null || n < 1 -> null
                match.groupValues[1] == "hours" -> TimeRange.LastHours(n.coerceAtMost(TimeRange.MAX_DAYS * 24))
                else -> TimeRange.LastDays(n.coerceAtMost(TimeRange.MAX_DAYS))
            }
        }
    }

    /** Questions the model reads are kept short; long text is not a question. */
    private const val MAX_QUESTION_CHARS = 300
    private val NUMBERED = Regex("""(hours|days):(\d{1,4})""")
}
