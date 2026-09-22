package com.gone.ai.health.source

import com.gone.ai.health.domain.VitalsSample
import kotlin.math.roundToInt

/**
 * Folds a fast wearable stream into one sample per fixed time bucket.
 *
 * WHY THE STREAM IS NOT STORED AS IT ARRIVES
 *
 * The wearable sends two lines a second. Stored raw that is 172,800 rows a day, every
 * one of them run through the detection engine, against rules whose shortest window is
 * minutes long. So the live trace on screen gets the raw stream and the pipeline gets
 * one sample per [bucketMillis] — the same 5-second cadence the simulator uses, so the
 * rules see identical timing from either source.
 *
 * HOW EACH CHANNEL IS COMBINED
 *
 *  - heart rate, SpO₂, AQI: median. One optical read corrupted by movement must not
 *    drag a bucket, and a median cannot invent a value that was never measured.
 *  - core, skin and ambient temperature, humidity: mean. These move slowly.
 *  - motion: MAXIMUM. The fall rule looks for an impact peak; averaging would erase it.
 *  - EMG: mean of the means for the envelope level, maximum of the peaks.
 *
 * Buckets are aligned to absolute time (`timestamp - timestamp % bucketMillis`) and the
 * combined sample is stamped with the bucket's start. That makes the output a pure
 * function of the input lines, which is what lets a replayed SD-card backlog be
 * de-duplicated by timestamp: the same lines always produce the same buckets.
 *
 * Not thread-safe. One instance per stream, fed from one coroutine.
 */
class SampleAggregator(val bucketMillis: Long = DEFAULT_BUCKET_MILLIS) {

    init {
        require(bucketMillis > 0) { "bucketMillis must be positive" }
    }

    private val pending = mutableListOf<VitalsSample>()
    private var bucketStart: Long? = null

    /**
     * Add one sample.
     *
     * @return the previous bucket, combined, when [sample] belongs to a different one;
     *   null while the current bucket is still filling.
     *
     * A sample from an EARLIER bucket than the current one means the stream went back in
     * time — in practice a backlog replay that restarted after the link dropped. The
     * current bucket is closed rather than polluted with older readings.
     */
    fun add(sample: VitalsSample): VitalsSample? {
        val start = bucketOf(sample.timestamp)
        val current = bucketStart
        if (current == null || start == current) {
            bucketStart = start
            pending += sample
            return null
        }
        val done = combine(current, pending)
        pending.clear()
        pending += sample
        bucketStart = start
        return done
    }

    /** Close the bucket being filled, e.g. when the stream ends. Null when empty. */
    fun flush(): VitalsSample? {
        val current = bucketStart ?: return null
        val done = combine(current, pending)
        pending.clear()
        bucketStart = null
        return done
    }

    private fun bucketOf(timestamp: Long): Long = Math.floorDiv(timestamp, bucketMillis) * bucketMillis

    companion object {
        /** Matches the simulator and the retention and chart sizing built around it. */
        const val DEFAULT_BUCKET_MILLIS = 5_000L

        internal fun combine(bucketStart: Long, samples: List<VitalsSample>): VitalsSample =
            VitalsSample(
                timestamp          = bucketStart,
                heartRate          = medianInt(samples.mapNotNull { it.heartRate }),
                spo2               = medianInt(samples.mapNotNull { it.spo2 }),
                bodyTempC          = mean(samples.mapNotNull { it.bodyTempC }),
                motionMagnitudeG   = samples.mapNotNull { it.motionMagnitudeG }.maxOrNull(),
                ambientTempC       = mean(samples.mapNotNull { it.ambientTempC }),
                ambientHumidityPct = mean(samples.mapNotNull { it.ambientHumidityPct }),
                aqi                = medianInt(samples.mapNotNull { it.aqi }),
                skinTempC          = mean(samples.mapNotNull { it.skinTempC }),
                emgMean            = mean(samples.mapNotNull { it.emgMean?.toFloat() })?.roundToInt(),
                emgMax             = samples.mapNotNull { it.emgMax ?: it.emgMean }.maxOrNull()
            )

        /** Lower median for an even count, so the result is always a measured value. */
        private fun medianInt(values: List<Int>): Int? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            return sorted[(sorted.size - 1) / 2]
        }

        private fun mean(values: List<Float>): Float? =
            if (values.isEmpty()) null else values.sum() / values.size
    }
}
