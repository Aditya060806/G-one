package com.infinity.ai.health.domain

/**
 * The set of health events G-one can detect.
 *
 * Each constant maps to exactly one rule family in the detection engine and to
 * exactly one deterministic explanation template. Adding a type without adding
 * both is a compile-time-visible gap, because the template registry is exhaustive
 * over this enum via a `when` with no else branch.
 */
enum class AnomalyType(val wireName: String, val label: String) {
    LOW_SPO2             ("low_spo2",              "Low blood oxygen"),
    SUSTAINED_LOW_SPO2   ("sustained_low_spo2",    "Sustained low blood oxygen"),
    HIGH_HEART_RATE      ("high_heart_rate",       "Elevated heart rate"),
    LOW_HEART_RATE       ("low_heart_rate",        "Low heart rate"),
    FEVER                ("fever",                 "Raised body temperature"),
    HEAT_STRESS          ("heat_stress",           "Heat stress risk"),
    DEHYDRATION_RISK     ("dehydration_risk",      "Dehydration risk"),
    RESPIRATORY_DISTRESS ("respiratory_distress",  "Breathing difficulty signs"),
    CARDIOVASCULAR_STRAIN("cardiovascular_strain", "Cardiovascular strain"),
    FALL_DETECTED        ("fall_detected",         "Possible fall"),
    FATIGUE              ("fatigue",               "Fatigue indicators"),
    BASELINE_DEVIATION   ("baseline_deviation",    "Change from your normal");

    companion object {
        fun fromWireName(name: String): AnomalyType? =
            entries.firstOrNull { it.wireName == name }
    }
}

/**
 * Severity tiers. Three levels on purpose, matching the UI's colour discipline:
 * LOW is informational, MODERATE is amber, CRITICAL is red.
 *
 * Red is reserved exclusively for CRITICAL so that its appearance always carries
 * real weight instead of becoming visual noise the user learns to ignore.
 */
enum class Severity(val wireName: String, val rank: Int) {
    LOW     ("LOW",      1),
    MODERATE("MODERATE", 2),
    CRITICAL("CRITICAL", 3);

    fun atLeast(other: Severity): Boolean = rank >= other.rank

    companion object {
        fun fromWireName(name: String): Severity =
            entries.firstOrNull { it.wireName == name } ?: LOW

        fun highest(values: Collection<Severity>): Severity =
            values.maxByOrNull { it.rank } ?: LOW
    }
}

/**
 * 0–100 risk scores across the three axes PS26181 names explicitly.
 *
 * These are computed deterministically from the current window, independently of
 * whether any rule fired — so the dashboard can show a rising trend before
 * anything crosses a threshold, which is what makes early warning possible.
 */
data class RiskScores(
    val heat: Int,
    val respiratory: Int,
    val cardiovascular: Int
) {
    init {
        require(heat in 0..100)           { "heat risk out of range: $heat" }
        require(respiratory in 0..100)    { "respiratory risk out of range: $respiratory" }
        require(cardiovascular in 0..100) { "cardiovascular risk out of range: $cardiovascular" }
    }

    /** Worst single axis — what the dashboard headline number shows. */
    val overall: Int get() = maxOf(heat, respiratory, cardiovascular)

    companion object {
        val ZERO = RiskScores(0, 0, 0)
    }
}

/**
 * The structured, already-validated snapshot handed to the explanation layer.
 *
 * Every value here was computed by the deterministic engine. The LLM receives this
 * and may only rephrase it — it never decides [type], [severity], or any number.
 * That separation is the core safety property of the whole pipeline.
 */
data class AnomalyEvidence(
    val type: AnomalyType,
    val severity: Severity,
    val triggeredAt: Long,
    val ruleId: String,
    val riskScores: RiskScores,
    val heartRate: Int? = null,
    val spo2: Int? = null,
    val bodyTempC: Float? = null,
    val ambientTempC: Float? = null,
    val ambientHumidityPct: Float? = null,
    val aqi: Int? = null,
    val durationMinutes: Int? = null,
    val trend: Trend? = null,
    val baselineDeltaPct: Float? = null,
    val motionDetected: Boolean? = null,
    val sampleCount: Int = 0
) {
    /**
     * Serialise to JSON for the LLM prompt and for storage.
     *
     * Hand-built rather than pulling in a serialization library: the schema is
     * small and fixed, this package must stay Android- and dependency-free, and
     * escaping is covered by unit tests. Nulls are omitted so the model is never
     * shown `"spo2": null` and tempted to reason about a reading that isn't there.
     */
    fun toJson(): String {
        val f = StringBuilder("{")
        val parts = mutableListOf<String>()
        parts += "\"event\":${jsonString(type.wireName)}"
        parts += "\"severity\":${jsonString(severity.wireName)}"
        parts += "\"rule\":${jsonString(ruleId)}"
        parts += "\"triggered_at\":$triggeredAt"
        heartRate?.let          { parts += "\"heart_rate\":$it" }
        spo2?.let               { parts += "\"spo2\":$it" }
        bodyTempC?.let          { parts += "\"body_temp_c\":${jsonFloat(it)}" }
        durationMinutes?.let    { parts += "\"duration_minutes\":$it" }
        trend?.let              { parts += "\"trend\":${jsonString(it.name.lowercase())}" }
        baselineDeltaPct?.let   { parts += "\"baseline_delta_pct\":${jsonFloat(it)}" }
        motionDetected?.let     { parts += "\"motion\":$it" }
        ambientTempC?.let       { parts += "\"ambient_temp_c\":${jsonFloat(it)}" }
        ambientHumidityPct?.let { parts += "\"ambient_humidity_pct\":${jsonFloat(it)}" }
        aqi?.let                { parts += "\"aqi\":$it" }
        parts += "\"risk\":{" +
            "\"heat\":${riskScores.heat}," +
            "\"respiratory\":${riskScores.respiratory}," +
            "\"cardiovascular\":${riskScores.cardiovascular}}"
        f.append(parts.joinToString(","))
        f.append("}")
        return f.toString()
    }
}

/** A confirmed detection, ready to be persisted and explained. */
data class AnomalyCandidate(
    val type: AnomalyType,
    val severity: Severity,
    val evidence: AnomalyEvidence
) {
    val riskScores: RiskScores get() = evidence.riskScores
}

// ── JSON primitives ───────────────────────────────────────────────────────────

/** RFC 8259 compliant string escaping, including control characters. */
internal fun jsonString(value: String): String {
    val sb = StringBuilder(value.length + 2)
    sb.append('"')
    for (c in value) {
        when (c) {
            '"'      -> sb.append("\\\"")
            '\\'     -> sb.append("\\\\")
            '\n'     -> sb.append("\\n")
            '\r'     -> sb.append("\\r")
            '\t'     -> sb.append("\\t")
            '\b'     -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else ->
                if (c < ' ') sb.append("\\u%04x".format(c.code))
                else sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}

/**
 * Emit a float with at most one decimal place and always a valid JSON number.
 *
 * NaN and infinities are not representable in JSON, so they collapse to 0 rather
 * than producing a payload that fails to parse downstream.
 */
internal fun jsonFloat(value: Float): String {
    if (value.isNaN() || value.isInfinite()) return "0"
    val rounded = Math.round(value * 10.0) / 10.0
    return if (rounded == Math.floor(rounded)) rounded.toInt().toString()
    else rounded.toString()
}
