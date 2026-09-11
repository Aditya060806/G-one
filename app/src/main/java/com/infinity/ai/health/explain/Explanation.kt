package com.infinity.ai.health.explain

import com.infinity.ai.health.domain.Severity

/**
 * The only next-step advice G-one is permitted to give.
 *
 * WHY THIS IS A CLOSED SET
 *
 * Free-form medical advice from a 1.5B language model is the single biggest risk in
 * a product like this. So the app does not generate advice at all — it selects from
 * three pre-written tiers, chosen deterministically from rule severity. The model,
 * when it runs, may rephrase the *description* of what was measured; it may never
 * invent or alter the recommendation.
 *
 * This is what makes the LLM safe to ship here: it is constrained to explanation,
 * and the actionable part of every alert is fixed text a clinician can review once
 * and trust forever.
 */
enum class ResponseTier(val label: String, val guidance: String) {

    MONITOR(
        label = "Keep an eye on this",
        guidance = "This is not urgent. Keep resting, drink water if you feel thirsty, " +
            "and check again in a little while. If you start feeling worse, tell " +
            "someone nearby."
    ),

    CONTACT_DOCTOR(
        label = "Speak to a doctor",
        guidance = "Please discuss this with a doctor or health worker when you can " +
            "today. Show them this reading. If it gets worse before then, treat it " +
            "as urgent."
    ),

    SEEK_IMMEDIATE_CARE(
        label = "Get help now",
        guidance = "Do not wait. Tell someone nearby immediately and get to a doctor " +
            "or hospital as soon as possible. If the person is unconscious, " +
            "struggling to breathe, or cannot be woken, call emergency services now."
    );

    companion object {
        /**
         * Severity maps to tier one-to-one and without exception.
         *
         * Deliberately not per-type: a CRITICAL finding must always escalate the same
         * way regardless of which rule produced it, so no future rule can quietly
         * downgrade urgency.
         */
        fun forSeverity(severity: Severity): ResponseTier = when (severity) {
            Severity.LOW      -> MONITOR
            Severity.MODERATE -> CONTACT_DOCTOR
            Severity.CRITICAL -> SEEK_IMMEDIATE_CARE
        }
    }
}

/**
 * A complete, human-readable account of one detected event.
 *
 * Produced by [ExplanationTemplates] with zero AI involvement, so it is available
 * the instant the event is persisted. The LLM's later output replaces [detail] only —
 * [headline] and [tier] are never model-generated.
 */
data class Explanation(
    /** Short enough for a notification title. */
    val headline: String,
    /** What was measured and why it can matter. Plain language, no diagnosis. */
    val detail: String,
    val tier: ResponseTier,
    /** Rule that produced this, so any alert is traceable. */
    val ruleId: String
) {
    /** Detail plus the fixed tier guidance — what the alert screen shows. */
    val full: String get() = "$detail\n\n${tier.guidance}"

    /** One-line form for notifications and list rows. */
    val short: String get() = "$headline — ${tier.label}"
}
