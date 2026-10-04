package com.gone.ai.ml

import kotlin.math.sqrt

/**
 * Turns a stream of movement into what the fall model reads.
 *
 * PURE KOTLIN, and a line-for-line twin of `ml/common/motion.py`, which is the specification.
 * `FallWindowTest` runs both over the same real recordings (`fall_golden.txt`) and requires the
 * same impacts and the same model inputs, so the model is never shown something in the app that
 * it was not shown in training.
 *
 * Everything is in g, at [RATE_HZ] samples a second.
 */
object FallWindow {

    const val RATE_HZ = 25
    /** 4 s. */
    const val WINDOW = 100
    /** 1 s before the impact; the other 3 s follow it. */
    const val PRE = 25
    /** Per axis. The wrist recordings the model learned from were made on a ±4 g sensor. */
    const val CLIP_G = 4f
    /** Below this nothing is scored at all. */
    const val TRIGGER_G = 2f
    /** An impact is the largest sample within about half a second each side. */
    const val PEAK_HALF_WIDTH = 12
    /** The largest magnitude a ±4 g sensor can report is 4·√3 = 6.93. */
    const val MAG_SCALE = 7f
    /** Values per sample the model reads: x, y, z and the magnitude. */
    const val CHANNELS = 4

    fun magnitude(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)

    /**
     * Sample numbers of impacts worth scoring: at least [trigger], larger than every sample in the
     * half second before it and no smaller than any in the half second after.
     */
    fun candidates(mag: FloatArray, trigger: Float = TRIGGER_G): List<Int> {
        val found = ArrayList<Int>()
        for (i in mag.indices) if (isImpact(mag, i, mag.size, trigger)) found += i
        return found
    }

    /**
     * Whether sample [i] of the first [count] values of [mag] is an impact. Samples past either
     * end are simply not there, as in the specification.
     */
    fun isImpact(mag: FloatArray, i: Int, count: Int = mag.size, trigger: Float = TRIGGER_G): Boolean {
        val m = mag[i]
        if (m < trigger) return false
        for (j in maxOf(0, i - PEAK_HALF_WIDTH) until i) if (mag[j] >= m) return false
        for (j in i + 1..minOf(count - 1, i + PEAK_HALF_WIDTH)) if (mag[j] > m) return false
        return true
    }

    /**
     * The model's input for the impact at sample [impact]: [WINDOW] rows of x, y, z over [CLIP_G]
     * and the magnitude over [MAG_SCALE], row after row. Past either end of the stream the
     * nearest sample is repeated.
     */
    fun features(x: FloatArray, y: FloatArray, z: FloatArray, impact: Int): FloatArray {
        require(x.size == y.size && y.size == z.size && x.isNotEmpty()) { "x, y and z must be the same, non-zero length" }
        val out = FloatArray(WINDOW * CHANNELS)
        val last = x.size - 1
        for (k in 0 until WINDOW) {
            val i = (impact - PRE + k).coerceIn(0, last)
            val cx = x[i].coerceIn(-CLIP_G, CLIP_G)
            val cy = y[i].coerceIn(-CLIP_G, CLIP_G)
            val cz = z[i].coerceIn(-CLIP_G, CLIP_G)
            val base = k * CHANNELS
            out[base] = cx / CLIP_G
            out[base + 1] = cy / CLIP_G
            out[base + 2] = cz / CLIP_G
            out[base + 3] = magnitude(cx, cy, cz) / MAG_SCALE
        }
        return out
    }
}

/**
 * Finds impacts in movement as it arrives, for a sensor read live.
 *
 * Samples come in at whatever rate and rhythm the sensor has. They are averaged onto the 25 Hz
 * grid the model was trained on, and each impact is reported once the three seconds after it
 * have been seen, with the model's input ready. Not thread-safe: feed it from one thread.
 *
 * @param onImpact the model input ([FallWindow.features]) and the impact's size in g.
 */
class FallStream(private val onImpact: (features: FloatArray, peakG: Float) -> Unit) {

    private val x = FloatArray(SIZE)
    private val y = FloatArray(SIZE)
    private val z = FloatArray(SIZE)
    private val mag = FloatArray(SIZE)
    /** Samples on the 25 Hz grid so far; the newest is at `count - 1`. */
    private var count = 0L

    private var startNanos = Long.MIN_VALUE
    private var bin = 0L
    private var sumX = 0f
    private var sumY = 0f
    private var sumZ = 0f
    private var inBin = 0

    /** One reading, in g. [timeNanos] must not go backwards. */
    fun add(timeNanos: Long, gx: Float, gy: Float, gz: Float) {
        if (!(gx.isFinite() && gy.isFinite() && gz.isFinite())) return
        if (startNanos == Long.MIN_VALUE) startNanos = timeNanos
        val at = ((timeNanos - startNanos).coerceAtLeast(0) * FallWindow.RATE_HZ) / 1_000_000_000L
        if (at != bin) {
            if (inBin > 0) {
                val mx = sumX / inBin
                val my = sumY / inBin
                val mz = sumZ / inBin
                // A bin with no reading repeats the one before, so a sensor that stalls does not
                // squeeze time; a long stall is not filled in.
                val steps = (at - bin).coerceIn(1, MAX_FILL.toLong())
                repeat(steps.toInt()) { push(mx, my, mz) }
            }
            bin = at
            sumX = 0f; sumY = 0f; sumZ = 0f; inBin = 0
        }
        sumX += gx; sumY += gy; sumZ += gz; inBin++
    }

    fun reset() {
        count = 0
        startNanos = Long.MIN_VALUE
        bin = 0
        sumX = 0f; sumY = 0f; sumZ = 0f; inBin = 0
    }

    private fun push(px: Float, py: Float, pz: Float) {
        val slot = (count % SIZE).toInt()
        val cx = px.coerceIn(-FallWindow.CLIP_G, FallWindow.CLIP_G)
        val cy = py.coerceIn(-FallWindow.CLIP_G, FallWindow.CLIP_G)
        val cz = pz.coerceIn(-FallWindow.CLIP_G, FallWindow.CLIP_G)
        x[slot] = cx; y[slot] = cy; z[slot] = cz
        mag[slot] = FallWindow.magnitude(cx, cy, cz)
        count++

        // The sample whose window has just been completed by this one.
        if (count < FallWindow.WINDOW) return
        val first = count - FallWindow.WINDOW
        val wx = FloatArray(FallWindow.WINDOW)
        val wy = FloatArray(FallWindow.WINDOW)
        val wz = FloatArray(FallWindow.WINDOW)
        val wm = FloatArray(FallWindow.WINDOW)
        for (k in 0 until FallWindow.WINDOW) {
            val from = ((first + k) % SIZE).toInt()
            wx[k] = x[from]; wy[k] = y[from]; wz[k] = z[from]; wm[k] = mag[from]
        }
        if (FallWindow.isImpact(wm, FallWindow.PRE)) {
            onImpact(FallWindow.features(wx, wy, wz, FallWindow.PRE), wm[FallWindow.PRE])
        }
    }

    private companion object {
        const val SIZE = 128
        /** Half a second: longer gaps are a stalled sensor, not movement to invent. */
        const val MAX_FILL = 12
    }
}
