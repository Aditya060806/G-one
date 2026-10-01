package com.gone.ai.health.session

import com.gone.ai.health.data.SessionReportEntity
import com.gone.ai.health.explain.ResponseTier
import com.gone.ai.health.domain.Temperature
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A stored report as plain text, for the Android share sheet.
 *
 * REPLACES REFERENCE'S E-MAIL SENDING. That version posted reports to a web API with a
 * key compiled into the APK and needed the INTERNET permission, which G-one does not have.
 * This produces text only; the user picks where it goes — messages, e-mail, notes — and
 * nothing leaves the phone until they do.
 *
 * No name, age or contact is included. The user can add whatever context they choose in
 * the app they share to.
 */
object ReportText {

    fun plain(report: SessionReportEntity, timeZone: TimeZone = TimeZone.getDefault()): String {
        val date = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.US).apply { this.timeZone = timeZone }
        val time = SimpleDateFormat("HH:mm", Locale.US).apply { this.timeZone = timeZone }

        return buildString {
            appendLine("G-one monitoring report")
            appendLine("${date.format(Date(report.startedAt))} to ${time.format(Date(report.endedAt))}")
            appendLine()
            appendLine(
                "Status: " + (report.severityEnum?.let { "${it.wireName} — ${ResponseTier.forSeverity(it).label}" }
                    ?: "no alerts raised")
            )
            appendLine()
            appendLine("What was recorded")
            report.observationLines.forEach { appendLine("- $it") }

            val points = RepresentativePointCodec.decode(report.representativePoints)
            if (points.isNotEmpty()) {
                appendLine()
                appendLine("Through the session")
                points.forEach { p ->
                    val values = listOfNotNull(
                        p.heartRate?.let { "HR ${it.toInt()}" },
                        p.spo2?.let { "SpO2 ${it.toInt()}%" },
                        p.bodyTempC?.let { "core ${Temperature.fahrenheitText(it)}" },
                        p.skinTempC?.let { "skin ${Temperature.fahrenheitText(it)}" },
                        p.motionPeakG?.let { "motion ${one(it)} g" },
                        p.emgMean?.let { "EMG ${it.toInt()}" }
                    )
                    appendLine("- ${p.label} (${time.format(Date(p.timestamp))}): ${values.joinToString(", ").ifEmpty { "no readings" }}")
                }
            }

            report.aiSummary?.let {
                appendLine()
                appendLine("Summary (reworded on this phone by the on-device model)")
                appendLine(it)
            }

            appendLine()
            append(
                "Recorded and summarised on this phone by G-one. This is a record of sensor " +
                    "readings, not a diagnosis. Skin temperature and muscle-sensor levels are not " +
                    "calibrated measurements."
            )
        }
    }

    private fun one(value: Float) = String.format(Locale.US, "%.1f", value)
}
