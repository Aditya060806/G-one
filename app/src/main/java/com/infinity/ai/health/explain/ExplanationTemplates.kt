package com.infinity.ai.health.explain

import com.infinity.ai.health.domain.AnomalyEvidence
import com.infinity.ai.health.domain.AnomalyType
import com.infinity.ai.health.domain.Trend

/**
 * Deterministic, human-readable explanations for every anomaly type.
 *
 * THIS IS THE PRIMARY EXPLANATION PATH, NOT A FALLBACK.
 *
 * Every event gets its explanation from here first, before the model is ever asked.
 * The alert is therefore complete and understandable the moment it is persisted. If
 * inference is slow, stalls, times out, or the process is killed mid-generation, the
 * user has already been warned properly. The LLM is an *upgrade* that improves
 * phrasing afterwards — it is never on the critical path to informing someone that
 * their oxygen is falling.
 *
 * The `when` in [render] is exhaustive over [AnomalyType] with NO else branch, so
 * adding a new anomaly type without writing its explanation is a compile error rather
 * than a silent blank alert.
 *
 * Voice: calm, concrete, and aimed at a family member with no medical training.
 * Hedged throughout ("can be", "may") because a threshold crossing is an observation,
 * not a diagnosis. No condition is ever named.
 */
object ExplanationTemplates {

    fun render(e: AnomalyEvidence): Explanation {
        val tier = ResponseTier.forSeverity(e.severity)
        return when (e.type) {

            AnomalyType.LOW_SPO2 -> Explanation(
                headline = "Blood oxygen is low",
                detail = buildString {
                    append("The oxygen level in the blood was measured at ${spo2Text(e)}")
                    append(hrClause(e))
                    append(". Readings this low mean the body may not be getting enough ")
                    append("oxygen, which needs attention quickly.")
                },
                tier = tier, ruleId = e.ruleId
            )

            AnomalyType.SUSTAINED_LOW_SPO2 -> Explanation(
                headline = "Blood oxygen has stayed low",
                detail = buildString {
                    append("The oxygen level has been below normal — currently ${spo2Text(e)} — ")
                    append("for about ${e.durationMinutes ?: 0} minutes")
                    append(trendClause(e))
                    append(". A brief dip is often just the sensor moving, but a reading ")
                    append("that stays low for this long usually reflects something real.")
                },
                tier = tier, ruleId = e.ruleId
            )

            AnomalyType.HIGH_HEART_RATE -> Explanation(
                headline = "Heart rate is high",
                detail = buildString {
                    append("The heart is beating ${hrText(e)}")
                    append(if (e.motionDetected == false) " while resting" else "")
                    append(durationClause(e))
                    append(". A fast heartbeat at rest can happen with fever, ")
                    append("dehydration, stress or heat, and sometimes with heart problems.")
                },
                tier = tier, ruleId = e.ruleId
            )

            AnomalyType.LOW_HEART_RATE -> Explanation(
                headline = "Heart rate is low",
                detail = buildString {
                    append("The heart is beating ${hrText(e)}, which is ")
                    append("slower than usual")
                    append(durationClause(e))
                    append(". For some people this is normal, especially if they are very ")
                    append("fit. For others it can mean the heart is not pumping enough ")
                    append("blood, which may cause dizziness or fainting.")
                },
                tier = tier, ruleId = e.ruleId
            )

            AnomalyType.FEVER -> Explanation(
                headline = "Body temperature is raised",
                detail = buildString {
                    append("Body temperature is ${temp(e)} °C")
                    append(durationClause(e))
                    append(". A raised temperature is usually the body responding to an ")
                    append("infection, and can also happen after long exposure to heat.")
                },
                tier = tier, ruleId = e.ruleId
            )

            AnomalyType.HEAT_STRESS -> Explanation(
                headline = "Signs of heat stress",
                detail = buildString {
                    append("It currently feels like around ${feelsLike(e)} °C outside")
                    append(humidityClause(e))
                    append(", and the body is showing signs of struggling with it")
                    append(bodyResponseClause(e))
                    append(". In this kind of heat the body can overheat faster than ")
                    append("people expect, especially while working or walking outdoors.")
                },
                tier = tier, ruleId = e.ruleId
            )

            AnomalyType.DEHYDRATION_RISK -> Explanation(
                headline = "Possible dehydration",
                detail = buildString {
                    append("In the current heat, the heart rate has been climbing while ")
                    append("body temperature also rises")
                    append(hrClause(e))
                    append(". This pattern often appears when the body is short of ")
                    append("fluids. Note that G-one cannot measure hydration directly — ")
                    append("this is an inference from the other readings, not a measurement.")
                },
                tier = tier, ruleId = e.ruleId
            )

            AnomalyType.RESPIRATORY_DISTRESS -> Explanation(
                headline = "Signs of breathing difficulty",
                detail = buildString {
                    append("Oxygen level is ${spo2Text(e)}")
                    append(aqiClause(e))
                    append(trendClause(e))
                    append(". Together these suggest the lungs may be working harder than ")
                    append("usual. This is common during dust, smoke or pollution events, ")
                    append("and matters more for anyone with asthma or a lung condition.")
                },
                tier = tier, ruleId = e.ruleId
            )

            AnomalyType.CARDIOVASCULAR_STRAIN -> Explanation(
                headline = "Heart is working harder",
                detail = buildString {
                    append("While at rest, the heart rate has been rising (now ")
                    append("${hrText(e)}) at the same time as the oxygen level ")
                    append("has been falling")
                    append(spo2Clause(e))
                    append(". Each of these alone can be harmless. Happening together at ")
                    append("rest, they suggest the heart is compensating for something and ")
                    append("should be looked at.")
                },
                tier = tier, ruleId = e.ruleId
            )

            AnomalyType.FALL_DETECTED -> Explanation(
                headline = "A fall may have happened",
                detail = buildString {
                    append("A sudden hard movement was detected, of the kind that happens ")
                    append("in a fall")
                    if ((e.durationMinutes ?: 0) > 0) {
                        append(", and there has been almost no movement since — about ")
                        append("${e.durationMinutes} minutes of stillness")
                    }
                    append(". Someone should check on the person straight away. If this ")
                    append("was not a fall, the alert can simply be dismissed.")
                },
                tier = tier, ruleId = e.ruleId
            )

            AnomalyType.FATIGUE -> Explanation(
                headline = "Resting heart rate is elevated",
                detail = buildString {
                    append("The resting heart rate has stayed around ${hrText(e)} ")
                    append("for about ${e.durationMinutes ?: 0} minutes without ")
                    append("much movement. This often just means tiredness, poor sleep or ")
                    append("not enough water. It is worth noticing rather than worrying about.")
                },
                tier = tier, ruleId = e.ruleId
            )

            AnomalyType.BASELINE_DEVIATION -> Explanation(
                headline = "Different from your normal",
                detail = buildString {
                    append("These readings are still inside the usual healthy range, but ")
                    append("they have drifted away from what is normal *for this person*")
                    append(baselineClause(e))
                    append(". Changes like this sometimes appear a day or two before ")
                    append("someone starts feeling unwell, which is why it is worth ")
                    append("flagging early rather than waiting.")
                },
                tier = tier, ruleId = e.ruleId
            )
        }
    }

    // ── Inline value formatters ───────────────────────────────────────────────
    // These render a measurement that a sentence is BUILT AROUND, so they cannot
    // simply vanish — they degrade to a neutral phrase instead.
    //
    // In practice a rule only fires when its own signal is present, so these
    // fallbacks are defensive. But "the rule guarantees it" is not the same as the
    // template guaranteeing it: string interpolation of a null Int renders the literal
    // text "null", and an alert reading "measured at null%" would destroy a user's
    // trust in the whole app instantly. Cheap insurance against an expensive failure.

    private fun spo2Text(e: AnomalyEvidence): String =
        e.spo2?.let { "$it%" } ?: "a reduced level"

    private fun hrText(e: AnomalyEvidence): String =
        e.heartRate?.let { "$it times a minute" } ?: "an unusual rate"

    // ── Optional clause helpers ───────────────────────────────────────────────
    // These are additive detail, so they return "" when the value is absent and the
    // surrounding sentence still reads correctly without them.

    private fun hrClause(e: AnomalyEvidence): String =
        e.heartRate?.let { ", with a heart rate of $it a minute" } ?: ""

    private fun spo2Clause(e: AnomalyEvidence): String =
        e.spo2?.let { " (now $it%)" } ?: ""

    private fun durationClause(e: AnomalyEvidence): String {
        val m = e.durationMinutes ?: return ""
        if (m <= 0) return ""
        return ", and has been for about $m minutes"
    }

    private fun trendClause(e: AnomalyEvidence): String = when (e.trend) {
        Trend.FALLING -> ", and it is still going down"
        Trend.RISING  -> ", and it is still going up"
        Trend.STABLE, null -> ""
    }

    private fun humidityClause(e: AnomalyEvidence): String =
        e.ambientHumidityPct?.let { ", with humidity at ${it.toInt()}%" } ?: ""

    private fun aqiClause(e: AnomalyEvidence): String =
        e.aqi?.let { ", and the air quality index outside is $it" } ?: ""

    private fun bodyResponseClause(e: AnomalyEvidence): String {
        val parts = mutableListOf<String>()
        e.bodyTempC?.let { parts += "body temperature ${temp(e)} °C" }
        e.heartRate?.let { parts += "heart rate $it a minute" }
        if (parts.isEmpty()) return ""
        return " (${parts.joinToString(", ")})"
    }

    private fun baselineClause(e: AnomalyEvidence): String {
        val d = e.baselineDeltaPct ?: return ""
        val rounded = Math.abs(d).toInt()
        if (rounded <= 0) return ""
        val direction = if (d > 0) "higher" else "lower"
        return " — currently about $rounded% $direction than this person's usual resting level"
    }

    /**
     * One decimal place, and never an empty string in the middle of a sentence.
     *
     * Locale.US is pinned deliberately. The default-locale overload would render
     * 37.4 as "37,4" on a device set to German, Hindi or French — a comma decimal
     * inside a clinical reading is at best confusing and at worst reads as a
     * different number.
     */
    private fun temp(e: AnomalyEvidence): String {
        val t = e.bodyTempC ?: return "—"
        return String.format(java.util.Locale.US, "%.1f", t)
    }

    private fun feelsLike(e: AnomalyEvidence): String {
        val t = e.ambientTempC ?: return "—"
        return t.toInt().toString()
    }
}
