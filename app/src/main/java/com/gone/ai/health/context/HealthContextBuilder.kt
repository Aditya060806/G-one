package com.gone.ai.health.context

import com.gone.ai.data.library.LibraryEntry
import com.gone.ai.health.data.AnomalyEventEntity
import com.gone.ai.health.data.EventStatus
import com.gone.ai.health.data.ReadingSource
import com.gone.ai.health.data.SessionReportEntity
import com.gone.ai.health.data.VitalsReadingEntity
import com.gone.ai.health.data.severityEnum
import com.gone.ai.health.data.statusEnum
import com.gone.ai.health.domain.AnomalyType
import com.gone.ai.health.explain.ResponseTier
import com.gone.ai.health.session.SessionReportBuilder
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * What the chat model is told about the person, written out as plain dated facts.
 *
 * Everything here is copied from stored data — readings, session reports, alerts and records
 * the person marked for the AI. Nothing is inferred. Each fact says where it came from and
 * when, simulated data says so on every line it touches, and missing data is stated as
 * missing, so the model has no gap to fill with a guess.
 *
 * Sections are written in priority order. The chat trims the text to its token budget from
 * the end, so what goes first survives: who the person is, the latest reading, recent
 * sessions, recent alerts, then records.
 */
object HealthContextBuilder {

    /** A reading older than this is not "latest". */
    const val FRESH_READING_MILLIS = 10 * 60_000L
    const val MAX_REPORTS = 3
    const val ALERT_WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1000
    const val MAX_ALERTS = 8
    /** Characters kept from each record; the whole block is fitted to tokens afterwards. */
    const val MAX_RECORD_CHARS = 900

    data class Input(
        val now: Long,
        val age: Int?,
        /** The newest stored reading, if any. */
        val latestReading: VitalsReadingEntity?,
        /** Newest first. */
        val reports: List<SessionReportEntity>,
        /** Any order; only the last [ALERT_WINDOW_MILLIS] are used. */
        val alerts: List<AnomalyEventEntity>,
        /** Records marked "Use in AI answers", newest first. */
        val records: List<LibraryEntry>
    )

    fun build(input: Input, timeZone: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault()): String {
        val dateTime = SimpleDateFormat("EEE d MMM yyyy, HH:mm", locale).apply { this.timeZone = timeZone }
        val day = SimpleDateFormat("EEE d MMM, HH:mm", locale).apply { this.timeZone = timeZone }
        val clock = SimpleDateFormat("HH:mm", locale).apply { this.timeZone = timeZone }
        val count = NumberFormat.getIntegerInstance(locale)

        return buildString {
            appendLine("Facts about the person, taken from data stored on this phone. Use only these facts about them.")
            appendLine("Now: ${dateTime.format(Date(input.now))}.")
            appendLine(input.age?.let { "Age: $it." } ?: "Age: not given.")

            // ── Latest reading ────────────────────────────────────────────────
            val reading = input.latestReading
            if (reading != null && input.now - reading.timestamp in 0..FRESH_READING_MILLIS) {
                val minutes = (input.now - reading.timestamp) / 60_000
                val simulated = reading.source == ReadingSource.SIMULATED.wireName
                val values = listOfNotNull(
                    reading.heartRate?.let { "heart rate $it bpm" },
                    reading.spo2?.let { "SpO2 $it%" },
                    reading.bodyTempC?.let { "body temperature ${one(it)} °C" },
                    reading.skinTempC?.let { "skin temperature ${one(it)} °C (skin, not body temperature)" },
                    reading.motionMagnitudeG?.let { "motion ${two(it)} g" },
                    reading.emgMean?.let { "muscle sensor level $it (uncalibrated)" }
                )
                append("Latest reading (")
                append(if (minutes < 1) "less than a minute ago" else "$minutes minute${if (minutes == 1L) "" else "s"} ago")
                append(if (simulated) ", SIMULATED – not measured from a person): " else ", from the wearable): ")
                appendLine(values.joinToString(", ").ifEmpty { "no values" } + ".")
            } else {
                appendLine("Latest reading: none in the last ${FRESH_READING_MILLIS / 60_000} minutes.")
            }

            // ── Sessions ──────────────────────────────────────────────────────
            val reports = input.reports.take(MAX_REPORTS)
            if (reports.isEmpty()) {
                appendLine("Monitoring session reports: none yet.")
            } else {
                appendLine("Monitoring session reports, newest first:")
                reports.forEach { r ->
                    val simulated = ReadingSource.SIMULATED in r.sourceList
                    val status = r.severityEnum?.let { "${r.eventCount} alert${if (r.eventCount == 1) "" else "s"}, highest: ${ResponseTier.forSeverity(it).label}" }
                        ?: "no alerts"
                    append("- ${day.format(Date(r.startedAt))} to ${clock.format(Date(r.endedAt))} ")
                    append("(${SessionReportBuilder.duration(r.endedAt - r.startedAt)}, ${count.format(r.sampleCount)} readings")
                    append(if (simulated) ", SIMULATED – not measured from a person" else "")
                    append("): $status. ")
                    appendLine(r.observationLines.joinToString(" "))
                }
            }

            // ── Alerts ────────────────────────────────────────────────────────
            val alerts = input.alerts
                .filter { input.now - it.createdAt in 0..ALERT_WINDOW_MILLIS }
                .sortedByDescending { it.createdAt }
            if (alerts.isEmpty()) {
                appendLine("Alerts in the last 7 days: none.")
            } else {
                appendLine("Alerts in the last 7 days, newest first:")
                alerts.take(MAX_ALERTS).forEach { a ->
                    val label = AnomalyType.fromWireName(a.eventType)?.label ?: a.eventType
                    val seen = if (a.statusEnum() == EventStatus.ACKNOWLEDGED) "marked seen" else "not yet marked seen"
                    appendLine("- ${day.format(Date(a.createdAt))}: $label (${ResponseTier.forSeverity(a.severityEnum()).label}), $seen.")
                }
                if (alerts.size > MAX_ALERTS) appendLine("- and ${alerts.size - MAX_ALERTS} earlier alerts.")
            }

            // ── Records ───────────────────────────────────────────────────────
            if (input.records.isNotEmpty()) {
                appendLine("Medical records the person chose to share, newest first (their own documents, as read from the file or photo):")
                input.records.forEach { record ->
                    val saved = SimpleDateFormat("d MMM yyyy", locale).apply { this.timeZone = timeZone }.format(Date(record.createdAt))
                    val text = record.content.trim().replace(Regex("\\s+"), " ")
                    val kept = if (text.length > MAX_RECORD_CHARS) text.take(MAX_RECORD_CHARS).substringBeforeLast(' ') + " …" else text
                    appendLine("- \"${record.title}\" (added $saved): $kept")
                }
            }
        }.trim()
    }

    private fun one(value: Float) = String.format(Locale.US, "%.1f", value)
    private fun two(value: Float) = String.format(Locale.US, "%.2f", value)
}
