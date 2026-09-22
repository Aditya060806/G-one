package com.gone.ai.health.session

import com.gone.ai.health.data.ReadingSource
import com.gone.ai.health.domain.AnomalyType
import com.gone.ai.health.domain.Severity
import com.gone.ai.health.domain.VitalsSample
import java.util.Locale

/**
 * A stored reading as the report builder needs it: the values plus where they came from.
 *
 * Provenance travels with every reading because a report built from simulated vitals
 * must say so in its first sentence, not in a footnote.
 */
data class SessionReading(val sample: VitalsSample, val source: ReadingSource)

/** An alert raised during the session, reduced to what a report shows. */
data class SessionEvent(val type: AnomalyType?, val label: String, val severity: Severity, val at: Long)

/** Minimum, mean and maximum of one channel. Null fields mean the channel never reported. */
data class ChannelRange(val min: Float, val mean: Float, val max: Float, val count: Int)

/**
 * One of the five points that summarise a session's shape: start, the three quartiles
 * and the end.
 *
 * Each is the mean of its fifth of the session rather than a single sample, so one
 * motion artifact cannot become a fifth of the summary. Motion is the PEAK within the
 * segment because an impact is exactly the thing an average would hide.
 */
data class RepresentativePoint(
    val label: String,
    val timestamp: Long,
    val heartRate: Float? = null,
    val spo2: Float? = null,
    val bodyTempC: Float? = null,
    val skinTempC: Float? = null,
    val motionPeakG: Float? = null,
    val emgMean: Float? = null
)

/**
 * A finished session's report.
 *
 * EVERY NUMBER HERE WAS MEASURED OR COUNTED. Nothing is estimated from another channel.
 * REFERENCE derived temperature and SpO₂ from EMG and a synthesised heart rate, and its
 * reports graded those fabrications "Concerning". This report has no such path: a
 * channel the wearable did not send is absent, and [observations] says so.
 *
 * It also gives no advice. [observations] describe what was recorded; what to do about
 * a finding is decided by severity through the fixed response tiers, never by the report.
 */
data class SessionReport(
    val startedAt: Long,
    val endedAt: Long,
    val sampleCount: Int,
    val sources: Set<ReadingSource>,
    val heartRate: ChannelRange?,
    val spo2: ChannelRange?,
    val bodyTempC: ChannelRange?,
    val skinTempC: ChannelRange?,
    val motionPeakG: Float?,
    val emgMean: ChannelRange?,
    val emgPeak: Int?,
    val events: List<SessionEvent>,
    val gapCount: Int,
    val longestGapMillis: Long,
    val points: List<RepresentativePoint>,
    val observations: List<String>
) {
    val durationMillis: Long get() = endedAt - startedAt

    /** Most severe alert raised during the session, or null when nothing was raised. */
    val highestSeverity: Severity?
        get() = events.maxByOrNull { it.severity.rank }?.severity

    val isSimulated: Boolean get() = ReadingSource.SIMULATED in sources
}

/**
 * Compact text form of the representative points, for one database column.
 *
 * A hand-rolled format rather than JSON because the unit tests run against the stubbed
 * android.jar, where `org.json` returns defaults — a JSON round trip could not be
 * tested there at all. The format is fixed and versioned:
 *
 * ```
 * v1|label,timestamp,hr,spo2,bodyTemp,skinTemp,motionPeak,emg;label,...
 * ```
 *
 * Empty fields are nulls. Labels are fixed words from the builder and never contain
 * the separators.
 */
object RepresentativePointCodec {

    private const val VERSION = "v1"

    fun encode(points: List<RepresentativePoint>): String =
        VERSION + "|" + points.joinToString(";") { p ->
            listOf(
                p.label,
                p.timestamp.toString(),
                num(p.heartRate),
                num(p.spo2),
                num(p.bodyTempC),
                num(p.skinTempC),
                num(p.motionPeakG),
                num(p.emgMean)
            ).joinToString(",")
        }

    /** @return the points, or an empty list for text in an unknown format. */
    fun decode(text: String): List<RepresentativePoint> {
        val body = text.removePrefix("$VERSION|")
        if (body.length == text.length || body.isEmpty()) return emptyList()
        return body.split(';').mapNotNull { row ->
            val f = row.split(',')
            if (f.size != 8) return@mapNotNull null
            val ts = f[1].toLongOrNull() ?: return@mapNotNull null
            RepresentativePoint(
                label = f[0],
                timestamp = ts,
                heartRate = f[2].toFloatOrNull(),
                spo2 = f[3].toFloatOrNull(),
                bodyTempC = f[4].toFloatOrNull(),
                skinTempC = f[5].toFloatOrNull(),
                motionPeakG = f[6].toFloatOrNull(),
                emgMean = f[7].toFloatOrNull()
            )
        }
    }

    private fun num(value: Float?): String =
        value?.let { String.format(Locale.US, "%.2f", it) } ?: ""
}
