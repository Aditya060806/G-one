package com.infinity.ai.health.detect

import com.infinity.ai.health.domain.AnomalyCandidate
import com.infinity.ai.health.domain.AnomalyEvidence
import com.infinity.ai.health.domain.AnomalyThresholds
import com.infinity.ai.health.domain.AnomalyType
import com.infinity.ai.health.domain.PatientBaseline
import com.infinity.ai.health.domain.RiskScores
import com.infinity.ai.health.domain.Severity
import com.infinity.ai.health.domain.Trend
import com.infinity.ai.health.domain.VitalsSample
import kotlin.math.abs

/**
 * Everything a rule is allowed to look at.
 *
 * Rules receive a context and return a candidate or null. They may not perform I/O,
 * consult a clock, or hold state — which is what makes each one a pure function that
 * a unit test can pin down completely.
 */
data class DetectionContext(
    val window: VitalsWindow,
    val baseline: PatientBaseline,
    val thresholds: AnomalyThresholds,
    val riskScores: RiskScores,
    val now: Long
) {
    val latest: VitalsSample? get() = window.latest
    val env: EnvironmentContext get() = window.environment

    /**
     * Assemble evidence, filling the observational fields from the newest sample.
     *
     * Centralised so that no rule can accidentally report a value it did not read —
     * every number in the payload comes from the actual sample, never from the rule's
     * own reasoning.
     */
    fun evidence(
        type: AnomalyType,
        severity: Severity,
        ruleId: String,
        durationMinutes: Int? = null,
        trend: Trend? = null,
        baselineDeltaPct: Float? = null
    ): AnomalyEvidence {
        val l = latest
        return AnomalyEvidence(
            type               = type,
            severity           = severity,
            triggeredAt        = l?.timestamp ?: now,
            ruleId             = ruleId,
            riskScores         = riskScores,
            heartRate          = l?.heartRate,
            spo2               = l?.spo2,
            bodyTempC          = l?.bodyTempC,
            ambientTempC       = l?.ambientTempC,
            ambientHumidityPct = l?.ambientHumidityPct,
            aqi                = l?.aqi,
            durationMinutes    = durationMinutes,
            trend              = trend,
            baselineDeltaPct   = baselineDeltaPct,
            motionDetected     = l?.motionMagnitudeG?.let { it > VitalsSample.REST_MOTION_G },
            sampleCount        = window.size
        )
    }

    fun candidate(
        type: AnomalyType,
        severity: Severity,
        ruleId: String,
        durationMinutes: Int? = null,
        trend: Trend? = null,
        baselineDeltaPct: Float? = null
    ) = AnomalyCandidate(
        type     = type,
        severity = severity,
        evidence = evidence(type, severity, ruleId, durationMinutes, trend, baselineDeltaPct)
    )
}

/** A single deterministic detection rule. */
interface AnomalyRule {
    /** Stable identifier, recorded in the evidence so any alert is traceable. */
    val id: String
    fun evaluate(ctx: DetectionContext): AnomalyCandidate?
}

// ── SpO2 ──────────────────────────────────────────────────────────────────────

/** Instantaneous critical desaturation. Fires immediately, duration irrelevant. */
object CriticalSpo2Rule : AnomalyRule {
    override val id = "spo2.critical"
    override fun evaluate(ctx: DetectionContext): AnomalyCandidate? {
        val spo2 = ctx.latest?.spo2 ?: return null
        if (spo2 >= ctx.thresholds.spo2CriticalBelow) return null
        return ctx.candidate(AnomalyType.LOW_SPO2, Severity.CRITICAL, id)
    }
}

/**
 * Mild desaturation held over time — the case PS26181 states explicitly
 * ("SpO2 < 92% for 10+ minutes").
 *
 * Duration is the whole point: a single 91% reading is usually a sensor artifact
 * from a shifted finger, while 91% held for ten minutes is physiology.
 */
object SustainedSpo2Rule : AnomalyRule {
    override val id = "spo2.sustained"
    override fun evaluate(ctx: DetectionContext): AnomalyCandidate? {
        val t = ctx.thresholds
        val spo2 = ctx.latest?.spo2 ?: return null
        // Defer to the critical rule rather than double-reporting the same reading.
        if (spo2 < t.spo2CriticalBelow) return null
        if (spo2 >= t.spo2SustainedBelow) return null

        val held = ctx.window.sustained({ it.spo2 }, { it < t.spo2SustainedBelow })
        if (!held.atLeastMinutes(t.spo2SustainedMinutes)) return null

        return ctx.candidate(
            AnomalyType.SUSTAINED_LOW_SPO2,
            Severity.MODERATE,
            id,
            durationMinutes = held.minutes,
            trend = ctx.window.spo2Trend(t.spo2TrendStableBand)
        )
    }
}

// ── Heart rate ────────────────────────────────────────────────────────────────

/**
 * Tachycardia.
 *
 * Motion-aware: at rest the normal threshold applies, but while the patient is
 * moving only the critical threshold counts. Without that, every brisk walk would
 * generate an alert and the user would learn to dismiss them.
 */
object HighHeartRateRule : AnomalyRule {
    override val id = "hr.high"
    override fun evaluate(ctx: DetectionContext): AnomalyCandidate? {
        val t = ctx.thresholds
        val hr = ctx.latest?.heartRate ?: return null
        val atRest = ctx.window.isAtRest

        val severity = when {
            hr >= t.hrCriticalHighBpm -> Severity.CRITICAL
            hr >= t.hrHighBpm && atRest -> Severity.MODERATE
            else -> return null
        }

        val held = ctx.window.sustained({ it.heartRate }, { it >= t.hrHighBpm })
        return ctx.candidate(
            AnomalyType.HIGH_HEART_RATE,
            severity,
            id,
            durationMinutes = held.minutes.takeIf { it > 0 },
            trend = ctx.window.hrTrend(t.hrTrendStableBand)
        )
    }
}

/** Bradycardia. Only meaningful at rest; a low reading mid-motion is an artifact. */
object LowHeartRateRule : AnomalyRule {
    override val id = "hr.low"
    override fun evaluate(ctx: DetectionContext): AnomalyCandidate? {
        val t = ctx.thresholds
        val hr = ctx.latest?.heartRate ?: return null
        if (hr > t.hrLowBpm) return null
        if (!ctx.window.isAtRest) return null

        val held = ctx.window.sustained({ it.heartRate }, { it <= t.hrLowBpm })
        // A one-off low sample is almost always optical noise; require persistence.
        if (held.sampleCount < 3) return null

        val severity = if (hr <= t.hrLowBpm - 8) Severity.CRITICAL else Severity.MODERATE
        return ctx.candidate(
            AnomalyType.LOW_HEART_RATE, severity, id,
            durationMinutes = held.minutes.takeIf { it > 0 }
        )
    }
}

// ── Temperature ───────────────────────────────────────────────────────────────

object FeverRule : AnomalyRule {
    override val id = "temp.fever"
    override fun evaluate(ctx: DetectionContext): AnomalyCandidate? {
        val t = ctx.thresholds
        val temp = ctx.latest?.bodyTempC ?: return null
        if (temp < t.tempFeverC) return null
        val severity = if (temp >= t.tempCriticalFeverC) Severity.CRITICAL else Severity.MODERATE
        val held = ctx.window.sustained({ it.bodyTempC }, { it >= t.tempFeverC })
        return ctx.candidate(
            AnomalyType.FEVER, severity, id,
            durationMinutes = held.minutes.takeIf { it > 0 }
        )
    }
}

// ── Environment-coupled ───────────────────────────────────────────────────────

/**
 * Heat stress: hot environment PLUS a physiological response to it.
 *
 * Environmental heat alone is a weather forecast, not a health event, so this rule
 * requires the body to actually be responding — rising core temperature or an
 * elevated resting heart rate. That coupling is what keeps it from firing on every
 * Indian summer afternoon regardless of the wearer's condition.
 */
object HeatStressRule : AnomalyRule {
    override val id = "env.heatStress"
    override fun evaluate(ctx: DetectionContext): AnomalyCandidate? {
        val t = ctx.thresholds
        val env = ctx.env
        if (!env.isHeatWave(t)) return null

        val latest = ctx.latest ?: return null
        val coreElevated = latest.bodyTempC?.let { it >= 37.5f } == true
        val hrElevated = ctx.window.isAtRest &&
            latest.heartRate?.let { it >= 100 } == true
        val tempRising = ctx.window.size >= t.minSamplesForTrend &&
            com.infinity.ai.health.domain.classifyTrend(ctx.window.bodyTemps(), 0.004f) == Trend.RISING

        if (!coreElevated && !hrElevated && !tempRising) return null

        val severity = when {
            env.isSevereHeat(t) && (coreElevated || hrElevated) -> Severity.CRITICAL
            coreElevated || hrElevated -> Severity.MODERATE
            else -> Severity.LOW
        }

        return ctx.candidate(
            AnomalyType.HEAT_STRESS, severity, id,
            trend = if (tempRising) Trend.RISING else null
        )
    }
}

/**
 * Dehydration risk, inferred rather than measured.
 *
 * There is no hydration sensor on this hardware, so this is explicitly a PROXY:
 * heat exposure plus a rising resting heart rate plus a rising core temperature is
 * the classic pattern of falling plasma volume. Severity stays capped at MODERATE
 * because the inference chain is longer than for a directly-measured vital — the
 * system should not claim confidence it has not earned.
 */
object DehydrationRiskRule : AnomalyRule {
    override val id = "env.dehydrationRisk"
    override fun evaluate(ctx: DetectionContext): AnomalyCandidate? {
        val t = ctx.thresholds
        if (!ctx.env.isHeatWave(t)) return null
        if (ctx.window.size < t.minSamplesForTrend) return null
        if (!ctx.window.isAtRest) return null

        val hrRising = ctx.window.hrTrend(t.hrTrendStableBand) == Trend.RISING
        val tempRising =
            com.infinity.ai.health.domain.classifyTrend(ctx.window.bodyTemps(), 0.004f) == Trend.RISING
        if (!hrRising || !tempRising) return null

        val severity = if (ctx.env.isSevereHeat(t)) Severity.MODERATE else Severity.LOW
        return ctx.candidate(AnomalyType.DEHYDRATION_RISK, severity, id, trend = Trend.RISING)
    }
}

/**
 * Respiratory distress from either mild desaturation under poor air quality, or a
 * falling SpO2 trend paired with a compensating rise in heart rate.
 */
object RespiratoryDistressRule : AnomalyRule {
    override val id = "resp.distress"
    override fun evaluate(ctx: DetectionContext): AnomalyCandidate? {
        val t = ctx.thresholds
        val latest = ctx.latest ?: return null
        val spo2 = latest.spo2 ?: return null

        val poorAir = ctx.env.isPoorAir(t)
        val enoughSamples = ctx.window.size >= t.minSamplesForTrend
        val satFalling = enoughSamples && ctx.window.spo2Trend(t.spo2TrendStableBand) == Trend.FALLING
        val hrRising = enoughSamples && ctx.window.hrTrend(t.hrTrendStableBand) == Trend.RISING

        val airPath = poorAir && spo2 < 95
        val couplingPath = satFalling && hrRising && spo2 < 96
        if (!airPath && !couplingPath) return null

        val severity = when {
            spo2 < t.spo2CriticalBelow -> Severity.CRITICAL
            ctx.env.isSevereAir(t) || spo2 < t.spo2SustainedBelow -> Severity.MODERATE
            else -> Severity.LOW
        }
        return ctx.candidate(
            AnomalyType.RESPIRATORY_DISTRESS, severity, id,
            trend = if (satFalling) Trend.FALLING else null
        )
    }
}

/**
 * Cardiovascular strain from CROSS-SIGNAL correlation: heart rate climbing while
 * oxygen saturation falls, at rest.
 *
 * Either signal alone has many benign explanations. Together, at rest, they are far
 * more specific to genuine distress — which is exactly why this is scored more
 * severely than either single-signal rule would be.
 */
object CardiovascularStrainRule : AnomalyRule {
    override val id = "cardio.strain"
    override fun evaluate(ctx: DetectionContext): AnomalyCandidate? {
        val t = ctx.thresholds
        if (ctx.window.size < t.minSamplesForTrend) return null
        if (!ctx.window.isAtRest) return null

        val hrRising = ctx.window.hrTrend(t.hrTrendStableBand) == Trend.RISING
        val satFalling = ctx.window.spo2Trend(t.spo2TrendStableBand) == Trend.FALLING
        if (!hrRising || !satFalling) return null

        val latest = ctx.latest ?: return null
        val hr = latest.heartRate ?: return null
        // Require the HR to have actually left the normal resting band, so a rising
        // trend inside 60–80 bpm does not trigger anything.
        if (hr < 100) return null

        val severity = if (hr >= t.hrHighBpm || (latest.spo2 ?: 100) < t.spo2SustainedBelow)
            Severity.MODERATE else Severity.LOW

        return ctx.candidate(AnomalyType.CARDIOVASCULAR_STRAIN, severity, id, trend = Trend.RISING)
    }
}

// ── Motion ────────────────────────────────────────────────────────────────────

/**
 * Fall detection: an impact spike followed by immobility.
 *
 * The two-part signature matters. An impact alone could be the wearable being set
 * down on a table. An impact followed by the wearer not moving is the pattern that
 * warrants an emergency response — and immobility is what escalates this to CRITICAL.
 *
 * Threshold-based for Phase 1 rather than a learned classifier: it needs no training
 * data, it is auditable, and its failure modes are obvious. A trained model is the
 * documented Phase 2 upgrade.
 */
object FallDetectionRule : AnomalyRule {
    override val id = "motion.fall"

    /** Motion at or below this after an impact counts as immobile. */
    private const val IMMOBILE_G = 1.05f
    /** Immobility required before escalating to CRITICAL. */
    private const val IMMOBILE_MILLIS = 20_000L

    override fun evaluate(ctx: DetectionContext): AnomalyCandidate? {
        val t = ctx.thresholds
        val samples = ctx.window.samples
        if (samples.isEmpty()) return null

        val impactIdx = samples.indexOfLast {
            (it.motionMagnitudeG ?: 0f) >= t.fallImpactG
        }
        if (impactIdx < 0) return null

        val impact = samples[impactIdx]
        val after = samples.drop(impactIdx + 1)

        // Nothing after the impact yet — report it, but do not claim immobility.
        if (after.isEmpty()) {
            return ctx.candidate(AnomalyType.FALL_DETECTED, Severity.MODERATE, id)
        }

        val allStill = after.all { (it.motionMagnitudeG ?: 0f) <= IMMOBILE_G }
        val stillFor = after.last().timestamp - impact.timestamp

        val severity =
            if (allStill && stillFor >= IMMOBILE_MILLIS) Severity.CRITICAL
            else Severity.MODERATE

        return ctx.candidate(
            AnomalyType.FALL_DETECTED, severity, id,
            durationMinutes = (stillFor / 60_000L).toInt().takeIf { it > 0 }
        )
    }
}

/**
 * Fatigue: an elevated resting heart rate held over a long stretch of inactivity.
 *
 * Informational only (LOW). This is a wellness signal, not a medical event, and
 * dressing it up as anything more would dilute the alerts that matter.
 */
object FatigueRule : AnomalyRule {
    override val id = "wellness.fatigue"
    private const val ELEVATED_RESTING_HR = 95
    private const val REQUIRED_MINUTES = 20

    override fun evaluate(ctx: DetectionContext): AnomalyCandidate? {
        if (!ctx.window.isAtRest) return null
        val hr = ctx.latest?.heartRate ?: return null
        if (hr < ELEVATED_RESTING_HR) return null
        // Defer to the tachycardia rule once it would fire on its own.
        if (hr >= ctx.thresholds.hrHighBpm) return null

        val held = ctx.window.sustained({ it.heartRate }, { it >= ELEVATED_RESTING_HR })
        if (!held.atLeastMinutes(REQUIRED_MINUTES, minSamples = 5)) return null

        return ctx.candidate(
            AnomalyType.FATIGUE, Severity.LOW, id,
            durationMinutes = held.minutes
        )
    }
}

// ── Personalised ──────────────────────────────────────────────────────────────

/**
 * Deviation from the patient's OWN normal.
 *
 * THIS IS THE RULE THAT MAKES EARLY WARNING POSSIBLE.
 *
 * Every other rule waits for a fixed threshold. This one fires while all vitals are
 * still nominally "normal" — a resting heart rate 25% above someone's personal
 * baseline with SpO2 down three points is invisible to fixed thresholds but is
 * exactly the 24–48-hour pre-deterioration signature. PS26181 asks for tracking
 * "changes in baseline health patterns"; this is that requirement, implemented.
 *
 * Gated on [PatientBaseline.isReliable] and on being at rest, because a baseline
 * built from too few samples, or a comparison made mid-exercise, would generate
 * confident nonsense.
 */
object BaselineDeviationRule : AnomalyRule {
    override val id = "baseline.deviation"

    override fun evaluate(ctx: DetectionContext): AnomalyCandidate? {
        val t = ctx.thresholds
        val baseline = ctx.baseline
        if (!baseline.isReliable(t)) return null
        if (!ctx.window.isAtRest) return null

        val latest = ctx.latest ?: return null

        var deltaPct: Float? = null
        var triggered = false

        val restingBaseline = baseline.restingHeartRate
        val hr = latest.heartRate
        if (restingBaseline != null && hr != null && restingBaseline > 0f) {
            val d = RiskScorer.percentDelta(hr.toFloat(), restingBaseline)
            if (d >= t.baselineHrDeviationPct) {
                triggered = true
                deltaPct = d
            }
        }

        val spo2Baseline = baseline.typicalSpo2
        val spo2 = latest.spo2
        if (!triggered && spo2Baseline != null && spo2 != null) {
            val drop = spo2Baseline - spo2.toFloat()
            if (drop >= t.baselineSpo2DropPoints) {
                triggered = true
                deltaPct = -RiskScorer.percentDelta(spo2.toFloat(), spo2Baseline)
            }
        }

        if (!triggered) return null

        // Escalate only when the deviation is large; a 20% drift is worth flagging,
        // not worth alarming over.
        val severity =
            if (abs(deltaPct ?: 0f) >= t.baselineHrDeviationPct * 2f) Severity.MODERATE
            else Severity.LOW

        return ctx.candidate(
            AnomalyType.BASELINE_DEVIATION, severity, id,
            trend = ctx.window.hrTrend(t.hrTrendStableBand),
            baselineDeltaPct = deltaPct
        )
    }
}

/** Evaluation order is stable so results are reproducible run to run. */
object AnomalyRules {
    val ALL: List<AnomalyRule> = listOf(
        CriticalSpo2Rule,
        SustainedSpo2Rule,
        HighHeartRateRule,
        LowHeartRateRule,
        FeverRule,
        HeatStressRule,
        DehydrationRiskRule,
        RespiratoryDistressRule,
        CardiovascularStrainRule,
        FallDetectionRule,
        FatigueRule,
        BaselineDeviationRule
    )
}
