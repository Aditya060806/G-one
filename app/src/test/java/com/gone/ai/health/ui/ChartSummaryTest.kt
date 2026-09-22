package com.gone.ai.health.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ChartSummaryTest {

    private fun pts(vararg pairs: Pair<Int, Float>) = pairs.map { (min, v) -> ChartPoint(min * 60_000L, v) }

    @Test
    fun `range, latest value and time span are read out`() {
        assertEquals(
            "Heart Rate chart: last 45 minutes, from 62 to 88 BPM, latest 71 BPM.",
            ChartSummary.describe("Heart Rate", "BPM", pts(0 to 62f, 20 to 88f, 45 to 71f))
        )
    }

    @Test
    fun `small ranges keep one decimal`() {
        assertEquals(
            "Skin temperature chart: last 3 hours, from 33.1 to 33.9 °C, latest 33.4 °C.",
            ChartSummary.describe("Skin temperature", "°C", pts(0 to 33.1f, 90 to 33.9f, 170 to 33.4f))
        )
    }

    @Test
    fun `an empty chart says so`() {
        assertEquals("SpO₂ chart: no readings yet.", ChartSummary.describe("SpO₂", "%", emptyList()))
    }

    @Test
    fun `a flat or single reading is steady`() {
        assertEquals("SpO₂ chart: steady at 98 %.", ChartSummary.describe("SpO₂", "%", pts(0 to 98f)))
        assertEquals("SpO₂ chart: last 5 minutes, steady at 98 %.", ChartSummary.describe("SpO₂", "%", pts(0 to 98f, 5 to 98f)))
    }

    @Test
    fun `latest is by time, not list order`() {
        val points = listOf(ChartPoint(120_000L, 70f), ChartPoint(0L, 60f))
        assertEquals("HR chart: last 2 minutes, from 60 to 70, latest 70.", ChartSummary.describe("HR", "", points))
    }

    @Test
    fun `unit comes from the shown value`() {
        assertEquals("BPM", ChartSummary.unitOf("72 BPM"))
        assertEquals("°C", ChartSummary.unitOf("33.4 °C"))
        assertEquals("°C", ChartSummary.unitOf("33.4°C"))
        assertEquals("%", ChartSummary.unitOf("98%"))
        assertEquals("°C", ChartSummary.unitOf("--°C"))
        assertEquals("", ChartSummary.unitOf("--"))
    }
}
