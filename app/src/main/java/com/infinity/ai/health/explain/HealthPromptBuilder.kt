package com.infinity.ai.health.explain

import com.infinity.ai.health.domain.AnomalyEvidence

/**
 * Builds the constrained prompt that turns a confirmed anomaly into plain language.
 *
 * THE MODEL'S JOB IS REPHRASING, NOT REASONING.
 *
 * By the time this is called, the deterministic engine has already decided the event
 * type, the severity, and every number, and [ExplanationTemplates] has already
 * produced a complete usable explanation. The model is handed all of that and asked
 * only to say the same thing more naturally.
 *
 * That framing is what makes a 1.5B 4-bit model appropriate here. It is not being
 * asked to diagnose, weigh evidence, or choose an action — all of which it would do
 * badly and unsafely. It is being asked to paraphrase a short factual passage, which
 * small models do reliably.
 *
 * Guardrails live in the SYSTEM prompt rather than the user turn because system
 * framing survives better through a long generation, and because it keeps the
 * instruction separated from the data it applies to.
 */
object HealthPromptBuilder {

    /**
     * Hard constraints for the health-explanation task.
     *
     * Note the last rule. Every earlier rule tells the model what not to invent; that
     * one gives it a safe way to fail. Without an explicit fallback, a constrained
     * model that decides it cannot comply will often produce something worse than a
     * refusal.
     */
    const val SYSTEM_PROMPT: String =
        "You are the explanation module of G-one, an offline personal health monitor. " +
            "A separate rule-based system has already analysed sensor readings and " +
            "detected an event. Your ONLY job is to restate its finding in simple, " +
            "calm language a family member with no medical training can understand.\n" +
            "\n" +
            "STRICT RULES:\n" +
            "1. Do NOT diagnose. Never name a disease or condition.\n" +
            "2. Do NOT invent, change, round or add any number. Use only the values given.\n" +
            "3. Do NOT change the severity. It has already been decided.\n" +
            "4. Do NOT give medical advice, medicines, dosages or treatments.\n" +
            "5. Do NOT claim certainty. Say what was measured, not what it proves.\n" +
            "6. Do NOT add a recommendation or next step. That text is added separately.\n" +
            "7. Keep it under 60 words, in 2 to 3 short sentences.\n" +
            "8. Write plainly. No jargon, no bullet points, no headings.\n" +
            "9. If the input is unclear, restate the provided summary as-is rather " +
            "than guessing."

    /**
     * Assemble the user turn: the structured evidence plus the deterministic
     * explanation as a worked reference.
     *
     * Including the template output is the important trick. It gives the model a
     * correct, safe answer to imitate, which sharply reduces the chance of a small
     * model drifting into diagnosis — and it means the worst realistic outcome is
     * output close to the template we would have shown anyway.
     */
    fun buildUserPrompt(evidence: AnomalyEvidence, template: Explanation): String =
        buildString {
            append("Sensor findings (JSON):\n")
            append(evidence.toJson())
            append("\n\n")
            append("Plain-language summary produced by the rule system:\n")
            append(template.detail)
            append("\n\n")
            append("Rewrite that summary so it sounds natural and reassuring, ")
            append("keeping every number exactly as given. Do not add advice.")
        }

    /**
     * Sanity-check model output before it is allowed to replace the template.
     *
     * DEFENCE IN DEPTH: prompt constraints are guidance, not a guarantee. A small
     * model can still ignore them, so its output is validated before it is shown to
     * anyone. Anything suspicious is discarded and the deterministic template stands.
     * Silently keeping the safe text is always preferable to displaying an unsafe
     * rewrite.
     *
     * @return the cleaned text, or null when it should be rejected.
     */
    fun validate(raw: String, evidence: AnomalyEvidence): String? {
        val text = raw.trim()

        if (text.length < 20) return null                 // truncated or empty
        if (text.length > 900) return null                // ran away

        val lower = text.lowercase()

        // Refusal or meta-commentary rather than an explanation.
        val metaMarkers = listOf(
            "as an ai", "language model", "i cannot", "i can't help",
            "i'm sorry", "i am sorry", "<|im_", "system prompt"
        )
        if (metaMarkers.any { lower.contains(it) }) return null

        // Advice or prescription, which rule 4 and 6 forbid — the tier text owns this.
        val adviceMarkers = listOf(
            "you should take", "take a tablet", "take medicine", "mg of",
            "dosage", "prescribe", "i recommend you take", "diagnosis is",
            "you have a", "this is caused by"
        )
        if (adviceMarkers.any { lower.contains(it) }) return null

        // Fabricated vitals check: any percentage or bpm figure in the output must
        // correspond to a value actually measured. This is the concrete defence
        // against the model inventing a reading, which is the most dangerous
        // failure mode available to it.
        val allowed = buildSet {
            evidence.spo2?.let { add(it) }
            evidence.heartRate?.let { add(it) }
            evidence.aqi?.let { add(it) }
            evidence.durationMinutes?.let { add(it) }
            evidence.ambientTempC?.let { add(it.toInt()) }
            evidence.ambientHumidityPct?.let { add(it.toInt()) }
            evidence.bodyTempC?.let { add(it.toInt()) }
            evidence.riskScores.heat.let { add(it) }
            evidence.riskScores.respiratory.let { add(it) }
            evidence.riskScores.cardiovascular.let { add(it) }
        }

        val quoted = Regex("""\d+""").findAll(text)
            .mapNotNull { it.value.toIntOrNull() }
            .filter { it > 5 }   // ignore small ordinals like "2 to 3 sentences"
            .toList()

        // A body temperature such as 37.4 appears as 37 and 4; allow the decimal part.
        val decimalParts = buildSet {
            evidence.bodyTempC?.let { add(((it * 10).toInt() % 10)) }
        }

        if (quoted.any { it !in allowed && it !in decimalParts }) return null

        return text
    }
}
