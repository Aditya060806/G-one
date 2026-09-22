package com.gone.ai.health.domain

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The wearer's own EMG levels, from 20 seconds relaxed and 5 seconds clenching.
 *
 * The default levels in [AnomalyThresholds] are fractions of the ADC range, because an EMG
 * envelope's size depends on electrode placement, skin and amplifier gain. This replaces them
 * with levels placed between this wearer's relaxed reading and their firm clench:
 *
 *  - active    30 % of the way from relaxed to clench: a deliberate contraction
 *  - high      60 %: held at rest, a low-level finding
 *  - very high 90 %: held at rest, a moderate finding
 *
 * "Relaxed" is the 95th percentile of the relaxed readings, so a twitch does not lower the
 * levels; "clench" is the median of the clench readings, so a spike does not raise them.
 */
object EmgCalibration {

    const val RELAX_SECONDS = 20
    const val CLENCH_SECONDS = 5
    /** A pause between relaxing and clenching, so the first clench readings are not still relaxed. */
    const val GET_READY_SECONDS = 3
    /** The wearable sends two lines a second; allow for a few lost. */
    const val MIN_RELAXED_READINGS = 30
    const val MIN_CLENCH_READINGS = 6
    /** Less separation than this is noise, not a contraction. */
    const val MIN_SEPARATION = 150

    sealed interface Result {
        data class Levels(
            val relaxed: Int,
            val clench: Int,
            val active: Int,
            val high: Int,
            val veryHigh: Int
        ) : Result {
            fun applyTo(thresholds: AnomalyThresholds): AnomalyThresholds =
                thresholds.copy(emgActiveLevel = active, emgHighLevel = high, emgVeryHighLevel = veryHigh)
        }

        data class Problem(val message: String) : Result
    }

    fun compute(relaxed: List<Int>, clench: List<Int>): Result {
        if (relaxed.size < MIN_RELAXED_READINGS || clench.size < MIN_CLENCH_READINGS) {
            return Result.Problem("Too few readings arrived from the wearable. Keep it close to the phone and try again.")
        }
        val rest = percentile(relaxed, 0.95)
        val firm = percentile(clench, 0.5)
        if (firm >= VitalsSample.EMG_RAIL) {
            return Result.Problem("The clench reads at the top of the sensor's range. Lower the EMG module's gain, then calibrate again.")
        }
        if (firm - rest < MIN_SEPARATION) {
            return Result.Problem("Relaxed and clenched readings are too close. Check that the pads are firmly on the muscle, then try again.")
        }
        fun at(fraction: Double) = (rest + (firm - rest) * fraction).roundToInt()
        val levels = Result.Levels(
            relaxed = rest,
            clench = firm,
            active = at(0.3).coerceAtLeast(1),
            high = at(0.6),
            veryHigh = at(0.9)
        )
        return try {
            levels.applyTo(AnomalyThresholds.DEFAULT)
            levels
        } catch (e: IllegalArgumentException) {
            Result.Problem("These readings do not give usable levels (${e.message}). Try again.")
        }
    }

    /** Nearest-rank percentile: always a value that was actually read. */
    private fun percentile(values: List<Int>, p: Double): Int {
        val sorted = values.sorted()
        val rank = ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }
}
