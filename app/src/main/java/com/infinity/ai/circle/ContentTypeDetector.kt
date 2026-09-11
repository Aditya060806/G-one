package com.infinity.ai.circle

/**
 * ContentTypeDetector
 *
 * Analyzes raw OCR text to classify content type.
 * Used by CircleLearnBottomSheet to prioritize and reorder action suggestions.
 * Purely heuristic — no AI call required.
 *
 * The primary type used to be CODE, which made sense when this was a developer study
 * tool and makes none in a health companion. It is now MEDICAL: the realistic thing a
 * G-one user circles is a lab report, a prescription label or a discharge summary.
 */
object ContentTypeDetector {

    enum class ContentType {
        MEDICAL, FORMULA, TABLE, PARAGRAPH
    }

    data class DetectionResult(
        val type           : ContentType,
        val primaryActions : List<CircleAction>,
        val allActions     : List<CircleAction>
    )

    /**
     * Signals that the selection is a clinical document.
     *
     * Chosen to be distinctive enough to survive OCR noise without false-firing on
     * ordinary prose. Note the deliberate absence of bare "mg" and "ml" — they appear
     * inside common words ("mgmt", "html") and OCR fragments, and a false MEDICAL
     * classification would push the wrong three actions to the front. Compound units
     * like "mg/dl" carry the same information without the collisions.
     */
    private val MEDICAL_SIGNALS = listOf(
        "mg/dl", "mg/dL", "mmol/l", "mcg", "units/l", "iu/l", "mmhg", "bpm", "spo2",
        "reference range", "normal range", "reference interval",
        "haemoglobin", "hemoglobin", "platelet", "cholesterol", "triglyceride",
        "glucose", "creatinine", "bilirubin", "urea", "tsh", "wbc", "rbc",
        "specimen", "prescription", "diagnosis", "dosage", "tablet", "capsule",
        "twice daily", "once daily", "lab report", "patient name", "blood pressure"
    )
    private val FORMULA_SIGNALS = listOf("=", "∫", "∑", "√", "∂", "∞", "π", "α", "β", "θ",
        "^2", "^n", "dx", "dy", "sin(", "cos(", "log(", "lim")
    private val TABLE_SIGNALS   = listOf("\t", "  |  ", "---", "===")

    fun detect(text: String): DetectionResult {
        // Match case-insensitively. OCR routinely returns lab reports fully capitalised
        // ("HAEMOGLOBIN 11.2 g/dL"), and the old case-sensitive contains() would have
        // missed every one of them.
        val t = text.lowercase()

        val type = when {
            isMedical(t) -> ContentType.MEDICAL
            isFormula(t) -> ContentType.FORMULA
            isTable(t)   -> ContentType.TABLE
            else         -> ContentType.PARAGRAPH
        }

        val primary = when (type) {
            ContentType.MEDICAL   -> listOf(CircleAction.EXPLAIN_REPORT, CircleAction.CHECK_RANGES,
                                            CircleAction.MEDICAL_TERMS)
            ContentType.FORMULA   -> listOf(CircleAction.EXPLAIN, CircleAction.SOLVE_EXAMPLE,
                                            CircleAction.PRACTICE_QUESTIONS)
            ContentType.TABLE     -> listOf(CircleAction.CHECK_RANGES, CircleAction.SUMMARIZE,
                                            CircleAction.KEY_POINTS)
            ContentType.PARAGRAPH -> listOf(CircleAction.EXPLAIN, CircleAction.SUMMARIZE,
                                            CircleAction.KEY_POINTS)
        }

        return DetectionResult(type, primary, CircleAction.entries.toList())
    }

    private fun isMedical(lower: String) = MEDICAL_SIGNALS.count { lower.contains(it.lowercase()) } >= 2
    private fun isFormula(lower: String) = FORMULA_SIGNALS.count { lower.contains(it) } >= 2
    private fun isTable(lower: String)   = TABLE_SIGNALS.any { lower.contains(it) }
}

/**
 * Actions offered for a circled selection.
 *
 * Declaration order is UI order — `allActions` is `entries.toList()` — so the health
 * actions lead. The three former coding actions (Explain Code, Find Bugs, Interview
 * Questions) were removed: they were the clearest sign this app had not finished
 * becoming a health companion.
 *
 * The health prompts carry their own "do not diagnose" clause even though
 * [com.infinity.ai.ai.prompts.PromptFormatter.DEFAULT_SYSTEM_PROMPT] already forbids
 * it. Redundant by design — a 1.5B model handed a page of abnormal lab values is
 * strongly pulled toward naming a disease, and restating the boundary next to the data
 * it applies to holds better than relying on the system turn alone.
 */
enum class CircleAction(val label: String, val emoji: String, val prompt: String) {
    EXPLAIN        ("Explain",        "💡", "Explain the following clearly and concisely:"),
    EXPLAIN_REPORT ("Explain Report", "🩺", "Explain what this medical report says in plain language " +
        "a person with no medical training can follow. Describe what each measurement is for. " +
        "Do NOT name a disease, do NOT diagnose, and do NOT suggest any treatment or medicine:"),
    CHECK_RANGES   ("Check Ranges",   "📊", "For each measurement below, state its value and whether it " +
        "falls inside or outside the reference range printed alongside it. If no range is printed, say " +
        "\"no range given\" rather than supplying one from memory. Do NOT diagnose:"),
    MEDICAL_TERMS  ("Decode Terms",   "🔤", "List each medical term or abbreviation that appears below and " +
        "define it in one plain sentence. Definitions only — do NOT interpret the results:"),
    SUMMARIZE      ("Summarize",      "📝", "Summarize the following in 5 concise bullet points:"),
    KEY_POINTS     ("Key Points",     "🔑", "List the 5 most important key points from:"),
    TRANSLATE      ("Translate",      "🌐", "Translate the following text to English (or explain if already English):"),
    NOTES          ("Generate Notes", "📚", "Convert the following into organized notes with headings:"),
    FLASHCARDS     ("Flashcards",     "🃏", "Create 5 question-answer flashcards from the following text:"),
    QUIZ           ("Quiz",           "❓", "Generate 5 multiple choice questions (with A B C D options and answer) from:"),
    VIVA           ("Viva Questions", "🎓", "Generate 5 important viva/oral exam questions with answers from:"),
    SOLVE_EXAMPLE  ("Solve Example",  "🔢", "Solve a numeric example using the following formula or equation:"),
    PRACTICE_QUESTIONS("Practice Questions", "✏️", "Generate 5 practice problems based on the following formula or concept:"),
    SAVE_TO_VAULT  ("Save to Vault",  "💾", "")   // handled separately — no AI prompt
}
