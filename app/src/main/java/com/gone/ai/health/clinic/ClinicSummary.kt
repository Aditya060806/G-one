package com.gone.ai.health.clinic

import com.gone.ai.health.domain.AnomalyType
import com.gone.ai.health.domain.Severity
import com.gone.ai.health.domain.SleepLog
import com.gone.ai.health.domain.StressLog
import com.gone.ai.health.domain.VitalsSample
import com.gone.ai.health.insight.AlertRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** What goes into a clinic summary. Profile fields are as the wearer entered them. */
data class ClinicInput(
    val name: String?,
    val age: Int?,
    val bloodGroup: String?,
    val allergies: String?,
    val conditions: String?,
    val medications: String?,
    val implants: String?,
    val readings: List<VitalsSample>,
    val alerts: List<AlertRecord>,
    val sleep: List<SleepLog>,
    val stress: List<StressLog>,
    val from: Long,
    val to: Long
)

/** The wearer's choice of what to show the doctor. */
data class ClinicInclude(
    val medical: Boolean = true,
    val medications: Boolean = true,
    val wearable: Boolean = true,
    val alerts: Boolean = true,
    val journal: Boolean = true
)

data class ClinicLine(val label: String, val value: String)

data class ClinicSection(val title: String, val lines: List<ClinicLine>)

data class ClinicSummary(val sections: List<ClinicSection>, val generatedAt: Long, val from: Long, val to: Long) {
    /** The whole summary as plain text, for sharing in a message or e-mail. */
    fun toText(): String = buildString {
        sections.forEachIndexed { i, s ->
            if (i > 0) append('\n')
            append(s.title.uppercase(Locale.ROOT)).append('\n')
            s.lines.forEach { append(it.label).append(": ").append(it.value).append('\n') }
        }
        append('\n').append(ClinicSummaryBuilder.NOTE)
    }.trim()
}

/**
 * Builds a doctor-visit summary from the wearer's own records, and its QR form.
 *
 * PURE KOTLIN AND DETERMINISTIC. No model writes any of it; every figure is a minimum,
 * average, maximum or count over stored readings, alerts and journal entries. The QR holds
 * plain text, so any phone camera shows it without an app, a server or the internet.
 */
object ClinicSummaryBuilder {

    const val NOTE = "Patient-recorded wearable data from G-one. Not a diagnosis."

    /** A QR at error correction M holds about 2,300 bytes; this keeps it easy to scan. */
    const val QR_MAX_BYTES = 1_100

    /** SpO2 below this is counted as low, matching the alert rules' first threshold. */
    const val LOW_SPO2 = 92

    /** Readings arrive every few seconds; a gap longer than this is not counted as worn. */
    private const val WORN_GAP_MILLIS = 60_000L

    private val dayFormat = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    fun build(input: ClinicInput, include: ClinicInclude, now: Long, zone: ZoneId): ClinicSummary {
        val sections = mutableListOf<ClinicSection>()
        val day = { at: Long -> dayFormat.format(Instant.ofEpochMilli(at).atZone(zone)) }
        val dayOf = { d: LocalDate -> dayFormat.format(d) }

        sections += ClinicSection("G-one clinic summary", listOfNotNull(
            ClinicLine("Period", "${day(input.from)} – ${day(input.to)}"),
            input.name.clean()?.let { ClinicLine("Name", it) },
            input.age?.let { ClinicLine("Age", "$it") }
        ))

        if (include.medical) {
            val lines = listOfNotNull(
                input.bloodGroup.clean()?.let { ClinicLine("Blood group", it) },
                input.allergies.clean()?.let { ClinicLine("Allergies", it) },
                input.conditions.clean()?.let { ClinicLine("Conditions", it) },
                if (include.medications) input.medications.clean()?.let { ClinicLine("Medications", it) } else null,
                input.implants.clean()?.let { ClinicLine("Implants", it) }
            )
            if (lines.isNotEmpty()) sections += ClinicSection("Medical", lines)
        }

        if (include.wearable) sections += wearable(input.readings)

        if (include.alerts) {
            val lines = if (input.alerts.isEmpty()) listOf(ClinicLine("Alerts", "none"))
            else input.alerts.groupBy { it.type }
                .entries
                .sortedWith(compareByDescending<Map.Entry<AnomalyType?, List<AlertRecord>>> { e -> e.value.maxOf { it.severity?.rank ?: 0 } }
                    .thenByDescending { it.value.size })
                .map { (type, list) ->
                    val worst = list.mapNotNull { it.severity }.maxByOrNull { it.rank }
                    val tag = if (worst == Severity.CRITICAL) " (critical)" else ""
                    ClinicLine(type?.label ?: "Other", "${list.size}x, last ${day(list.maxOf { it.at })}$tag")
                }
            sections += ClinicSection("Alerts", lines)
        }

        if (include.journal) {
            val lines = buildList {
                if (input.sleep.isNotEmpty()) {
                    val avg = input.sleep.map { it.sleepMinutes }.average().roundToInt()
                    val quality = input.sleep.map { it.quality }.average()
                    val shortest = input.sleep.minBy { it.sleepMinutes }
                    add(ClinicLine("Sleep", "avg ${hm(avg)} over ${input.sleep.size} nights, quality ${one(quality)}/5"))
                    add(ClinicLine("Shortest night", "${hm(shortest.sleepMinutes)} (${dayOf(shortest.wakeDate)})"))
                }
                if (input.stress.isNotEmpty()) {
                    val avg = input.stress.map { it.level }.average()
                    add(ClinicLine("Stress", "avg ${one(avg)}/5 over ${input.stress.size} days, max ${input.stress.maxOf { it.level }}/5"))
                }
            }
            if (lines.isNotEmpty()) sections += ClinicSection("Self-reported", lines)
        }

        return ClinicSummary(sections, now, input.from, input.to)
    }

    private fun wearable(readings: List<VitalsSample>): ClinicSection {
        if (readings.isEmpty()) return ClinicSection("Wearable", listOf(ClinicLine("Readings", "none in this period")))
        val sorted = readings.sortedBy { it.timestamp }
        val wornMillis = sorted.zipWithNext().sumOf { (a, b) -> (b.timestamp - a.timestamp).takeIf { it <= WORN_GAP_MILLIS } ?: 0L }
        val days = sorted.map { it.timestamp / 86_400_000L }.distinct().size
        val hr = sorted.mapNotNull { it.heartRate }.filter { it > 0 }
        val spo2 = sorted.mapNotNull { it.spo2 }.filter { it > 0 }
        val skin = sorted.mapNotNull { it.skinTempC ?: it.bodyTempC }.filter { it.isFinite() }
        val motion = sorted.mapNotNull { it.motionMagnitudeG }.filter { it.isFinite() }
        val emg = sorted.mapNotNull { it.emgMean }
        val emgPeak = sorted.mapNotNull { it.emgMax ?: it.emgMean }

        return ClinicSection("Wearable", buildList {
            add(ClinicLine("Worn", "${one(wornMillis / 3_600_000.0)} h on $days ${if (days == 1) "day" else "days"}, ${sorted.size} readings"))
            if (hr.isNotEmpty()) add(ClinicLine("Heart rate", "min ${hr.min()} / avg ${hr.average().roundToInt()} / max ${hr.max()} bpm"))
            if (spo2.isNotEmpty()) {
                val low = spo2.count { it < LOW_SPO2 } * 100.0 / spo2.size
                add(ClinicLine("SpO2", "min ${spo2.min()} / avg ${spo2.average().roundToInt()}%, ${one(low)}% of readings <$LOW_SPO2"))
            }
            if (skin.isNotEmpty()) add(ClinicLine("Skin temp", "${one(skin.min().toDouble())}–${one(skin.max().toDouble())} C"))
            if (motion.isNotEmpty()) add(ClinicLine("Max impact", "${String.format(Locale.US, "%.1f", motion.max())} g"))
            if (emg.isNotEmpty()) add(ClinicLine("Muscle (EMG)", "avg ${emg.average().roundToInt()}, peak ${emgPeak.max()} of 4095"))
        })
    }

    /**
     * The summary as QR text: short, ASCII where it can be, and within [QR_MAX_BYTES] of UTF-8.
     * If it is too long, less important lines go first: the shortest night, muscle, skin
     * temperature, low-priority alerts, then free-text profile fields are shortened.
     */
    fun toQrText(summary: ClinicSummary): String {
        var sections = summary.sections
        fun render() = buildString {
            sections.forEach { s ->
                append('[').append(s.title).append("]\n")
                s.lines.forEach { append(it.label).append(": ").append(it.value).append('\n') }
            }
            append(NOTE)
        }
        fun fits() = render().toByteArray(Charsets.UTF_8).size <= QR_MAX_BYTES
        fun drop(section: String, label: String) {
            sections = sections.map { s -> if (s.title == section) s.copy(lines = s.lines.filterNot { it.label == label }) else s }
        }

        val optional = listOf(
            "Self-reported" to "Shortest night",
            "Wearable" to "Muscle (EMG)",
            "Wearable" to "Skin temp"
        )
        for ((section, label) in optional) { if (fits()) break; drop(section, label) }

        // Alerts are listed most serious first; drop from the end.
        while (!fits()) {
            val alerts = sections.firstOrNull { it.title == "Alerts" } ?: break
            if (alerts.lines.size <= 3) break
            drop("Alerts", alerts.lines.last().label)
        }

        // Last resort: shorten long free-text fields.
        var limit = 120
        while (!fits() && limit >= 30) {
            sections = sections.map { s -> s.copy(lines = s.lines.map { l -> if (l.value.length > limit) l.copy(value = l.value.take(limit - 1) + "…") else l }) }
            limit -= 30
        }
        return render()
    }

    private fun String?.clean(): String? = this?.trim()?.replace(Regex("""\s+"""), " ")?.takeIf { it.isNotEmpty() }

    private fun hm(minutes: Int) = "${minutes / 60}h${"%02d".format(minutes % 60)}"

    private fun one(v: Double) = String.format(Locale.US, "%.1f", v)
}
