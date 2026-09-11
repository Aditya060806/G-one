package com.infinity.ai.health.detect

import com.infinity.ai.health.domain.Trend
import com.infinity.ai.health.domain.VitalsSample
import com.infinity.ai.health.domain.classifyTrend

/**
 * Window helpers shared by every rule.
 *
 * ORDERING CONTRACT — read this before touching a rule.
 *
 * Every function here assumes CHRONOLOGICAL order: oldest first, newest LAST.
 *
 * The DAO deliberately returns newest-first (`ORDER BY timestamp DESC`) because that
 * is what an index range scan can serve efficiently, so the caller MUST reverse
 * before handing samples to the detector. [VitalsWindow.of] does that and is the only
 * sanctioned way to build one — passing a raw DAO list straight in would invert every
 * trend in the system and turn a falling SpO2 into a rising one.
 */
class VitalsWindow private constructor(
    /** Oldest first, newest last. */
    val samples: List<VitalsSample>
) {
    val size: Int get() = samples.size
    val isEmpty: Boolean get() = samples.isEmpty()

    /** Most recent sample, or null on an empty window. */
    val latest: VitalsSample? get() = samples.lastOrNull()
    val oldest: VitalsSample? get() = samples.firstOrNull()

    /** Wall-clock span covered by the window. */
    val spanMillis: Long
        get() {
            val a = oldest ?: return 0L
            val b = latest ?: return 0L
            return b.timestamp - a.timestamp
        }

    val environment: EnvironmentContext
        get() {
            val l = latest ?: return EnvironmentContext.NONE
            return EnvironmentContext.from(l.ambientTempC, l.ambientHumidityPct, l.aqi)
        }

    /** True when the newest sample shows the patient is essentially still. */
    val isAtRest: Boolean
        get() {
            val m = latest?.motionMagnitudeG ?: return true   // unknown motion → assume rest
            return m <= VitalsSample.REST_MOTION_G
        }

    fun heartRates(): List<Float> = samples.mapNotNull { it.heartRate?.toFloat() }
    fun spo2Values(): List<Float> = samples.mapNotNull { it.spo2?.toFloat() }
    fun bodyTemps(): List<Float> = samples.mapNotNull { it.bodyTempC }

    /** Peak motion magnitude across the window, for impact detection. */
    fun peakMotion(): Float? = samples.mapNotNull { it.motionMagnitudeG }.maxOrNull()

    fun hrTrend(stableBand: Float): Trend = classifyTrend(heartRates(), stableBand)
    fun spo2Trend(stableBand: Float): Trend = classifyTrend(spo2Values(), stableBand)

    /**
     * How long a condition has held continuously, counting back from the newest
     * sample.
     *
     * Null handling is the subtle part. A sample where the field is absent is
     * *unknown*, not *false*: an SpO2 sensor briefly losing contact must not reset a
     * genuine 10-minute desaturation streak, or the sustained rule would almost never
     * fire on real hardware. So nulls are skipped, and only a present-and-failing
     * value breaks the streak.
     *
     * @return span in millis from the earliest continuously-holding sample to the
     *   newest, plus how many samples supported it.
     */
    fun <T> sustained(
        extract: (VitalsSample) -> T?,
        holds: (T) -> Boolean
    ): Sustained {
        val newest = latest ?: return Sustained.NONE
        var earliestHolding: Long? = null
        var count = 0

        for (s in samples.asReversed()) {
            val v = extract(s) ?: continue        // unknown — neither extends nor breaks
            if (!holds(v)) break                  // known and failing — streak ends
            earliestHolding = s.timestamp
            count++
        }

        val start = earliestHolding ?: return Sustained.NONE
        return Sustained(millis = newest.timestamp - start, sampleCount = count)
    }

    companion object {
        /**
         * Build a window from samples in ANY order; they are sorted chronologically.
         *
         * Sorting rather than trusting the caller is intentional — the DAO returns
         * DESC, the simulator produces ASC, and a replayed SD-card backlog can
         * interleave. One normalisation point removes a whole class of silent bugs.
         */
        fun of(samples: List<VitalsSample>): VitalsWindow =
            VitalsWindow(samples.sortedBy { it.timestamp })

        val EMPTY = VitalsWindow(emptyList())
    }
}

/** Result of a sustained-condition query. */
data class Sustained(val millis: Long, val sampleCount: Int) {

    fun atLeastMinutes(minutes: Int, minSamples: Int = 2): Boolean =
        sampleCount >= minSamples && millis >= minutes * 60_000L

    val minutes: Int get() = (millis / 60_000L).toInt()

    companion object {
        val NONE = Sustained(0L, 0)
    }
}
