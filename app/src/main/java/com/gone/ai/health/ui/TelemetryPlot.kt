package com.gone.ai.health.ui

/** Shared chart geometry: preserve timing and never clip valid readings to the nominal band. */
internal data class TelemetryPlot(val points: List<ChartPoint>, val minimum: Float, val maximum: Float) {
    fun fractionAt(timestamp: Long): Float {
        if (points.size < 2) return 0.5f
        val start = points.first().timestamp.toDouble()
        val duration = points.last().timestamp.toDouble() - start
        return if (duration <= 0) 0.5f else ((timestamp.toDouble() - start) / duration).toFloat()
    }

    companion object {
        fun from(points: List<ChartPoint>, nominalMin: Float, nominalMax: Float): TelemetryPlot {
            val valid = points.filter { it.value.isFinite() }.associateBy { it.timestamp }.values.sortedBy { it.timestamp }
            val lo = minOf(nominalMin, valid.minOfOrNull { it.value } ?: nominalMin)
            val hi = maxOf(nominalMax, valid.maxOfOrNull { it.value } ?: nominalMax)
            val padding = ((hi - lo) * 0.05f).coerceAtLeast(0.01f)
            return TelemetryPlot(valid, lo - padding, hi + padding)
        }
    }
}
