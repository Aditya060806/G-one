package com.infinity.ai.health.detect

import com.infinity.ai.health.domain.AnomalyThresholds

/**
 * Heat index ("feels like" temperature) from dry-bulb temperature and relative
 * humidity, using the NWS Rothfusz regression.
 *
 * WHY NOT JUST USE AIR TEMPERATURE
 *
 * 38 °C at 25% humidity and 38 °C at 80% humidity are not the same physiological
 * threat. Sweat evaporation is the body's only real cooling mechanism above ~35 °C,
 * and high humidity disables it. For a heat-wave feature aimed at India — where
 * coastal humidity routinely sits above 70% — comparing raw air temperature against
 * a fixed threshold would badly understate risk in exactly the conditions that kill
 * people. So the engine reasons about heat index, not temperature.
 *
 * Implemented in Fahrenheit because the published regression coefficients are
 * defined there, then converted back. Pure function, no Android dependency.
 */
object HeatIndex {

    fun cToF(c: Float): Float = c * 9f / 5f + 32f
    fun fToC(f: Float): Float = (f - 32f) * 5f / 9f

    /**
     * @param tempC dry-bulb air temperature in °C
     * @param humidityPct relative humidity, 0–100
     * @return heat index in °C
     */
    fun computeC(tempC: Float, humidityPct: Float): Float {
        val rh = humidityPct.coerceIn(0f, 100f)
        val t = cToF(tempC)

        // Below ~80 °F the regression is not valid and overshoots; the NWS uses a
        // simple average-based form in that range instead.
        if (t < 80f) {
            val simple = 0.5f * (t + 61f + ((t - 68f) * 1.2f) + (rh * 0.094f))
            // The simple form can read slightly below air temperature; heat index is
            // defined as an apparent temperature, so never report cooler than actual.
            return fToC(maxOf(simple, t))
        }

        var hi = (-42.379
            + 2.04901523 * t
            + 10.14333127 * rh
            - 0.22475541 * t * rh
            - 0.00683783 * t * t
            - 0.05481717 * rh * rh
            + 0.00122874 * t * t * rh
            + 0.00085282 * t * rh * rh
            - 0.00000199 * t * t * rh * rh)

        // Published corrections at the edges of the fitted surface.
        if (rh < 13f && t in 80f..112f) {
            hi -= ((13.0 - rh) / 4.0) * Math.sqrt((17.0 - Math.abs(t - 95.0)) / 17.0)
        } else if (rh > 85f && t in 80f..87f) {
            hi += ((rh - 85.0) / 10.0) * ((87.0 - t) / 5.0)
        }

        return fToC(hi.toFloat())
    }

    /**
     * Compute heat index only when both inputs are available.
     *
     * Returns null rather than substituting a default: a fabricated humidity value
     * would produce a confident-looking heat index that no sensor supports, and that
     * number could go on to justify a heat-stress alert.
     */
    fun computeOrNull(tempC: Float?, humidityPct: Float?): Float? {
        if (tempC == null) return null
        if (humidityPct == null) return null
        return computeC(tempC, humidityPct)
    }
}

/**
 * Environmental risk framing derived from the latest sample.
 *
 * This is what lets the same physiological reading mean different things in
 * different conditions — the "disaster-specific alerts" requirement. A resting HR of
 * 118 during a 45 °C heat index is a very different signal than the same HR in a
 * 24 °C room.
 */
data class EnvironmentContext(
    val ambientTempC: Float? = null,
    val humidityPct: Float? = null,
    val heatIndexC: Float? = null,
    val aqi: Int? = null
) {
    fun isHeatWave(t: AnomalyThresholds): Boolean =
        (heatIndexC ?: ambientTempC)?.let { it >= t.heatIndexWarningC } == true

    fun isSevereHeat(t: AnomalyThresholds): Boolean =
        (heatIndexC ?: ambientTempC)?.let { it >= t.heatIndexCriticalC } == true

    fun isPoorAir(t: AnomalyThresholds): Boolean =
        aqi?.let { it >= t.aqiUnhealthy } == true

    fun isSevereAir(t: AnomalyThresholds): Boolean =
        aqi?.let { it >= t.aqiSevere } == true

    /** True when nothing environmental is known — rules must then stay silent. */
    val isUnknown: Boolean
        get() = ambientTempC == null && humidityPct == null && aqi == null

    companion object {
        val NONE = EnvironmentContext()

        fun from(
            ambientTempC: Float?,
            humidityPct: Float?,
            aqi: Int?
        ) = EnvironmentContext(
            ambientTempC = ambientTempC,
            humidityPct  = humidityPct,
            heatIndexC   = HeatIndex.computeOrNull(ambientTempC, humidityPct),
            aqi          = aqi
        )
    }
}
