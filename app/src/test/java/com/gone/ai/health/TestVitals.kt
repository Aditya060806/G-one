package com.gone.ai.health

import com.gone.ai.health.domain.VitalsSample

/**
 * Sample builders for the health tests.
 *
 * Fixed [T0] and minute-based offsets so every test reads as a physiological
 * timeline rather than a pile of epoch millis, and so failures are reproducible.
 */
object TestVitals {

    /** Arbitrary fixed instant. Tests never depend on the wall clock. */
    const val T0 = 1_700_000_000_000L

    /** Motion value that reads as "at rest" to the engine. */
    const val REST = 1.0f

    /** Motion value that reads as "moving". */
    const val MOVING = 1.6f

    fun sample(
        atMinute: Double = 0.0,
        hr: Int? = null,
        spo2: Int? = null,
        temp: Float? = null,
        motion: Float? = REST,
        ambientTemp: Float? = null,
        humidity: Float? = null,
        aqi: Int? = null,
        t0: Long = T0
    ): VitalsSample = VitalsSample(
        timestamp          = t0 + (atMinute * 60_000L).toLong(),
        heartRate          = hr,
        spo2               = spo2,
        bodyTempC          = temp,
        motionMagnitudeG   = motion,
        ambientTempC       = ambientTemp,
        ambientHumidityPct = humidity,
        aqi                = aqi
    )

    /**
     * A chronological series with optional linear ramps.
     *
     * `null` for a ramp end means "hold the start value", which keeps the common
     * flat-line case readable at the call site.
     */
    fun series(
        count: Int,
        intervalMinutes: Double = 1.0,
        hrFrom: Int? = null, hrTo: Int? = null,
        spo2From: Int? = null, spo2To: Int? = null,
        tempFrom: Float? = null, tempTo: Float? = null,
        motion: Float? = REST,
        ambientTemp: Float? = null,
        humidity: Float? = null,
        aqi: Int? = null,
        t0: Long = T0
    ): List<VitalsSample> = (0 until count).map { i ->
        val p = if (count <= 1) 0f else i.toFloat() / (count - 1)
        sample(
            atMinute    = i * intervalMinutes,
            hr          = ramp(hrFrom, hrTo, p),
            spo2        = ramp(spo2From, spo2To, p),
            temp        = rampF(tempFrom, tempTo, p),
            motion      = motion,
            ambientTemp = ambientTemp,
            humidity    = humidity,
            aqi         = aqi,
            t0          = t0
        )
    }

    private fun ramp(from: Int?, to: Int?, p: Float): Int? {
        if (from == null) return null
        if (to == null) return from
        return Math.round(from + (to - from) * p)
    }

    private fun rampF(from: Float?, to: Float?, p: Float): Float? {
        if (from == null) return null
        if (to == null) return from
        return from + (to - from) * p
    }
}
