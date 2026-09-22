package com.gone.ai.health.ui

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What a chart shows, as one sentence for TalkBack.
 *
 * A chart is a picture, so without this a screen reader skipped it or read nothing useful.
 * Only the numbers drawn are used: the range, the latest value and how much time it covers.
 */
object ChartSummary {

    fun describe(title: String, unit: String, readings: List<ChartPoint>): String {
        val points = readings.filter { it.value.isFinite() }
        if (points.isEmpty()) return "$title chart: no readings yet."
        val values = points.map { it.value }
        val lo = values.min()
        val hi = values.max()
        val decimals = if (hi - lo < 10f && values.any { abs(it - it.roundToInt()) >= 0.05f }) 1 else 0
        fun number(v: Float) = String.format(Locale.US, "%.${decimals}f", v)
        fun withUnit(v: Float) = listOf(number(v), unit).filter { it.isNotBlank() }.joinToString(" ")
        val latest = withUnit(points.maxBy { it.timestamp }.value)
        if (points.size == 1 || lo == hi) return "$title chart: ${span(points)}steady at $latest."
        return "$title chart: ${span(points)}from ${number(lo)} to ${withUnit(hi)}, latest $latest."
    }

    /** The unit in a shown value such as "72 BPM" or "33.4 °C". */
    fun unitOf(value: String): String = value.trim().replace(Regex("^[+−-]?(?:[0-9]+(?:[.,][0-9]+)?|--?)"), "").trim()

    private fun span(points: List<ChartPoint>): String {
        val minutes = (points.maxOf { it.timestamp } - points.minOf { it.timestamp }) / 60_000L
        return when {
            points.size < 2 -> ""
            minutes < 1 -> "last minute, "
            minutes < 120 -> "last $minutes minutes, "
            else -> "last ${(minutes + 30) / 60} hours, "
        }
    }
}
