package com.gone.ai.health.session

/**
 * The constrained prompt and output check for rewording a session report with the local model.
 *
 * Same contract as the alert explanations: the deterministic observations are complete on
 * their own, the model may only restate them, and its output is thrown away unless it passes
 * [validate]. REFERENCE fed the model its own estimated vitals and printed whatever came back,
 * including diet and exercise advice.
 */
object ReportSummaryPrompt {

    const val SYSTEM_PROMPT: String =
        "You summarise a personal health monitoring session for a family member with no " +
            "medical training. A rule-based system has already written the observations; your " +
            "ONLY job is to restate them as one short, calm paragraph.\n" +
            "\n" +
            "STRICT RULES:\n" +
            "1. Do NOT diagnose or name any disease or condition.\n" +
            "2. Use ONLY numbers that appear in the observations. Do not round, add or change any.\n" +
            "3. Do NOT give advice, recommendations, next steps, food, drink, exercise or medicine.\n" +
            "4. If the observations say readings were simulated, say so.\n" +
            "5. Keep it under 80 words. No lists, no headings.\n" +
            "6. If unsure, repeat the observations plainly rather than guessing."

    fun userPrompt(observations: List<String>): String = buildString {
        append("Observations from the rule system:\n")
        observations.forEach { append("- ").append(it).append('\n') }
        append("\nWrite them as one short paragraph, keeping every number exactly as given. Do not add advice.")
    }

    /** @return the cleaned text, or null when it must not be shown. */
    fun validate(raw: String, observations: List<String>): String? {
        val text = raw.trim()
        if (text.length < 30 || text.length > 900) return null
        val lower = text.lowercase()

        val meta = listOf("as an ai", "language model", "i cannot", "i can't", "i'm sorry", "i am sorry", "<|im_", "system prompt")
        if (meta.any { lower.contains(it) }) return null

        val advice = listOf(
            "you should", "should see", "recommend", "consult", "see a doctor", "drink", "hydrat",
            "exercise", "diet", "eat ", "medicine", "medication", "dose", "rest more", "make sure to",
            "diagnos", "you have a"
        )
        if (advice.any { lower.contains(it) }) return null

        // A simulated session must never be presented as a measured one.
        val simulated = observations.any { it.contains("simulated", ignoreCase = true) }
        if (simulated && !lower.contains("simulat")) return null

        val allowed = observations.flatMap { line ->
            NUMBER.findAll(line).map { it.value }.toList()
        }.toSet()
        // Single digits are ordinary words ("2 times", "1 hour"), as in the alert validator.
        val quoted = NUMBER.findAll(text).map { it.value }.filter { it.length > 1 }
        if (quoted.any { it !in allowed }) return null

        return text
    }

    /** Whole numbers and decimals, as they appear in the observations ("33.9", "4095"). */
    private val NUMBER = Regex("""\d+(?:\.\d+)?""")
}
