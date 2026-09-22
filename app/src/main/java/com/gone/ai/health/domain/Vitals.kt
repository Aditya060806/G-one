package com.gone.ai.health.domain

/**
 * A single point-in-time physiological + environmental observation.
 *
 * PURE KOTLIN — no Android imports anywhere in this package. That is deliberate:
 * the anomaly-detection engine is the safety-critical part of G-one, so it must be
 * testable with plain JVM unit tests (no emulator, no Robolectric, no device).
 *
 * Every field is nullable because a real wearable does not deliver every signal on
 * every sample — SpO2 requires a stable optical reading, ambient AQI may come from
 * a separate source entirely. Rules must therefore tolerate missing data rather
 * than assume a complete sample.
 */
data class VitalsSample(
    val timestamp: Long,
    val heartRate: Int? = null,
    val spo2: Int? = null,
    /** Core body temperature. Only a core reading may drive fever or heat rules. */
    val bodyTempC: Float? = null,
    /**
     * Peak accelerometer magnitude in g over the reporting interval. ~1.0 at rest
     * (gravity only). A peak rather than an average so a fall's impact survives.
     */
    val motionMagnitudeG: Float? = null,
    val ambientTempC: Float? = null,
    val ambientHumidityPct: Float? = null,
    val aqi: Int? = null,
    /**
     * Skin temperature, e.g. a DS18B20 held against the wrist.
     *
     * NOT CORE TEMPERATURE. Skin reads 2–4 °C below core and follows the room, so a
     * real fever can sit at a "normal-looking" 35 °C here. No fever, heat or risk rule
     * may read this field; it is shown and trended as skin temperature only.
     */
    val skinTempC: Float? = null,
    /**
     * EMG envelope averaged over the reporting interval, in 12-bit ADC counts
     * (0–[EMG_ADC_MAX]). Unitless and uncalibrated: the same contraction reads
     * differently with electrode placement, skin and amplifier gain.
     */
    val emgMean: Int? = null,
    /** Highest EMG envelope value within the interval, same units as [emgMean]. */
    val emgMax: Int? = null
) {
    /** True when the sample carries at least one physiological signal. */
    val hasVitals: Boolean
        get() = heartRate != null || spo2 != null || bodyTempC != null ||
            skinTempC != null || emgMean != null

    companion object {
        /** Motion magnitude below this is treated as "at rest" for artifact rejection. */
        const val REST_MOTION_G = 1.15f

        /** Full scale of the 12-bit ADC the EMG channel is sampled with. */
        const val EMG_ADC_MAX = 4095

        /**
         * EMG at or above this is pinned at the ADC rail. That is a detached electrode or
         * a saturated amplifier, not muscle activity, so no rule may treat it as such.
         */
        const val EMG_RAIL = 4090
    }
}

/** Direction of change across a window of samples. */
enum class Trend { RISING, FALLING, STABLE }

/**
 * Least-squares slope of [values] against their index, used for trend detection.
 *
 * Returns slope in "units per sample". Null when there are fewer than two points,
 * since a slope through one point is undefined rather than zero — collapsing that
 * to 0.0 would silently report STABLE for a window that has no trend information
 * at all.
 */
internal fun linearSlope(values: List<Float>): Float? {
    if (values.size < 2) return null
    val n = values.size
    val meanX = (n - 1) / 2.0
    val meanY = values.sum() / n.toDouble()
    var num = 0.0
    var den = 0.0
    values.forEachIndexed { i, y ->
        val dx = i - meanX
        num += dx * (y - meanY)
        den += dx * dx
    }
    if (den == 0.0) return null
    return (num / den).toFloat()
}

/**
 * Classify a series into a [Trend].
 *
 * [stableBand] is expressed in units-per-sample; anything inside ±stableBand is
 * reported STABLE so that sensor jitter does not read as a real trend.
 */
internal fun classifyTrend(values: List<Float>, stableBand: Float): Trend {
    val slope = linearSlope(values) ?: return Trend.STABLE
    return when {
        slope > stableBand  -> Trend.RISING
        slope < -stableBand -> Trend.FALLING
        else                -> Trend.STABLE
    }
}
