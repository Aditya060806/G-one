package com.infinity.ai.health.detect

import com.infinity.ai.health.domain.AnomalyCandidate
import com.infinity.ai.health.domain.AnomalyThresholds
import com.infinity.ai.health.domain.AnomalyType
import com.infinity.ai.health.domain.PatientBaseline
import com.infinity.ai.health.domain.RiskScores
import com.infinity.ai.health.domain.Severity
import com.infinity.ai.health.domain.VitalsSample

/**
 * The deterministic anomaly detection engine.
 *
 * THIS CLASS MAKES EVERY MEDICAL DECISION IN G-ONE. THE LLM MAKES NONE.
 *
 * The model is never consulted here and is not a dependency of this package. It
 * decides nothing about whether something is wrong, how severe it is, or what the
 * numbers are — it only rephrases the output of this engine into plain language
 * afterwards. That boundary is the core safety property of the product: an alert is
 * always traceable to a named rule and a threshold a clinician can review, never to
 * a token sampled from a probability distribution.
 *
 * Pure Kotlin, no Android, no I/O, no clock of its own — [now] is passed in. Fully
 * reproducible and directly unit-testable.
 */
class AnomalyDetector(
    val thresholds: AnomalyThresholds = AnomalyThresholds.DEFAULT,
    private val rules: List<AnomalyRule> = AnomalyRules.ALL
) {

    /**
     * Evaluate a window.
     *
     * @param samples any order — normalised chronologically internally.
     * @param baseline the patient's own rolling normal.
     * @param now evaluation timestamp, injected for testability.
     * @param lastEventAtByType most recent event time per type, for the cooldown.
     *   Empty map disables debouncing (useful in tests).
     */
    fun evaluate(
        samples: List<VitalsSample>,
        baseline: PatientBaseline = PatientBaseline.EMPTY,
        now: Long,
        lastEventAtByType: Map<AnomalyType, Long> = emptyMap()
    ): DetectionResult {
        val window = VitalsWindow.of(samples)

        // Risk scores are computed on EVERY evaluation, whether or not any rule
        // fires. This is what gives the dashboard a continuously rising trend line
        // instead of a binary fine/critical flip — the difference between warning
        // early and reporting late.
        val risk = RiskScorer.score(window, baseline, thresholds)

        if (window.isEmpty) {
            return DetectionResult(emptyList(), risk, emptyList(), window)
        }

        val ctx = DetectionContext(
            window     = window,
            baseline   = baseline,
            thresholds = thresholds,
            riskScores = risk,
            now        = now
        )

        val fired = rules.mapNotNull { rule ->
            // A rule throwing must never take down monitoring. Detection continuing
            // with one rule degraded is strictly better than the service dying and
            // the patient going unmonitored.
            runCatching { rule.evaluate(ctx) }.getOrNull()
        }

        val (kept, suppressed) = applySuppression(fired, now, lastEventAtByType)

        return DetectionResult(
            candidates = kept.sortedByDescending { it.severity.rank },
            riskScores = risk,
            suppressed = suppressed,
            window     = window
        )
    }

    /**
     * Two-stage noise control.
     *
     * 1. COOLDOWN — a vital hovering at a threshold would otherwise emit an event per
     *    sample. At 1 Hz that is 60 alerts a minute, which trains the user to ignore
     *    notifications entirely. That is the worst possible failure for a
     *    health-warning app, so it is prevented structurally rather than in the UI.
     *
     * 2. LOW-SEVERITY SHADOWING — when something CRITICAL is firing, informational
     *    LOW findings alongside it are a distraction. They are recorded as suppressed
     *    for the audit trail, not surfaced.
     */
    private fun applySuppression(
        fired: List<AnomalyCandidate>,
        now: Long,
        lastEventAtByType: Map<AnomalyType, Long>
    ): Pair<List<AnomalyCandidate>, List<SuppressedCandidate>> {
        val cooldownMillis = thresholds.perTypeCooldownMinutes * 60_000L
        val kept = mutableListOf<AnomalyCandidate>()
        val suppressed = mutableListOf<SuppressedCandidate>()

        for (c in fired) {
            val last = lastEventAtByType[c.type]
            if (last != null && cooldownMillis > 0 && now - last < cooldownMillis) {
                suppressed += SuppressedCandidate(
                    candidate = c,
                    reason = SuppressionReason.COOLDOWN,
                    detail = "last ${c.type.wireName} ${(now - last) / 1000}s ago, " +
                        "cooldown ${thresholds.perTypeCooldownMinutes}m"
                )
            } else {
                kept += c
            }
        }

        val hasCritical = kept.any { it.severity == Severity.CRITICAL }
        if (!hasCritical) return kept to suppressed

        val shadowed = kept.filter { it.severity == Severity.LOW }
        if (shadowed.isEmpty()) return kept to suppressed

        shadowed.forEach {
            suppressed += SuppressedCandidate(
                candidate = it,
                reason = SuppressionReason.SHADOWED_BY_CRITICAL,
                detail = "LOW finding withheld while a CRITICAL event is active"
            )
        }
        return kept.filter { it.severity != Severity.LOW } to suppressed
    }
}

/**
 * Outcome of one evaluation.
 *
 * [riskScores] is always populated; [candidates] is frequently empty, which is the
 * normal and desired case.
 */
data class DetectionResult(
    val candidates: List<AnomalyCandidate>,
    val riskScores: RiskScores,
    val suppressed: List<SuppressedCandidate>,
    val window: VitalsWindow
) {
    val hasAnomaly: Boolean get() = candidates.isNotEmpty()

    /** Worst severity in this evaluation, or null when nothing fired. */
    val peakSeverity: Severity?
        get() = candidates.maxByOrNull { it.severity.rank }?.severity

    /** The event to surface first. */
    val primary: AnomalyCandidate? get() = candidates.firstOrNull()
}

/** A candidate that fired but was deliberately withheld. Kept for auditability. */
data class SuppressedCandidate(
    val candidate: AnomalyCandidate,
    val reason: SuppressionReason,
    val detail: String
)

enum class SuppressionReason { COOLDOWN, SHADOWED_BY_CRITICAL }
