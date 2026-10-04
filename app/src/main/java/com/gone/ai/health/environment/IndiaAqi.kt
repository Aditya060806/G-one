package com.gone.ai.health.environment

import kotlin.math.roundToInt

/**
 * India's National Air Quality Index (CPCB), computed from pollutant concentrations.
 *
 * PURE KOTLIN. The breakpoints are CPCB's. Each pollutant gets a sub-index by straight-line
 * interpolation inside its band; the index is the largest sub-index. CPCB requires at least three
 * pollutants, one of them PM2.5 or PM10; with less, no index is given.
 *
 * Bands are joined end to end (61–90 is read as "above 60 up to 90"), so a value between CPCB's
 * printed whole-number edges still gets a sub-index; this can differ from CPCB's own calculator
 * by a point or two inside a band.
 *
 * Two honest limits. The concentrations are Open-Meteo's modelled values for the city, not a
 * monitoring station. And above the "Very Poor" band CPCB gives no upper concentration, so G-one
 * extends the line to twice the band's lower edge for 401–500 and stops at 500.
 */
object IndiaAqi {

    /** Concentrations, µg/m³ except carbon monoxide in mg/m³. Null when not available. */
    data class Averages(
        val pm25Day: Float?,
        val pm10Day: Float?,
        val no2Day: Float?,
        val so2Day: Float?,
        val o3EightHours: Float?,
        val coEightHours: Float?,
        val observedAt: Long
    )

    data class Result(val value: Int, val category: String, val dominant: String, val subIndices: Map<String, Int>)

    private val INDEX_BANDS = listOf(0 to 50, 51 to 100, 101 to 200, 201 to 300, 301 to 400, 401 to 500)
    val CATEGORIES = listOf("Good", "Satisfactory", "Moderate", "Poor", "Very Poor", "Severe")

    /** Upper concentration edge of each of the first five bands. */
    private val BREAKPOINTS = mapOf(
        "PM2.5" to floatArrayOf(30f, 60f, 90f, 120f, 250f),
        "PM10" to floatArrayOf(50f, 100f, 250f, 350f, 430f),
        "NO2" to floatArrayOf(40f, 80f, 180f, 280f, 400f),
        "SO2" to floatArrayOf(40f, 80f, 380f, 800f, 1600f),
        "O3" to floatArrayOf(50f, 100f, 168f, 208f, 748f),
        "CO" to floatArrayOf(1f, 2f, 10f, 17f, 34f)
    )

    fun subIndex(pollutant: String, concentration: Float): Int? {
        val edges = BREAKPOINTS[pollutant] ?: return null
        if (!concentration.isFinite() || concentration < 0f) return null
        var low = 0f
        for (band in 0 until 5) {
            val high = edges[band]
            if (concentration <= high) {
                val (iLow, iHigh) = INDEX_BANDS[band]
                return (iLow + (concentration - low) * (iHigh - iLow) / (high - low)).roundToInt()
            }
            low = high
        }
        // Severe: CPCB gives no upper concentration. Extended to twice the edge, capped at 500.
        val edge = edges[4]
        return (401 + (concentration - edge) * 99f / edge).roundToInt().coerceAtMost(500)
    }

    fun category(value: Int): String = CATEGORIES[INDEX_BANDS.indexOfFirst { value <= it.second }.takeIf { it >= 0 } ?: 5]

    /**
     * The averages CPCB's index uses, from hourly series: 24 hours for PM2.5, PM10, NO2 and SO2,
     * 8 hours for ozone and carbon monoxide, over the hours up to [now]. Carbon monoxide comes in
     * µg/m³ and is turned into mg/m³. A pollutant needs at least three quarters of its hours.
     *
     * @param series keyed by Open-Meteo's names; NaN where an hour is missing.
     */
    fun averages(times: LongArray, series: Map<String, FloatArray>, now: Long): Averages {
        fun mean(name: String, hours: Int, scale: Float = 1f): Float? {
            val values = series[name] ?: return null
            val chosen = times.indices.filter { times[it] <= now && times[it] > now - hours * 3_600_000L && it < values.size }
                .map { values[it] }.filter { it.isFinite() }
            return if (chosen.size * 4 >= hours * 3) (chosen.average() / scale).toFloat() else null
        }
        return Averages(
            pm25Day = mean("pm2_5", 24),
            pm10Day = mean("pm10", 24),
            no2Day = mean("nitrogen_dioxide", 24),
            so2Day = mean("sulphur_dioxide", 24),
            o3EightHours = mean("ozone", 8),
            coEightHours = mean("carbon_monoxide", 8, scale = 1000f),
            observedAt = times.filter { it <= now }.maxOrNull() ?: now
        )
    }

    fun compute(a: Averages): Result? {
        val subs = linkedMapOf<String, Int>()
        a.pm25Day?.let { c -> subIndex("PM2.5", c)?.let { subs["PM2.5"] = it } }
        a.pm10Day?.let { c -> subIndex("PM10", c)?.let { subs["PM10"] = it } }
        a.no2Day?.let { c -> subIndex("NO2", c)?.let { subs["NO2"] = it } }
        a.so2Day?.let { c -> subIndex("SO2", c)?.let { subs["SO2"] = it } }
        a.o3EightHours?.let { c -> subIndex("O3", c)?.let { subs["O3"] = it } }
        a.coEightHours?.let { c -> subIndex("CO", c)?.let { subs["CO"] = it } }
        if (subs.size < 3 || ("PM2.5" !in subs && "PM10" !in subs)) return null
        val (dominant, value) = subs.maxBy { it.value }
        return Result(value, category(value), dominant, subs)
    }
}
