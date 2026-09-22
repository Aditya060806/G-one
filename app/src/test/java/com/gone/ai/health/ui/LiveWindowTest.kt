package com.gone.ai.health.ui

import com.gone.ai.health.data.VitalsReadingEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveWindowTest {

    private fun reading(at: Long) = VitalsReadingEntity(patientId = "p", timestamp = at, heartRate = 70, source = "BLE")

    private val earlierRun = listOf(reading(1_000_000), reading(1_005_000), reading(1_010_000))

    @Test
    fun `with no start recorded, every reading is shown`() {
        assertEquals(earlierRun, liveReadings(earlierRun, chartsFrom = null))
    }

    @Test
    fun `a new start leaves the graphs empty until the first reading of the new run`() {
        assertEquals(emptyList<VitalsReadingEntity>(), liveReadings(earlierRun, chartsFrom = 2_000_000))
    }

    @Test
    fun `only readings since the start are shown`() {
        val run = earlierRun + listOf(reading(2_000_000), reading(2_005_000))
        assertEquals(listOf(2_000_000L, 2_005_000L), liveReadings(run, chartsFrom = 2_000_000).map { it.timestamp })
    }

    @Test
    fun `the first reading is kept although its bucket starts before the run did`() {
        // Started 3 s into a bucket; the reading for that bucket is stamped at the bucket's start.
        val run = earlierRun + listOf(reading(2_000_000), reading(2_005_000))
        assertEquals(listOf(2_000_000L, 2_005_000L), liveReadings(run, chartsFrom = 2_003_000).map { it.timestamp })
    }

    @Test
    fun `a session started during monitoring starts the graphs over again`() {
        val run = listOf(reading(2_000_000), reading(2_005_000), reading(2_010_000), reading(2_015_000))
        assertEquals(listOf(2_010_000L, 2_015_000L), liveReadings(run, chartsFrom = 2_010_000).map { it.timestamp })
    }
}
