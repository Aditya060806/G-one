package com.gone.ai.health.ui

import org.junit.Assert.*
import org.junit.Test

class TelemetryPlotTest {
    @Test fun `high impact is inside plot instead of clipped to nominal maximum`() {
        val plot = TelemetryPlot.from(listOf(ChartPoint(0, 1f), ChartPoint(1000, 6.2f)), 0.8f, 3.5f)
        assertTrue(plot.maximum > 6.2f)
        assertTrue(plot.minimum < 1f)
        assertEquals(6.2f, plot.points.last().value)
    }
    @Test fun `irregular packets keep their actual time spacing`() {
        val plot = TelemetryPlot.from(listOf(ChartPoint(0, 30f), ChartPoint(1000, 31f), ChartPoint(10000, 32f)), 26f, 38f)
        assertEquals(0.1f, plot.fractionAt(1000), 0.0001f)
    }
    @Test fun `invalid data is omitted and duplicated timestamps use last reading`() {
        val plot = TelemetryPlot.from(listOf(ChartPoint(2, Float.NaN), ChartPoint(1, 30f), ChartPoint(1, 31f), ChartPoint(3, Float.POSITIVE_INFINITY)), 26f, 38f)
        assertEquals(listOf(ChartPoint(1, 31f)), plot.points)
        assertEquals(0.5f, plot.fractionAt(1), 0f)
    }
    @Test fun `empty data stays empty and has a nonzero range`() {
        val plot = TelemetryPlot.from(emptyList(), 30f, 30f)
        assertTrue(plot.points.isEmpty())
        assertTrue(plot.maximum > plot.minimum)
    }
    @Test fun `skin readings retain their actual values`() {
        val plot = TelemetryPlot.from(listOf(ChartPoint(100, 33.4f)), 26f, 38f)
        assertEquals(33.4f, plot.points.single().value)
        assertEquals(0.5f, plot.fractionAt(100), 0f)
    }
}
