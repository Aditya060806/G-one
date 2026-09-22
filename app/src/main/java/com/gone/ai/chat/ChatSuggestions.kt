package com.gone.ai.chat

/**
 * The suggestions an empty chat opens with.
 *
 * They used to include "What does my resting heart rate mean?" for everyone, although the
 * chat could not see any heart rate. Personal suggestions now appear only when the data they
 * refer to exists and the person lets the chat use it; the rest are general questions.
 */
object ChatSuggestions {

    enum class Kind { SESSION, ALERT, RECORD, HEART, LAB, GENERAL }

    data class Suggestion(val kind: Kind, val title: String, val subtitle: String)

    private const val MAX = 4

    fun choose(hasReports: Boolean, hasRecentAlerts: Boolean, hasRecords: Boolean, usingHealthData: Boolean): List<Suggestion> {
        val personal = if (!usingHealthData) emptyList() else buildList {
            if (hasReports) add(Suggestion(Kind.SESSION, "How did my last session go?", "From your latest session report"))
            if (hasRecentAlerts) add(Suggestion(Kind.ALERT, "Explain my latest alert", "What was measured and what it means"))
            if (hasRecords) add(Suggestion(Kind.RECORD, "What do my lab results say?", "From the records you chose to share"))
        }
        val general = listOf(
            Suggestion(Kind.HEART, "What is a normal resting heart rate?", "Typical ranges and what changes them"),
            Suggestion(Kind.LAB, "Help me understand a lab report", "Paste the values, or scan a photo with OCR"),
            Suggestion(Kind.GENERAL, "Ask any health question", "Plain-language explanations")
        )
        return (personal + general).take(MAX)
    }
}
