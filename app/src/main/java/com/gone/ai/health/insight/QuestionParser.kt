package com.gone.ai.health.insight

import com.gone.ai.health.domain.AnomalyType
import java.text.Normalizer

/**
 * Reads a question in English or Hindi into a [HealthQuery], by fixed rules.
 *
 * PURE KOTLIN. Tried before the language model, so the common questions are answered at once,
 * the same way every time, and without loading the model. Returns null when the question
 * names nothing it can look up; the model is then asked to interpret it.
 *
 * Words are matched whole, not as substrings. In English that keeps "min" out of "minutes";
 * in Hindi it keeps कल (yesterday) out of निकल. A few Hindi stems, such as गिर for गिरा and
 * गिरी, are matched at the start of a word instead.
 */
object QuestionParser {

    /** When no period is named. A week finds something for anyone who wore it recently. */
    val DEFAULT_RANGE: TimeRange = TimeRange.LastDays(7)

    fun parse(question: String): HealthQuery? {
        val text = Text(question)
        val ranges = ranges(text)
        val fallWords = text.has("fall", "falls", "fell", "fallen") || text.hasPrefix("गिर")

        var metric = metric(text) ?: return null
        var op = op(text, ranges.size) ?: defaultOp(metric)
        var alertType: AnomalyType? = null

        // "How many times did I fall?" and "When did I last fall?" are about fall alerts, not
        // about movement readings. "My hardest fall" stays a question about movement.
        if (fallWords && (op == InsightOp.COUNT || op == InsightOp.LATEST) &&
            (metric == InsightMetric.MOVEMENT || metric == InsightMetric.ALERTS)
        ) {
            metric = InsightMetric.ALERTS
            alertType = AnomalyType.FALL_DETECTED
        }
        // Counting only makes sense for alerts; for a reading, show how it went.
        if (op == InsightOp.COUNT && metric != InsightMetric.ALERTS) op = InsightOp.TREND

        return HealthQuery(
            metric = metric,
            op = op,
            range = ranges.firstOrNull() ?: DEFAULT_RANGE,
            compareTo = if (op == InsightOp.COMPARE) ranges.getOrNull(1) else null,
            alertType = alertType
        )
    }

    // ── What is asked about ───────────────────────────────────────────────────

    private fun metric(t: Text): InsightMetric? = when {
        // Before movement, so "muscle activity" is about muscles.
        t.has("muscle", "muscles", "emg", "clench", "clenched") || t.hasPrefix("मांसपेशी", "पेशी") ->
            InsightMetric.MUSCLE
        // Before movement, so हृदय गति (heart rate) is not read as गति (motion).
        t.has("heart rate", "heartrate", "heart beat", "heartbeat", "pulse", "bpm", "hr") ||
            t.has("धड़कन", "धडकन", "नब्ज़", "नब्ज", "नाड़ी", "हृदय गति", "दिल") -> InsightMetric.HEART_RATE
        t.has("oxygen", "spo2", "sp02", "o2", "saturation") || t.has("ऑक्सीजन", "आक्सीजन", "ओक्सीजन") ->
            InsightMetric.SPO2
        t.has("temperature", "temp", "fever", "warm", "warmest", "hot", "hottest", "cold", "coldest") ||
            t.has("तापमान", "बुखार", "गर्म", "ठंड") -> InsightMetric.SKIN_TEMPERATURE
        t.has("sleep", "slept", "asleep", "sleeping") || t.has("नींद", "सोया", "सोई", "सोने") -> InsightMetric.SLEEP
        t.has("stress", "stressed", "anxious", "anxiety", "tension", "tense") || t.has("तनाव", "टेंशन", "चिंता") ->
            InsightMetric.STRESS
        t.has("alert", "alerts", "warning", "warnings", "sos", "event", "events") || t.hasPrefix("anomal", "emergenc") ||
            t.has("चेतावनी", "चेतावनियाँ", "चेतावनियों", "अलर्ट") -> InsightMetric.ALERTS
        t.has("movement", "motion", "moved", "moving", "activity", "active", "fall", "falls", "fell", "impact", "jolt", "shake") ||
            t.has("हरकत", "गति", "गतिविधि", "झटका") || t.hasPrefix("गिर") -> InsightMetric.MOVEMENT
        else -> null
    }

    // ── What to work out ──────────────────────────────────────────────────────

    private fun op(t: Text, rangeCount: Int): InsightOp? = when {
        rangeCount >= 2 || t.has("vs", "versus", "compare", "compared", "comparison", "than") ||
            t.has("तुलना", "बनाम", "मुकाबले") -> InsightOp.COMPARE
        t.has("how many", "how often", "number of", "count", "times") || t.has("कितनी बार", "कितने बार", "कितनी चेतावनियाँ", "कितनी चेतावनी") ->
            InsightOp.COUNT
        t.has("right now", "now", "current", "currently", "latest", "at the moment", "last time", "when did") ||
            t.has("अभी", "इस समय", "वर्तमान", "ताज़ा", "आखिरी बार", "पिछली बार") -> InsightOp.LATEST
        t.has(
            "highest", "max", "maximum", "peak", "most", "top", "hottest", "warmest", "fastest", "worst",
            "biggest", "hardest", "strongest", "longest", "high"
        ) || t.has("सबसे ज़्यादा", "सबसे ज्यादा", "सबसे अधिक", "अधिकतम", "सबसे तेज़", "सबसे तेज", "सबसे लंबी", "सबसे गर्म") ->
            InsightOp.HIGHEST
        t.has("lowest", "min", "minimum", "least", "coldest", "slowest", "shortest", "low") ||
            t.has("सबसे कम", "न्यूनतम", "सबसे धीमी", "सबसे छोटी") -> InsightOp.LOWEST
        t.has("average", "avg", "mean", "typical", "typically", "usual", "usually", "normally", "overall") ||
            t.has("औसत", "आमतौर") -> InsightOp.AVERAGE
        t.has(
            "trend", "over time", "going up", "going down", "rising", "falling", "increasing", "decreasing",
            "changed", "changing", "graph", "chart", "pattern", "show"
        ) || t.has("रुझान", "ट्रेंड", "बदला", "बदल", "दिखाओ", "दिखाइए", "दिखाएं", "ग्राफ") -> InsightOp.TREND
        else -> null
    }

    private fun defaultOp(metric: InsightMetric): InsightOp = when (metric) {
        InsightMetric.ALERTS -> InsightOp.COUNT
        InsightMetric.SLEEP, InsightMetric.STRESS -> InsightOp.AVERAGE
        else -> InsightOp.TREND
    }

    // ── When ──────────────────────────────────────────────────────────────────

    /** Every period named, in the order they appear in the question. */
    private fun ranges(t: Text): List<TimeRange> {
        val found = mutableListOf<Pair<Int, TimeRange>>()
        fun add(at: Int, range: TimeRange) { if (at >= 0) found += at to range }

        // "Last night" before "last"/"night" on their own, and कल रात before कल.
        add(t.indexOf("last night"), TimeRange.LastNight)
        listOf("कल रात", "पिछली रात", "रात को", "रात में").forEach { add(t.indexOf(it), TimeRange.LastNight) }
        add(t.indexOf("yesterday"), TimeRange.Yesterday)
        if (t.indexOf("कल रात") < 0) add(t.indexOf("कल"), TimeRange.Yesterday)
        listOf("today", "this morning", "this evening", "आज").forEach { add(t.indexOf(it), TimeRange.Today) }
        listOf("this week", "इस हफ़्ते", "इस हफ्ते", "इस सप्ताह").forEach { add(t.indexOf(it), TimeRange.ThisWeek) }
        listOf("last week", "previous week", "पिछले हफ़्ते", "पिछले हफ्ते", "पिछले सप्ताह", "पिछला हफ़्ता")
            .forEach { add(t.indexOf(it), TimeRange.PreviousWeek) }
        listOf("last hour", "past hour", "पिछले घंटे").forEach { add(t.indexOf(it), TimeRange.LastHours(1)) }
        listOf("this month", "last month", "past month", "इस महीने", "पिछले महीने")
            .forEach { add(t.indexOf(it), TimeRange.LastDays(30)) }

        // Numbered periods: "last 3 days", "past 12 hours", "2 weeks", "3 दिन", "12 घंटे".
        NUMBERED.findAll(t.padded).forEach { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@forEach
            val unit = m.groupValues[2]
            val range = when {
                unit.startsWith("hour") || unit.startsWith(nfd("घंट")) ->
                    TimeRange.LastHours(n.coerceIn(1, TimeRange.MAX_DAYS * 24))
                unit.startsWith("week") || unit.startsWith(nfd("हफ़्त")) || unit.startsWith(nfd("हफ्त")) || unit.startsWith(nfd("सप्ताह")) ->
                    TimeRange.LastDays((n * 7).coerceIn(1, TimeRange.MAX_DAYS))
                else -> TimeRange.LastDays(n.coerceIn(1, TimeRange.MAX_DAYS))
            }
            add(m.range.first, range)
        }

        // The earliest match at each position wins; the same period named twice counts once.
        return found.sortedBy { it.first }.map { it.second }.distinct()
    }

    private val NUMBERED = Regex(
        nfd("""(\d+) (hours|hour|days|day|weeks|week|घंटों|घंटे|घंटा|दिनों|दिन|हफ़्तों|हफ़्ते|हफ्तों|हफ्ते|सप्ताह)(?= )""")
    )

    /**
     * Devanagari letters with a nukta (ज़, फ़) can be typed as one character or as two; speech
     * recognisers differ too. Both the question and every phrase are decomposed, so they match.
     */
    private fun nfd(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)

    // ── Text handling ─────────────────────────────────────────────────────────

    /** A question as lower-case words, with Devanagari digits read as ordinary ones. */
    private class Text(question: String) {
        val words: List<String> = nfd(question).lowercase()
            .map { c -> if (c in '०'..'९') '0' + (c - '०') else c }
            .joinToString("")
            // Letters, combining marks (Devanagari vowel signs) and digits make up words.
            .split(Regex("""[^\p{L}\p{M}\p{N}]+"""))
            .filter { it.isNotEmpty() }

        /** Words joined by single spaces, with a space at each end, for phrase matching. */
        val padded: String = words.joinToString(" ", prefix = " ", postfix = " ")

        fun has(vararg phrases: String): Boolean = phrases.any { padded.contains(" ${nfd(it)} ") }

        fun hasPrefix(vararg stems: String): Boolean = words.any { w -> stems.any { w.startsWith(nfd(it)) } }

        /** Where [phrase] starts as whole words, or -1. */
        fun indexOf(phrase: String): Int = padded.indexOf(" ${nfd(phrase)} ")
    }
}
