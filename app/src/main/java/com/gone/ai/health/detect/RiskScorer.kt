package com.gone.ai.health.detect

import com.gone.ai.health.domain.AnomalyThresholds
import com.gone.ai.health.domain.PatientBaseline
import com.gone.ai.health.domain.RiskScores
import com.gone.ai.health.domain.Trend
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Continuous 0–100 risk scores for the three axes PS26181 names by name: heat,
 * respiratory, and cardiovascular stress.
 *
 * WHY SCORES EXIST SEPARATELY FROM RULES
 *
 * Rules are binary and threshold-driven — they answer "should we alert right now".
 * Scores are continuous and always computed, even when nothing fires. That gap is
 * the entire early-warning story: a heat score climbing 20 → 45 → 70 over an
 * afternoon is visible on the dashboard long before any threshold is crossed, which
 * is what lets someone act *before* the emergency. A system that only ever showed
 * "fine" until it suddenly said "critical" would be useless for prevention.
 *
 * Design constraints, all covered by tests:
 *   - MONOTONIC: a worse input never lowers a score. This is what makes the number
 *     trustworthy as a trend line.
 *   - BOUNDED: always 0..100, so no clamp bugs leak to the UI.
 *   - DEGRADES GRACEFULLY: missing signals contribute 0 rather than poisoning the
 *     result, so a device without an ambient sensor still yields useful vitals-based
 *     scores.
 */
object RiskScorer {

    fun score(
        window: VitalsWindow,
        baseline: PatientBaseline,
        thresholds: AnomalyThresholds
    ): RiskScores {
        if (window.isEmpty) return RiskScores.ZERO
        return RiskScores(
            heat           = heatScore(window, thresholds),
            respiratory    = respiratoryScore(window, thresholds),
            cardiovascular = cardiovascularScore(window, baseline, thresholds)
        )
    }

    // ── Heat ──────────────────────────────────────────────────────────────────

    /**
     * Blends environmental load with the body's actual response, because either
     * alone is misle: a fit person in 45 °C shade may be coping, while a frail
     * person at 38 °C with a rising core temperature is not.
     */
    internal fun heatScore(window: VitalsWindow, t: AnomalyThresholds): Int {
        val env = window.environment
        val latest = window.latest ?: return 0

        // Environmental load: nothing below 32 °C, saturating at the critical index.
        val envLoad = (env.heatIndexC ?: env.ambientTempC)
            ?.let { ramp(it, 32f, t.heatIndexCriticalC + 3f) } ?: 0f

        // Core temperature response: 37.2 °C is unremarkable, 40 °C is an emergency.
        val coreLoad = latest.bodyTempC?.let { ramp(it, 37.2f, 40.0f) } ?: 0f

        // Humidity amplifies, it does not cause. Only counts once it is actually hot.
        val humidityLoad =
            if (envLoad > 0f) latest.ambientHumidityPct?.let { ramp(it, 50f, 95f) } ?: 0f
            else 0f

        // Cardiac response to heat, only credited at rest — a high HR while walking
        // in the sun is expected and would otherwise inflate every daytime score.
        val strainLoad =
            if (window.isAtRest && envLoad > 0f)
                latest.heartRate?.let { ramp(it.toFloat(), 90f, t.hrHighBpm.toFloat()) } ?: 0f
            else 0f

        val blended = 0.42f * envLoad +
            0.30f * coreLoad +
            0.13f * humidityLoad +
            0.15f * strainLoad

        return pct(blended)
    }

    // ── Respiratory ───────────────────────────────────────────────────────────

    internal fun respiratoryScore(window: VitalsWindow, t: AnomalyThresholds): Int {
        val latest = window.latest ?: return 0

        // SpO2 dominates: 97+ is fine, 88 and below is severe.
        val satLoad = latest.spo2?.let { inverseRamp(it.toFloat(), 97f, 88f) } ?: 0f

        // Air quality is a genuine respiratory stressor during pollution events.
        val airLoad = latest.aqi?.let {
            ramp(it.toFloat(), t.aqiUnhealthy.toFloat() * 0.6f, t.aqiSevere.toFloat() + 100f)
        } ?: 0f

        // A falling trend matters even while the absolute value still looks acceptable
        // — that is the difference between warning early and reporting late.
        val trendLoad = if (window.size >= t.minSamplesForTrend &&
            window.spo2Trend(t.spo2TrendStableBand) == Trend.FALLING
        ) 0.5f else 0f

        // Sustained mild desaturation, weighted by how long it has held.
        val sustained = window.sustained(
            extract = { it.spo2 },
            holds = { it < t.spo2SustainedBelow }
        )
        val durationLoad =
            if (sustained.sampleCount >= 2)
                ramp(sustained.minutes.toFloat(), 0f, t.spo2SustainedMinutes.toFloat() * 2f)
            else 0f

        val blended = 0.52f * satLoad +
            0.20f * airLoad +
            0.12f * trendLoad +
            0.16f * durationLoad

        return pct(blended)
    }

    // ── Cardiovascular ────────────────────────────────────────────────────────

    internal fun cardiovascularScore(
        window: VitalsWindow,
        baseline: PatientBaseline,
        t: AnomalyThresholds
    ): Int {
        val latest = window.latest ?: return 0
        val hr = latest.heartRate

        // Distance outside the safe band, in whichever direction.
        val absoluteLoad = hr?.let {
            when {
                it > t.hrLowBpm && it < 100 -> 0f
                it >= 100 -> ramp(it.toFloat(), 100f, t.hrCriticalHighBpm.toFloat())
                else      -> inverseRamp(it.toFloat(), t.hrLowBpm.toFloat() + 10f, 35f)
            }
        } ?: 0f

        // Deviation from the patient's OWN resting normal — the personalised half.
        val baselineLoad =
            if (baseline.isReliable(t) && window.isAtRest && hr != null && baseline.restingHeartRate != null) {
                val deltaPct = percentDelta(hr.toFloat(), baseline.restingHeartRate)
                ramp(abs(deltaPct), t.baselineHrDeviationPct * 0.5f, t.baselineHrDeviationPct * 2.5f)
            } else 0f

        // Rising HR while SpO2 falls is far more specific to real distress than
        // either signal alone — the body compensating for poor oxygenation.
        val couplingLoad =
            if (window.size >= t.minSamplesForTrend &&
                window.hrTrend(t.hrTrendStableBand) == Trend.RISING &&
                window.spo2Trend(t.spo2TrendStableBand) == Trend.FALLING
            ) 1f else 0f

        val feverLoad = latest.bodyTempC?.let { ramp(it, t.tempFeverC, t.tempCriticalFeverC) } ?: 0f

        val blended = 0.42f * absoluteLoad +
            0.24f * baselineLoad +
            0.22f * couplingLoad +
            0.12f * feverLoad

        return pct(blended)
    }

    // ── Normalisation primitives ──────────────────────────────────────────────

    /** 0 at or below [low], 1 at or above [high], linear between. */
    internal fun ramp(value: Float, low: Float, high: Float): Float {
        if (high <= low) return if (value >= high) 1f else 0f
        return ((value - low) / (high - low)).coerceIn(0f, 1f)
    }

    /** Inverted [ramp] for metrics where lower is worse, e.g. SpO2. */
    internal fun inverseRamp(value: Float, good: Float, bad: Float): Float {
        if (good <= bad) return ((good - value) / (good - bad)).coerceIn(0f, 1f)
        return ((good - value) / (good - bad)).coerceIn(0f, 1f)
    }

    internal fun percentDelta(actual: Float, reference: Float): Float {
        if (reference == 0f) return 0f
        return (actual - reference) / reference * 100f
    }

    private fun pct(unit: Float): Int = (unit.coerceIn(0f, 1f) * 100f).roundToInt()
}
