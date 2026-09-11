package com.infinity.ai.health.source

import com.infinity.ai.health.domain.VitalsSample
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Physiological storylines the simulator can play.
 *
 * Each one is shaped to drive a specific rule family in the detection engine, so the
 * engine can be validated end-to-end with zero hardware — and so a demo can show a
 * real heat-stress or desaturation alert on command instead of waiting for one.
 */
enum class VitalsScenario(val displayName: String, val nominalMinutes: Int) {
    /** Stable, in-range. Nothing should ever fire. The false-positive control. */
    HEALTHY_BASELINE("Healthy baseline", 30),

    /** Ambient 34→46 °C with rising body temp and HR. Heat stress + dehydration. */
    HEAT_WAVE_EXPOSURE("Heat wave exposure", 60),

    /** SpO2 97→88 and held. Sustained-low-SpO2 then critical + respiratory. */
    DESATURATION_EPISODE("Oxygen desaturation", 30),

    /** Resting HR spikes to ~150. Tachycardia at rest, so not a motion artifact. */
    TACHYCARDIA_EPISODE("Tachycardia episode", 20),

    /** Resting HR falls to ~42. Bradycardia. */
    BRADYCARDIA_EPISODE("Bradycardia episode", 20),

    /** Body temp 36.8→39.7 °C with the HR rise that accompanies fever. */
    FEVER_ONSET("Fever onset", 90),

    /** AQI 90→340 with a mild SpO2 dip. Pollution-event respiratory risk. */
    AIR_QUALITY_EVENT("Air quality event", 45),

    /** Impact spike then immobility — the two-part signature of a real fall. */
    FALL_THEN_IMMOBILE("Fall then immobile", 15),

    /**
     * The early-warning case. Resting HR drifts ~25% above the patient's own
     * baseline and SpO2 slides a few points, with nothing ever crossing a fixed
     * threshold. Only the baseline-deviation rule catches this.
     */
    GRADUAL_DETERIORATION("Gradual deterioration", 240)
}

/**
 * Deterministic sample generator.
 *
 * PURE AND INDEX-ADDRESSED, WHICH IS WHAT MAKES IT TESTABLE.
 *
 * [sampleAt] is a pure function of the index — no internal cursor, no shared Random.
 * Calling `sampleAt(500)` first returns exactly what it would return after 500
 * sequential calls. Tests can therefore jump straight to minute 12 of a desaturation
 * episode instead of pumping a flow, and a failing test reproduces byte-for-byte
 * from the seed alone.
 *
 * Jitter is derived by hashing (seed, index, field) rather than drawing from a
 * sequential PRNG, which is precisely what preserves that property.
 */
class VitalsScenarioGenerator(
    val scenario: VitalsScenario,
    val startAt: Long,
    val intervalMillis: Long = 1_000L,
    val seed: Long = 42L
) {
    init {
        require(intervalMillis > 0) { "intervalMillis must be positive" }
    }

    /** Samples per minute at the configured interval. */
    val samplesPerMinute: Double = 60_000.0 / intervalMillis

    /** Total samples for one full pass of the scenario's nominal duration. */
    val nominalSampleCount: Int = (scenario.nominalMinutes * samplesPerMinute).roundToInt()

    fun sampleAt(index: Int): VitalsSample {
        require(index >= 0) { "index must be non-negative" }
        val t = startAt + index * intervalMillis
        val min = index / samplesPerMinute
        return when (scenario) {
            VitalsScenario.HEALTHY_BASELINE     -> healthy(index, t)
            VitalsScenario.HEAT_WAVE_EXPOSURE   -> heatWave(index, t, min)
            VitalsScenario.DESATURATION_EPISODE -> desaturation(index, t, min)
            VitalsScenario.TACHYCARDIA_EPISODE  -> tachycardia(index, t, min)
            VitalsScenario.BRADYCARDIA_EPISODE  -> bradycardia(index, t, min)
            VitalsScenario.FEVER_ONSET          -> fever(index, t, min)
            VitalsScenario.AIR_QUALITY_EVENT    -> airQuality(index, t, min)
            VitalsScenario.FALL_THEN_IMMOBILE   -> fall(index, t, min)
            VitalsScenario.GRADUAL_DETERIORATION-> deterioration(index, t, min)
        }
    }

    /** Convenience for tests and warm-up: the first [count] samples. */
    fun take(count: Int): List<VitalsSample> = (0 until count).map { sampleAt(it) }

    // ── Scenario shapes ───────────────────────────────────────────────────────

    private fun healthy(i: Int, t: Long) = VitalsSample(
        timestamp          = t,
        heartRate          = (72 + noise(i, 1) * 4f).roundToInt(),
        spo2               = (97 + noise(i, 2) * 1.2f).roundToInt().coerceIn(95, 100),
        bodyTempC          = 36.7f + noise(i, 3) * 0.15f,
        motionMagnitudeG   = 1.00f + abs(noise(i, 4)) * 0.08f,
        ambientTempC       = 28f + noise(i, 5) * 1.5f,
        ambientHumidityPct = 55f + noise(i, 6) * 4f,
        aqi                = (80 + noise(i, 7) * 12f).roundToInt().coerceAtLeast(0)
    )

    private fun heatWave(i: Int, t: Long, min: Double): VitalsSample {
        val p = progress(min, 0.0, 55.0)
        return VitalsSample(
            timestamp          = t,
            heartRate          = (ramp(78f, 128f, p) + noise(i, 1) * 3f).roundToInt(),
            spo2               = (96 + noise(i, 2) * 1f).roundToInt().coerceIn(93, 100),
            bodyTempC          = ramp(36.8f, 38.6f, p) + noise(i, 3) * 0.08f,
            motionMagnitudeG   = 1.05f + abs(noise(i, 4)) * 0.12f,
            ambientTempC       = ramp(34f, 46f, p) + noise(i, 5) * 0.4f,
            ambientHumidityPct = ramp(58f, 76f, p) + noise(i, 6) * 2f,
            aqi                = (120 + noise(i, 7) * 15f).roundToInt().coerceAtLeast(0)
        )
    }

    private fun desaturation(i: Int, t: Long, min: Double): VitalsSample {
        // Flat for 5 min, decline to 88 by minute 15, then hold — so the sustained
        // rule fires before the critical one, exercising both in a single run.
        val spo2 = when {
            min < 5.0  -> 97f
            min < 15.0 -> ramp(97f, 88f, progress(min, 5.0, 15.0))
            else       -> 88f
        }
        val hr = if (min < 5.0) 76f else ramp(76f, 114f, progress(min, 5.0, 18.0))
        return VitalsSample(
            timestamp          = t,
            heartRate          = (hr + noise(i, 1) * 2.5f).roundToInt(),
            spo2               = (spo2 + noise(i, 2) * 0.6f).roundToInt().coerceIn(80, 100),
            bodyTempC          = 37.0f + noise(i, 3) * 0.1f,
            motionMagnitudeG   = 1.00f + abs(noise(i, 4)) * 0.05f,   // at rest
            ambientTempC       = 27f + noise(i, 5) * 1f,
            ambientHumidityPct = 52f + noise(i, 6) * 3f,
            aqi                = (95 + noise(i, 7) * 10f).roundToInt().coerceAtLeast(0)
        )
    }

    private fun tachycardia(i: Int, t: Long, min: Double): VitalsSample {
        val hr = if (min < 3.0) 74f else 150f
        return VitalsSample(
            timestamp          = t,
            heartRate          = (hr + noise(i, 1) * 5f).roundToInt(),
            spo2               = (96 + noise(i, 2) * 1f).roundToInt().coerceIn(92, 100),
            bodyTempC          = 36.9f + noise(i, 3) * 0.1f,
            motionMagnitudeG   = 1.00f + abs(noise(i, 4)) * 0.04f,   // at rest: real, not exertion
            ambientTempC       = 26f + noise(i, 5) * 1f,
            ambientHumidityPct = 50f + noise(i, 6) * 3f,
            aqi                = (75 + noise(i, 7) * 10f).roundToInt().coerceAtLeast(0)
        )
    }

    private fun bradycardia(i: Int, t: Long, min: Double): VitalsSample {
        val hr = if (min < 3.0) 70f else ramp(70f, 42f, progress(min, 3.0, 8.0))
        return VitalsSample(
            timestamp          = t,
            heartRate          = (hr + noise(i, 1) * 1.8f).roundToInt(),
            spo2               = (95 + noise(i, 2) * 1f).roundToInt().coerceIn(92, 100),
            bodyTempC          = 36.6f + noise(i, 3) * 0.1f,
            motionMagnitudeG   = 1.00f + abs(noise(i, 4)) * 0.03f,
            ambientTempC       = 24f + noise(i, 5) * 1f,
            ambientHumidityPct = 48f + noise(i, 6) * 3f,
            aqi                = (70 + noise(i, 7) * 10f).roundToInt().coerceAtLeast(0)
        )
    }

    private fun fever(i: Int, t: Long, min: Double): VitalsSample {
        val p = progress(min, 0.0, 85.0)
        return VitalsSample(
            timestamp          = t,
            heartRate          = (ramp(74f, 104f, p) + noise(i, 1) * 3f).roundToInt(),
            spo2               = (96 + noise(i, 2) * 1f).roundToInt().coerceIn(93, 100),
            bodyTempC          = ramp(36.8f, 39.7f, p) + noise(i, 3) * 0.07f,
            motionMagnitudeG   = 1.00f + abs(noise(i, 4)) * 0.06f,
            ambientTempC       = 29f + noise(i, 5) * 1f,
            ambientHumidityPct = 60f + noise(i, 6) * 3f,
            aqi                = (85 + noise(i, 7) * 10f).roundToInt().coerceAtLeast(0)
        )
    }

    private fun airQuality(i: Int, t: Long, min: Double): VitalsSample {
        val p = progress(min, 0.0, 40.0)
        return VitalsSample(
            timestamp          = t,
            heartRate          = (ramp(76f, 98f, p) + noise(i, 1) * 3f).roundToInt(),
            spo2               = (ramp(97f, 93f, p) + noise(i, 2) * 0.7f).roundToInt().coerceIn(88, 100),
            bodyTempC          = 36.9f + noise(i, 3) * 0.1f,
            motionMagnitudeG   = 1.02f + abs(noise(i, 4)) * 0.08f,
            ambientTempC       = 32f + noise(i, 5) * 1.5f,
            ambientHumidityPct = 45f + noise(i, 6) * 4f,
            aqi                = (ramp(90f, 340f, p) + noise(i, 7) * 12f).roundToInt().coerceAtLeast(0)
        )
    }

    private fun fall(i: Int, t: Long, min: Double): VitalsSample {
        val impactIndex = (5.0 * samplesPerMinute).roundToInt()
        val motion = when {
            i == impactIndex -> 3.4f                                  // the impact
            i < impactIndex  -> 1.05f + abs(noise(i, 4)) * 0.15f      // walking
            else             -> 0.99f + abs(noise(i, 4)) * 0.02f      // immobile after
        }
        val hr = when {
            i < impactIndex               -> 84f
            i < impactIndex + samplesPerMinute * 2 -> 104f            // startle response
            else                          -> 88f
        }
        return VitalsSample(
            timestamp          = t,
            heartRate          = (hr + noise(i, 1) * 3f).roundToInt(),
            spo2               = (96 + noise(i, 2) * 1f).roundToInt().coerceIn(92, 100),
            bodyTempC          = 36.8f + noise(i, 3) * 0.1f,
            motionMagnitudeG   = motion,
            ambientTempC       = 27f + noise(i, 5) * 1f,
            ambientHumidityPct = 52f + noise(i, 6) * 3f,
            aqi                = (80 + noise(i, 7) * 10f).roundToInt().coerceAtLeast(0)
        )
    }

    private fun deterioration(i: Int, t: Long, min: Double): VitalsSample {
        val p = progress(min, 0.0, 230.0)
        return VitalsSample(
            timestamp          = t,
            // 68 -> 86 bpm is ~26% over baseline while never reaching hrHighBpm (130).
            heartRate          = (ramp(68f, 86f, p) + noise(i, 1) * 1.5f).roundToInt(),
            // 97 -> 93 stays above spo2SustainedBelow (92), so only the baseline
            // rule can see it. This is the whole point of the scenario.
            spo2               = (ramp(97f, 93f, p) + noise(i, 2) * 0.5f).roundToInt().coerceIn(90, 100),
            bodyTempC          = ramp(36.8f, 37.3f, p) + noise(i, 3) * 0.08f,
            motionMagnitudeG   = 1.00f + abs(noise(i, 4)) * 0.05f,   // at rest throughout
            ambientTempC       = 28f + noise(i, 5) * 1.5f,
            ambientHumidityPct = 55f + noise(i, 6) * 3f,
            aqi                = (90 + noise(i, 7) * 10f).roundToInt().coerceAtLeast(0)
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun ramp(from: Float, to: Float, p: Float) = from + (to - from) * p

    /** Fraction through [startMin]..[endMin], clamped to 0..1. */
    private fun progress(min: Double, startMin: Double, endMin: Double): Float {
        if (endMin <= startMin) return 1f
        return ((min - startMin) / (endMin - startMin)).coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * Deterministic pseudo-noise in [-1, 1] from (seed, index, field).
     *
     * Hash-based rather than a sequential PRNG so [sampleAt] stays pure — a shared
     * Random would make the value at an index depend on how many times it had been
     * called before, and the whole reproducibility guarantee would evaporate.
     */
    private fun noise(index: Int, field: Int): Float {
        var h = (seed.toInt() * 0x9E3779B1.toInt()) xor (index * 0x85EBCA6B.toInt())
        h = h xor (field * 0xC2B2AE35.toInt())
        h = h xor (h ushr 16)
        h *= 0x7FEB352D
        h = h xor (h ushr 15)
        h *= 0x846CA68B.toInt()
        h = h xor (h ushr 16)
        return ((h and 0xFFFF) / 32767.5f) - 1f
    }
}
