package com.infinity.ai.health.domain

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
    val bodyTempC: Float? = null,
    /** Accelerometer magnitude in g. ~1.0 at rest (gravity only). */
    val motionMagnitudeG: Float? = null,
    val ambientTempC: Float? = null,
    val ambientHumidityPct: Float? = null,
    val aqi: Int? = null
) {
    /** True when the sample carries at least one physiological signal. */
    val hasVitals: Boolean
        get() = heartRate != null || spo2 != null || bodyTempC != null

    companion object {
        /** Motion magnitude below this is treated as "at rest" for artifact rejection. */
        const val REST_MOTION_G = 1.15f
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
