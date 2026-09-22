package com.gone.ai.health.source

import com.gone.ai.health.domain.VitalsSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SampleAggregatorTest {

    /** A bucket boundary, so tests read in seconds from a clean start. */
    private val t0 = 1_700_000_000_000L - 1_700_000_000_000L % 5_000L

    private fun at(millis: Long, hr: Int? = null, spo2: Int? = null, motion: Float? = null,
                   skin: Float? = null, emg: Int? = null, emgMax: Int? = emg) =
        VitalsSample(
            timestamp = t0 + millis,
            heartRate = hr,
            spo2 = spo2,
            motionMagnitudeG = motion,
            skinTempC = skin,
            emgMean = emg,
            emgMax = emgMax
        )

    @Test
    fun `holds samples until the next bucket starts`() {
        val a = SampleAggregator()
        assertNull(a.add(at(0, hr = 70)))
        assertNull(a.add(at(500, hr = 72)))
        assertNull(a.add(at(4_999, hr = 74)))
        val done = a.add(at(5_000, hr = 90))!!
        assertEquals(t0, done.timestamp)
        assertEquals(72, done.heartRate)
    }

    /** An impact lasts one half-second line; averaging it away would hide a fall. */
    @Test
    fun `keeps the motion peak`() {
        val a = SampleAggregator()
        listOf(1.0f, 1.02f, 3.4f, 0.99f).forEachIndexed { i, g -> a.add(at(i * 500L, motion = g)) }
        assertEquals(3.4f, a.flush()!!.motionMagnitudeG!!, 0.0001f)
    }

    /** One motion-corrupted optical read must not drag the bucket. */
    @Test
    fun `uses the median for heart rate and oxygen`() {
        val a = SampleAggregator()
        listOf(72 to 97, 74 to 96, 180 to 70, 73 to 97).forEachIndexed { i, (hr, spo2) ->
            a.add(at(i * 500L, hr = hr, spo2 = spo2))
        }
        val done = a.flush()!!
        assertEquals(73, done.heartRate)
        assertEquals(96, done.spo2)
    }

    @Test
    fun `averages the EMG level and keeps its peak`() {
        val a = SampleAggregator()
        a.add(at(0, emg = 1000, emgMax = 1500))
        a.add(at(500, emg = 2000, emgMax = 3200))
        a.add(at(1_000, emg = 1500))
        val done = a.flush()!!
        assertEquals(1500, done.emgMean)
        assertEquals(3200, done.emgMax)
    }

    @Test
    fun `leaves absent channels absent`() {
        val a = SampleAggregator()
        a.add(at(0, hr = 70))
        a.add(at(500, hr = 71))
        val done = a.flush()!!
        assertNull(done.spo2)
        assertNull(done.skinTempC)
        assertNull(done.emgMean)
        assertNull(done.motionMagnitudeG)
    }

    /**
     * De-duplicating a replayed backlog depends on this: the same lines must always land
     * in the same bucket with the same timestamp, whenever they arrive.
     */
    @Test
    fun `buckets are aligned to absolute time`() {
        val first = SampleAggregator().apply { add(at(1_200, hr = 70)); add(at(3_700, hr = 71)) }.flush()!!
        val second = SampleAggregator().apply { add(at(3_700, hr = 71)) }.flush()!!
        assertEquals(first.timestamp, second.timestamp)
    }

    /** A backlog replay that restarts after a dropped link goes back in time. */
    @Test
    fun `closes the current bucket when the stream goes back in time`() {
        val a = SampleAggregator()
        a.add(at(20_000, hr = 90))
        val closed = a.add(at(0, hr = 70))!!
        assertEquals(t0 + 20_000, closed.timestamp)
        assertEquals(90, closed.heartRate)
        assertEquals(70, a.flush()!!.heartRate)
    }

    @Test
    fun `flush on an empty aggregator returns nothing`() {
        val a = SampleAggregator()
        assertNull(a.flush())
        a.add(at(0, hr = 70))
        a.flush()
        assertNull(a.flush())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a non-positive bucket`() {
        SampleAggregator(0)
    }
}
