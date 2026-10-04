package com.gone.ai.health.detect

import com.gone.ai.health.domain.VitalsSample

/**
 * What the trained models said about the readings in the window.
 *
 * MODELS ARE SENSORS. Nothing here raises an alert. A rule reads these values and applies its own
 * fixed thresholds, exactly as it reads a heart rate. A rule whose signal is missing (no model,
 * model failed its checksum, too little data) stays silent.
 */
data class ModelSignals(
    /** Expected heart-rate band, keyed by the timestamp of the reading it was worked out for. */
    val heartRateBands: Map<Long, HeartRateBand> = emptyMap()
) {
    companion object {
        val NONE = ModelSignals()
    }
}

/**
 * The heart rate to be expected for the movement of the last five minutes, beats a minute.
 * [low] to [high] is the 10th to 90th percentile the model learned; [middle] the median.
 */
data class HeartRateBand(val low: Float, val middle: Float, val high: Float, val modelVersion: Int)

/**
 * Works out a [HeartRateBand] from recent readings. An interface so the detection layer stays
 * free of Android and of the model runtime; `com.gone.ai.ml.HrContextModel` is the real one.
 */
fun interface HeartRateExpectation {
    /** @return null when there is too little movement data to say. */
    fun expected(samples: List<VitalsSample>, now: Long, restingHeartRate: Float): HeartRateBand?
}

/**
 * Weather for the wearer's chosen city, from a forecast service.
 *
 * Used by the heat rules ONLY when the wearable sends no ambient reading of its own, and never
 * written into a stored reading. Events that rest on it are capped at MODERATE, say in their text
 * that the weather is the city's, and never send an SOS.
 */
data class AreaWeather(
    val temperatureC: Float,
    val humidityPct: Float?,
    val observedAt: Long,
    val place: String
) {
    fun isFresh(now: Long): Boolean = now - observedAt in 0..MAX_AGE_MILLIS

    companion object {
        /** Older than this, the city's weather says little about the wearer's last few minutes. */
        const val MAX_AGE_MILLIS = 90L * 60 * 1000
    }
}

/** Where a rule's environmental reading came from. */
enum class EnvironmentSource { WEARABLE, CITY }
