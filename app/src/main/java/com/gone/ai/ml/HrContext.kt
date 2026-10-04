package com.gone.ai.ml

import com.gone.ai.health.detect.HeartRateBand
import com.gone.ai.health.detect.HeartRateExpectation
import com.gone.ai.health.domain.VitalsSample
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What the heart-rate-in-context model reads, built from the app's stored 5-second readings.
 *
 * PURE KOTLIN, and a twin of `ml/common/hr.py`, which is the specification. `HrContextTest` runs
 * both over the same recordings (`hr_context_golden.txt`) and requires the same numbers.
 *
 * Movement is a reading's largest MOT, in g: 1.0 is still, anything above is movement on top of
 * gravity.
 */
object HrContextFeatures {

    const val REST_MOTION_G = VitalsSample.REST_MOTION_G
    /** MOT is capped here before use; the training data's wrist sensor read further. */
    const val MOTION_CAP_G = 4f
    /** Four fifths of the last five minutes of readings must be there. */
    const val MIN_READINGS_5_MIN = 48
    /** The model's outputs are beats a minute above resting, divided by this. */
    const val OUTPUT_SCALE = 50f
    const val FEATURES = 7
    private val SPANS_MILLIS = longArrayOf(30_000, 60_000, 120_000, 300_000)

    private fun activity(motion: Float): Double = max(0.0, min(motion, MOTION_CAP_G).toDouble() - 1.0)

    /**
     * The 7 model inputs at [now], or null when there is too little movement data:
     * mean activity over the last 30 s, 60 s, 120 s and 300 s; the largest activity and its
     * spread (population standard deviation) over the last 60 s; the share of the last 300 s
     * spent moving. "The last 30 s" means readings with now − 30 s < time ≤ now.
     *
     * @param motion NaN where a reading has no movement value.
     */
    fun features(times: LongArray, motion: FloatArray, now: Long): FloatArray? {
        require(times.size == motion.size) { "times and motion must be the same length" }
        fun chosen(span: Long) = times.indices.filter { !motion[it].isNaN() && times[it] > now - span && times[it] <= now }

        val last5 = chosen(300_000)
        if (last5.size < MIN_READINGS_5_MIN || chosen(30_000).isEmpty()) return null
        val out = FloatArray(FEATURES)
        SPANS_MILLIS.forEachIndexed { k, span ->
            val c = chosen(span)
            out[k] = (c.sumOf { activity(motion[it]) } / c.size).toFloat()
        }
        val minute = chosen(60_000).map { activity(motion[it]) }
        val mean = minute.average()
        out[4] = minute.max().toFloat()
        out[5] = sqrt(minute.sumOf { (it - mean) * (it - mean) } / minute.size).toFloat()
        out[6] = (last5.count { motion[it] > REST_MOTION_G }.toDouble() / last5.size).toFloat()
        return out
    }

    /** The model's three outputs to the expected band, in beats a minute, around [resting]. */
    fun band(outputs: FloatArray, resting: Float, version: Int): HeartRateBand {
        val sorted = outputs.sortedArray()
        return HeartRateBand(
            low = resting + sorted[0] * OUTPUT_SCALE,
            middle = resting + sorted[1] * OUTPUT_SCALE,
            high = resting + sorted[2] * OUTPUT_SCALE,
            modelVersion = version
        )
    }
}

/** The heart-rate-in-context model, as the monitoring pipeline asks it. */
class HrContextModel(private val scorer: Scorer, private val version: Int = 1) : HeartRateExpectation {
    override fun expected(samples: List<VitalsSample>, now: Long, restingHeartRate: Float): HeartRateBand? {
        val recent = samples.filter { it.timestamp > now - 300_000 && it.timestamp <= now }
        val times = LongArray(recent.size) { recent[it].timestamp }
        val motion = FloatArray(recent.size) { recent[it].motionMagnitudeG ?: Float.NaN }
        val features = HrContextFeatures.features(times, motion, now) ?: return null
        return HrContextFeatures.band(scorer.score(features), restingHeartRate, version)
    }
}
