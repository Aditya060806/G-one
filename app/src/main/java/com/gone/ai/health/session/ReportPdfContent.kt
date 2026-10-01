package com.gone.ai.health.session

import com.gone.ai.health.data.AnomalyEventEntity
import com.gone.ai.health.data.ReadingSource
import com.gone.ai.health.data.SessionReportEntity
import com.gone.ai.health.data.VitalsReadingEntity
import com.gone.ai.health.data.severityEnum
import com.gone.ai.health.domain.AnomalyType
import com.gone.ai.health.domain.Severity
import com.gone.ai.health.domain.Temperature
import com.gone.ai.health.explain.ResponseTier
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Everything a session's PDF shows, as plain values, before any layout.
 *
 * Built only from what the report and the stored readings contain — the same sources as the
 * report screen and [ReportText] — so the PDF cannot say more than the app does. Every chart
 * point is a stored reading; nothing is smoothed or estimated.
 */
data class ReportPdfContent(
    val title: String,
    val subtitle: String,
    val status: List<StatusLine>,
    val observations: List<String>,
    val points: List<PointRow>,
    val charts: List<ChartSeries>,
    val events: List<EventRow>,
    val aiSummary: String?,
    val disclaimer: String
) {
    enum class Tone { OK, NOTE, WARN, ALERT }

    data class StatusLine(val text: String, val tone: Tone)
    data class PointRow(val label: String, val time: String, val values: String)
    data class EventRow(val time: String, val label: String, val severity: String, val tone: Tone)

    /** One channel over the session. [times] and [values] are the same length, times ascending. */
    data class ChartSeries(
        val title: String,
        val unit: String,
        val times: List<Long>,
        val values: List<Float>,
        val decimals: Int
    )

    companion object {
        const val DISCLAIMER = "Recorded and summarised on this phone by G-one. This is a record of sensor " +
            "readings, not a diagnosis. Skin temperature and muscle-sensor levels are not calibrated measurements."

        /** Most points drawn per chart; more would not be visible at A4 width. */
        const val MAX_CHART_POINTS = 240

        fun from(
            report: SessionReportEntity,
            events: List<AnomalyEventEntity>,
            readings: List<VitalsReadingEntity>,
            timeZone: TimeZone = TimeZone.getDefault(),
            locale: Locale = Locale.getDefault()
        ): ReportPdfContent {
            val date = SimpleDateFormat("EEE d MMM yyyy, HH:mm", locale).apply { this.timeZone = timeZone }
            val time = SimpleDateFormat("HH:mm", locale).apply { this.timeZone = timeZone }
            val count = NumberFormat.getIntegerInstance(locale)

            val status = buildList {
                val severity = report.severityEnum
                if (severity == null) {
                    add(StatusLine("No alerts were raised during this session.", Tone.OK))
                } else {
                    val alerts = "${report.eventCount} alert${if (report.eventCount == 1) "" else "s"}"
                    add(StatusLine("$alerts · highest: ${ResponseTier.forSeverity(severity).label}", toneOf(severity)))
                }
                if (ReadingSource.SIMULATED in report.sourceList) {
                    add(StatusLine("Built from simulated vitals, not measurements from a person.", Tone.WARN))
                }
                if (report.gapCount > 0) {
                    add(StatusLine(
                        "The record has ${report.gapCount} gap${if (report.gapCount == 1) "" else "s"} with no readings; " +
                            "the longest lasted ${SessionReportBuilder.duration(report.longestGapMillis)}.",
                        Tone.NOTE
                    ))
                }
            }

            val points = RepresentativePointCodec.decode(report.representativePoints).map { p ->
                val values = listOfNotNull(
                    p.heartRate?.let { "HR ${it.toInt()}" },
                    p.spo2?.let { "SpO₂ ${it.toInt()}%" },
                    p.bodyTempC?.let { "core ${Temperature.fahrenheitText(it)}" },
                    p.skinTempC?.let { "skin ${Temperature.fahrenheitText(it)}" },
                    p.motionPeakG?.let { "motion ${one(it)} g" },
                    p.emgMean?.let { "EMG ${it.toInt()}" }
                )
                PointRow(p.label, time.format(Date(p.timestamp)), values.joinToString("  ·  ").ifEmpty { "no readings" })
            }

            val ordered = readings.sortedBy { it.timestamp }
            val charts = listOfNotNull(
                series("Heart rate", "bpm", 0, ordered) { it.heartRate?.toFloat() },
                series("SpO₂", "%", 0, ordered) { it.spo2?.toFloat() },
                series("Body temperature", "°F", 1, ordered) { it.bodyTempC?.let(Temperature::fahrenheit) },
                series("Skin temperature", "°F", 1, ordered) { it.skinTempC?.let(Temperature::fahrenheit) },
                series("Motion", "g", 2, ordered) { it.motionMagnitudeG },
                series("Muscle activity (EMG level)", "", 0, ordered) { it.emgMean?.toFloat() }
            )

            val eventRows = events.sortedBy { it.createdAt }.map { event ->
                val severity = event.severityEnum()
                EventRow(
                    time = time.format(Date(event.createdAt)),
                    label = AnomalyType.fromWireName(event.eventType)?.label ?: event.eventType,
                    severity = ResponseTier.forSeverity(severity).label,
                    tone = toneOf(severity)
                )
            }

            return ReportPdfContent(
                title = "G-one monitoring report",
                subtitle = "${date.format(Date(report.startedAt))} to ${time.format(Date(report.endedAt))} · " +
                    "${SessionReportBuilder.duration(report.endedAt - report.startedAt)} · " +
                    "${count.format(report.sampleCount)} readings",
                status = status,
                observations = report.observationLines,
                points = points,
                charts = charts,
                events = eventRows,
                aiSummary = report.aiSummary?.takeIf { it.isNotBlank() },
                disclaimer = DISCLAIMER
            )
        }

        private fun toneOf(severity: Severity) = when (severity) {
            Severity.CRITICAL -> Tone.ALERT
            Severity.MODERATE -> Tone.WARN
            Severity.LOW -> Tone.NOTE
        }

        /** A chart for one channel, or null when fewer than two readings carry it. */
        private fun series(
            title: String,
            unit: String,
            decimals: Int,
            readings: List<VitalsReadingEntity>,
            value: (VitalsReadingEntity) -> Float?
        ): ChartSeries? {
            val present = readings.mapNotNull { r -> value(r)?.let { r.timestamp to it } }
            if (present.size < 2) return null
            val sampled = strideSample(present, MAX_CHART_POINTS)
            return ChartSeries(title, unit, sampled.map { it.first }, sampled.map { it.second }, decimals)
        }

        /**
         * At most [target] points, keeping the first and last and the highest and lowest
         * readings, so a brief spike or dip is not thinned away.
         */
        internal fun <T> strideSample(points: List<Pair<Long, T>>, target: Int): List<Pair<Long, T>>
            where T : Comparable<T> {
            if (points.size <= target) return points
            val stride = points.size.toDouble() / target
            val keep = sortedSetOf(0, points.lastIndex)
            var i = 0.0
            while (i < points.size) { keep += i.toInt(); i += stride }
            keep += points.indices.maxBy { points[it].second }
            keep += points.indices.minBy { points[it].second }
            return keep.map { points[it] }
        }

        private fun one(value: Float) = String.format(Locale.US, "%.1f", value)
    }
}
