package com.gone.ai.ml

import com.gone.ai.health.domain.VitalsSample
import kotlin.math.ln
import kotlin.math.max

/**
 * What the sleep model reads, from the app's stored 5-second movement readings.
 *
 * PURE KOTLIN, a twin of `ml/common/sleep.py` (the specification), checked against it on a real
 * night by `SleepTest` (`sleep_golden.txt`). The night is cut into 30-second epochs of six
 * readings; each epoch gets its mean and largest movement above gravity (log-scaled) and whether
 * it has at least four readings. The model sees the epoch and ten on each side.
 */
object SleepFeatures {
    const val EPOCH_READINGS = 6
    const val MIN_READINGS = 4
    const val CONTEXT = 10
    const val CHANNELS = 3
    const val FEATURES = (2 * CONTEXT + 1) * CHANNELS
    const val READING_MILLIS = 5_000L

    private fun scaled(activity: Double): Float = ln(1.0 + 20.0 * activity).toFloat()

    /** Per epoch: mean (scaled), largest (scaled), present (1 or 0). [motion] has NaN where a reading is missing. */
    fun epochs(motion: FloatArray): Array<FloatArray> = Array(motion.size / EPOCH_READINGS) { e ->
        val values = (0 until EPOCH_READINGS).map { motion[e * EPOCH_READINGS + it] }.filter { !it.isNaN() }
        if (values.size < MIN_READINGS) FloatArray(CHANNELS) else {
            val a = values.map { max(0.0, it.toDouble() - 1.0) }
            floatArrayOf(scaled(a.average()), scaled(a.max()), 1f)
        }
    }

    /** The model's input for epoch [e]: epochs e−10 … e+10, zeros past either end of the night. */
    fun features(perEpoch: Array<FloatArray>, e: Int): FloatArray {
        val out = FloatArray(FEATURES)
        for ((k, j) in (e - CONTEXT..e + CONTEXT).withIndex()) {
            if (j in perEpoch.indices) perEpoch[j].copyInto(out, k * CHANNELS)
        }
        return out
    }

    /**
     * Readings onto a regular 5-second grid starting at the first one (NaN where none was
     * stored), as the training data was laid out.
     */
    fun grid(samples: List<VitalsSample>): Pair<Long, FloatArray> {
        val sorted = samples.sortedBy { it.timestamp }
        val start = sorted.first().timestamp
        val slots = ((sorted.last().timestamp - start) / READING_MILLIS + 1).toInt()
        val motion = FloatArray(slots) { Float.NaN }
        sorted.forEach { s -> s.motionMagnitudeG?.let { motion[((s.timestamp - start) / READING_MILLIS).toInt()] = it } }
        return start to motion
    }
}

/** A night as the sleep model saw it. Shown as an estimate; the diary entry stays the wearer's own. */
data class SleepEstimate(
    val fellAsleep: Long,
    val wokeUp: Long,
    val asleepMinutes: Int,
    val awakeMinutes: Int,
    /** Share of the 30-second epochs between falling asleep and waking that had readings. */
    val coverage: Float
)

object SleepEstimator {

    /** A sleep period starts or ends where 8 of the next 10 epochs agree. */
    private const val RUN = 10
    private const val AGREE = 8
    const val MIN_COVERAGE = 0.7f
    const val MIN_ASLEEP_MINUTES = 60

    /**
     * Per epoch: asleep (true), awake (false) or no data (null).
     */
    fun epochs(motion: FloatArray, scorer: Scorer, threshold: Float): List<Boolean?> {
        val per = SleepFeatures.epochs(motion)
        return per.indices.map { e ->
            if (per[e][2] == 0f) null else scorer.score(SleepFeatures.features(per, e))[0] >= threshold
        }
    }

    /** The night's estimate, or null when there is too little data or too little sleep to say. */
    fun estimate(samples: List<VitalsSample>, scorer: Scorer, threshold: Float): SleepEstimate? {
        if (samples.size < SleepFeatures.EPOCH_READINGS * (2 * RUN)) return null
        val (start, motion) = SleepFeatures.grid(samples)
        val asleep = epochs(motion, scorer, threshold)
        return summarise(start, asleep)
    }

    /** Pure summary of per-epoch decisions; separate so it is tested without a model. */
    fun summarise(start: Long, asleep: List<Boolean?>): SleepEstimate? {
        fun sleepy(from: Int) = (from until minOf(asleep.size, from + RUN)).count { asleep[it] == true } >= AGREE
        val first = asleep.indices.firstOrNull { asleep[it] == true && sleepy(it) } ?: return null
        val last = asleep.indices.reversed().firstOrNull { asleep[it] == true && sleepy(maxOf(0, it - RUN + 1)) } ?: return null
        if (last <= first) return null
        val span = asleep.subList(first, last + 1)
        val coverage = span.count { it != null }.toFloat() / span.size
        val asleepMinutes = span.count { it == true } / 2
        if (coverage < MIN_COVERAGE || asleepMinutes < MIN_ASLEEP_MINUTES) return null
        val epochMillis = SleepFeatures.EPOCH_READINGS * SleepFeatures.READING_MILLIS
        return SleepEstimate(
            fellAsleep = start + first * epochMillis,
            wokeUp = start + (last + 1) * epochMillis,
            asleepMinutes = asleepMinutes,
            awakeMinutes = span.count { it == false } / 2,
            coverage = coverage
        )
    }
}
