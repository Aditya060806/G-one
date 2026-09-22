package com.gone.ai.health.sos

import com.gone.ai.health.domain.AnomalyType
import com.gone.ai.health.domain.Severity
import com.gone.ai.health.domain.VitalsSample
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Automatic SOS: what makes the app text, then call, the emergency contact.
 *
 * PURE KOTLIN. Which alerts count, what the message says and which numbers are usable are
 * decided here, where they can be tested; the Android side only sends.
 *
 * Every confirmed live anomaly is eligible. A fall additionally requires CRITICAL
 * confirmation and a finite recorded impact of at least SEVERE_IMPACT_G. The anomaly
 * detector owns persistence, signal validation and repeat suppression.
 *
 * Only live readings from the wearable count. A simulated reading is not a person, and a reading
 * replayed from the past describes something already over — the caller checks both.
 */
object SosPolicy {

    /** High-impact engineering threshold shared with fall detection; needs wearable validation. */
    const val SEVERE_IMPACT_G = 5.0f

    /**
     * Why an alert warrants an SOS, in words for the message; null when it does not.
     *
     * @param latest the reading the alert was raised on: its values go in the message.
     * @param fallImpactG the original peak recorded before post-impact stillness.
     */
    fun reasonFor(
        type: AnomalyType,
        severity: Severity,
        latest: VitalsSample?,
        fallImpactG: Float? = null
    ): String? {
        return when {
            type == AnomalyType.FALL_DETECTED ->
                if (severity == Severity.CRITICAL && fallImpactG != null && fallImpactG.isFinite() && fallImpactG >= SEVERE_IMPACT_G)
                    "Very hard impact (${oneDecimal(fallImpactG)} g), followed by no movement; a possible fall"
                else null
            severity != Severity.CRITICAL -> type.label
            type == AnomalyType.LOW_SPO2 || type == AnomalyType.SUSTAINED_LOW_SPO2 ->
                latest?.spo2?.let { "Blood oxygen $it %" } ?: "Blood oxygen very low"
            type == AnomalyType.HIGH_HEART_RATE ->
                latest?.heartRate?.let { "Heart rate $it beats a minute, very high" } ?: "Heart rate very high"
            type == AnomalyType.LOW_HEART_RATE ->
                latest?.heartRate?.let { "Heart rate $it beats a minute, very low" } ?: "Heart rate very low"
            else -> type.label
        }
    }

    private fun oneDecimal(v: Float): String = ((v * 10).roundToInt() / 10f).toString()
}

/** The text message. Plain, short, and clear that a machine sent it. */
object SosMessage {

    /**
     * @param name the wearer's name from their profile; "The wearer" when unset.
     * @param test a message sent from Settings to try the feature, marked so nobody is alarmed.
     */
    fun compose(
        name: String?,
        reasons: List<String>,
        at: Long,
        test: Boolean = false,
        zone: TimeZone = TimeZone.getDefault()
    ): String {
        val who = name?.trim()?.takeIf { it.isNotEmpty() } ?: "The wearer"
        val time = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = zone }.format(Date(at))
        if (test) {
            return "TEST from G-one: this is a test of $who's automatic SOS. Nothing has happened. " +
                "A real SOS would come from this number."
        }
        val what = reasons.distinct().joinToString("; ")
        return "SOS from G-one: $who may need help. $what, at $time. " +
            "Sent automatically by their health wearable. Please call them or check on them now."
    }
}

/** Emergency numbers as typed into the profile, made fit to dial and text. */
object SosNumbers {

    /**
     * Digits with an optional leading +, spaces, dashes, dots and brackets dropped; null when
     * what is left cannot be a phone number (fewer than 3 or more than 15 digits).
     */
    fun normalize(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val plus = text.startsWith("+")
        val digits = text.filter { it.isDigit() }
        if (text.any { !(it.isDigit() || it in " +-.()") }) return null
        if (digits.length !in 3..15) return null
        return (if (plus) "+" else "") + digits
    }
}
