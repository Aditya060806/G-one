package com.infinity.ai.health.domain

/**
 * Every threshold the detection engine uses, in one injectable value object.
 *
 * WHY THIS IS A DATA CLASS AND NOT CONSTANTS
 *
 * These numbers are clinical decisions, not implementation details. Hard-coding
 * them would mean (a) tests could not explore boundary behaviour without faking
 * sensor data at real physiological values, and (b) a clinician reviewing the app
 * would have to read the rule engine to find them.
 *
 * The defaults below are widely-cited general adult reference points, chosen so the
 * system is demonstrably functional out of the box. They are NOT a substitute for
 * clinically-validated, population- and patient-specific thresholds, and G-one does
 * not present them as such. Per-patient overrides are the intended path — see
 * [PatientBaseline] for the adaptive half of the story.
 */
data class AnomalyThresholds(

    // ── SpO2 (%) ──────────────────────────────────────────────────────────────
    /** Immediate critical flag regardless of duration. */
    val spo2CriticalBelow: Int = 90,
    /** Flagged only if held for [spo2SustainedMinutes]. PS26181 names this case. */
    val spo2SustainedBelow: Int = 92,
    val spo2SustainedMinutes: Int = 10,

    // ── Heart rate (bpm) ──────────────────────────────────────────────────────
    val hrHighBpm: Int = 130,
    val hrCriticalHighBpm: Int = 150,
    val hrLowBpm: Int = 45,
    val hrSustainedMinutes: Int = 5,

    // ── Body temperature (°C) ─────────────────────────────────────────────────
    val tempFeverC: Float = 38.0f,
    val tempCriticalFeverC: Float = 39.5f,

    // ── Fall detection (g) ────────────────────────────────────────────────────
    /**
     * Peak accelerometer magnitude treated as an impact. Threshold-based by design
     * for Phase 1: it is auditable and needs no training data. A learned classifier
     * is the documented Phase 2 path.
     */
    val fallImpactG: Float = 2.5f,

    // ── Environment ───────────────────────────────────────────────────────────
    /** Heat-index equivalent at which heat-stress rules tighten. */
    val heatIndexWarningC: Float = 40.0f,
    val heatIndexCriticalC: Float = 45.0f,
    val humidityHighPct: Float = 70.0f,
    /** CPCB-style AQI bands: 201+ is "poor", 301+ "very poor". */
    val aqiUnhealthy: Int = 201,
    val aqiSevere: Int = 301,

    // ── Trend / window sizing ─────────────────────────────────────────────────
    /** Window used by trend rules. */
    val trendWindowMinutes: Int = 30,
    /** Minimum samples before any trend rule is allowed to fire. */
    val minSamplesForTrend: Int = 6,
    /** bpm-per-sample band inside which HR movement is considered noise. */
    val hrTrendStableBand: Float = 0.35f,
    /** %-per-sample band inside which SpO2 movement is considered noise. */
    val spo2TrendStableBand: Float = 0.12f,

    // ── Baseline deviation ────────────────────────────────────────────────────
    /** Resting HR this far above the patient's own baseline is notable. */
    val baselineHrDeviationPct: Float = 20.0f,
    /** SpO2 this many points below the patient's own baseline is notable. */
    val baselineSpo2DropPoints: Int = 3,
    /** Baseline is only trusted once it is built from at least this many samples. */
    val baselineMinSamples: Int = 30,

    // ── Debounce ──────────────────────────────────────────────────────────────
    /**
     * Minimum gap between two events of the SAME type for the same patient.
     *
     * Without this, a vital hovering at a threshold generates an event per sample
     * and buries the user in notifications — which trains them to ignore alerts,
     * the single worst failure mode for a health-warning app.
     */
    val perTypeCooldownMinutes: Int = 15
) {
    init {
        require(spo2CriticalBelow < spo2SustainedBelow) {
            "spo2CriticalBelow ($spo2CriticalBelow) must be below spo2SustainedBelow " +
                "($spo2SustainedBelow), otherwise the critical rule can never be the " +
                "more severe of the two"
        }
        require(hrLowBpm < hrHighBpm) { "hrLowBpm must be below hrHighBpm" }
        require(hrHighBpm < hrCriticalHighBpm) { "hrHighBpm must be below hrCriticalHighBpm" }
        require(tempFeverC < tempCriticalFeverC) { "tempFeverC must be below tempCriticalFeverC" }
        require(heatIndexWarningC < heatIndexCriticalC) {
            "heatIndexWarningC must be below heatIndexCriticalC"
        }
        require(aqiUnhealthy < aqiSevere) { "aqiUnhealthy must be below aqiSevere" }
        require(minSamplesForTrend >= 2) { "trend needs at least 2 samples" }
        require(perTypeCooldownMinutes >= 0) { "cooldown cannot be negative" }
    }

    companion object {
        /** Documented defaults; see the class KDoc for their standing. */
        val DEFAULT = AnomalyThresholds()
    }
}

/**
 * The patient's own rolling normal, which is what makes "deviation from baseline"
 * meaningful rather than just another fixed threshold.
 *
 * A resting HR of 95 is unremarkable for one person and a 30% jump for another.
 * PS26181 asks explicitly for tracking "changes in baseline health patterns", and
 * this is the value object that carries it.
 *
 * [sampleCount] gates trust: a baseline built from three readings is noise, so
 * [isReliable] must be consulted before any baseline rule fires.
 */
data class PatientBaseline(
    val restingHeartRate: Float? = null,
    val typicalSpo2: Float? = null,
    val typicalBodyTempC: Float? = null,
    val sampleCount: Int = 0
) {
    fun isReliable(thresholds: AnomalyThresholds): Boolean =
        sampleCount >= thresholds.baselineMinSamples

    companion object {
        val EMPTY = PatientBaseline()

        /**
         * Build a baseline from at-rest samples only.
         *
         * Motion filtering matters: heart rate during a walk is not a resting
         * baseline, and folding it in would inflate the baseline until genuine
         * tachycardia looked normal — the rule would quietly stop working.
         */
        fun fromSamples(
            samples: List<VitalsSample>,
            restMotionG: Float = VitalsSample.REST_MOTION_G
        ): PatientBaseline {
            val atRest = samples.filter {
                val m = it.motionMagnitudeG
                m == null || m <= restMotionG
            }
            if (atRest.isEmpty()) return EMPTY

            val hr   = atRest.mapNotNull { it.heartRate }
            val spo2 = atRest.mapNotNull { it.spo2 }
            val temp = atRest.mapNotNull { it.bodyTempC }

            return PatientBaseline(
                restingHeartRate = hr.takeIf { it.isNotEmpty() }?.let { it.sum().toFloat() / it.size },
                typicalSpo2      = spo2.takeIf { it.isNotEmpty() }?.let { it.sum().toFloat() / it.size },
                typicalBodyTempC = temp.takeIf { it.isNotEmpty() }?.let { it.sum() / it.size },
                sampleCount      = atRest.size
            )
        }
    }
}
