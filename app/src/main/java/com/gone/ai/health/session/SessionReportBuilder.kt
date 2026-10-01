package com.gone.ai.health.session

import com.gone.ai.health.data.ReadingSource
import com.gone.ai.health.domain.AnomalyThresholds
import com.gone.ai.health.domain.Temperature
import com.gone.ai.health.explain.ResponseTier
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Builds a [SessionReport] from what was recorded between a session's start and end.
 *
 * PURE AND DETERMINISTIC. The same readings and events always produce the same report,
 * word for word, so a report can be regenerated and audited, and the whole thing is
 * testable on the JVM.
 *
 * What this deliberately does NOT do, compared with REFERENCE's `LocalReportAnalyzer`:
 *  - no "Normal / Needs Attention / Concerning" grade of its own. The session's status
 *    is the most severe alert the detection engine actually raised, so a report can
 *    never be more alarming — or more reassuring — than the alerts the user already saw;
 *  - no food, exercise or lifestyle recommendations;
 *  - no temperature or SpO₂ estimated from other channels.
 */
object SessionReportBuilder {

    /** Fewer readings than this cannot support five representative points. */
    const val MIN_SAMPLES = 5

    /** A break between readings longer than this counts as a gap in the record. */
    const val GAP_MILLIS = 60_000L

    private val POINT_LABELS = listOf("Start", "25%", "50%", "75%", "End")

    /**
     * @return the report, or null when fewer than [MIN_SAMPLES] readings fall inside
     *   the session — too little to summarise honestly.
     */
    fun build(
        startedAt: Long,
        endedAt: Long,
        readings: List<SessionReading>,
        events: List<SessionEvent>,
        thresholds: AnomalyThresholds = AnomalyThresholds.DEFAULT
    ): SessionReport? {
        require(endedAt >= startedAt) { "session ends before it starts" }

        val inSession = readings
            .filter { it.sample.timestamp in startedAt..endedAt }
            .sortedBy { it.sample.timestamp }
        if (inSession.size < MIN_SAMPLES) return null

        val samples = inSession.map { it.sample }
        val sessionEvents = events.filter { it.at in startedAt..endedAt }.sortedBy { it.at }

        val heartRate = range(samples.mapNotNull { it.heartRate?.toFloat() })
        val spo2 = range(samples.mapNotNull { it.spo2?.toFloat() })
        val bodyTemp = range(samples.mapNotNull { it.bodyTempC })
        val skinTemp = range(samples.mapNotNull { it.skinTempC })
        val emg = range(samples.mapNotNull { it.emgMean?.toFloat() })
        val emgPeak = samples.mapNotNull { it.emgMax ?: it.emgMean }.maxOrNull()
        val motionPeak = samples.mapNotNull { it.motionMagnitudeG }.maxOrNull()

        val (gapCount, longestGap) = gaps(startedAt, endedAt, samples.map { it.timestamp })
        val sources = inSession.map { it.source }.toSortedSet()

        val partial = SessionReport(
            startedAt = startedAt,
            endedAt = endedAt,
            sampleCount = samples.size,
            sources = sources,
            heartRate = heartRate,
            spo2 = spo2,
            bodyTempC = bodyTemp,
            skinTempC = skinTemp,
            motionPeakG = motionPeak,
            emgMean = emg,
            emgPeak = emgPeak,
            events = sessionEvents,
            gapCount = gapCount,
            longestGapMillis = longestGap,
            points = representativePoints(samples),
            observations = emptyList()
        )
        return partial.copy(observations = observations(partial, thresholds))
    }

    // ── Statistics ────────────────────────────────────────────────────────────

    private fun range(values: List<Float>): ChannelRange? {
        if (values.isEmpty()) return null
        return ChannelRange(
            min = values.min(),
            mean = values.sum() / values.size,
            max = values.max(),
            count = values.size
        )
    }

    /**
     * Breaks longer than [GAP_MILLIS], including before the first reading and after the
     * last — a session that recorded nothing for its final twenty minutes has a gap
     * there, whatever the readings in between look like.
     */
    private fun gaps(startedAt: Long, endedAt: Long, timestamps: List<Long>): Pair<Int, Long> {
        val edges = listOf(startedAt) + timestamps + listOf(endedAt)
        val breaks = edges.zipWithNext { a, b -> b - a }.filter { it > GAP_MILLIS }
        return breaks.size to (breaks.maxOrNull() ?: 0L)
    }

    /** Five equal fifths of the session by reading count, each averaged. */
    private fun representativePoints(samples: List<com.gone.ai.health.domain.VitalsSample>): List<RepresentativePoint> {
        val n = samples.size
        val segment = n / POINT_LABELS.size.toDouble()
        return POINT_LABELS.mapIndexed { i, label ->
            val from = (i * segment).toInt()
            val to = if (i == POINT_LABELS.lastIndex) n else ((i + 1) * segment).toInt().coerceAtLeast(from + 1)
            val part = samples.subList(from, to)
            RepresentativePoint(
                label = label,
                timestamp = part[part.size / 2].timestamp,
                heartRate = mean(part.mapNotNull { it.heartRate?.toFloat() }),
                spo2 = mean(part.mapNotNull { it.spo2?.toFloat() }),
                bodyTempC = mean(part.mapNotNull { it.bodyTempC }),
                skinTempC = mean(part.mapNotNull { it.skinTempC }),
                motionPeakG = part.mapNotNull { it.motionMagnitudeG }.maxOrNull(),
                emgMean = mean(part.mapNotNull { it.emgMean?.toFloat() })
            )
        }
    }

    private fun mean(values: List<Float>): Float? =
        if (values.isEmpty()) null else values.sum() / values.size

    // ── Wording ───────────────────────────────────────────────────────────────

    /**
     * What was recorded, one sentence per channel, in a fixed order.
     *
     * Absent channels are said to be absent rather than skipped: a report with no oxygen
     * line reads as "oxygen was fine", which is not what the data says.
     */
    private fun observations(r: SessionReport, t: AnomalyThresholds): List<String> = buildList {
        add(sourceSentence(r))

        add(
            r.heartRate?.let {
                "Heart rate ranged from ${int(it.min)} to ${int(it.max)} beats a minute, averaging ${int(it.mean)}."
            } ?: "No heart-rate readings were received."
        )
        add(
            r.spo2?.let {
                "Blood oxygen ranged from ${int(it.min)}% to ${int(it.max)}%, averaging ${int(it.mean)}%."
            } ?: "No blood-oxygen readings were received."
        )
        r.bodyTempC?.let {
            add("Core body temperature ranged from ${Temperature.fahrenheitText(it.min)} to ${Temperature.fahrenheitText(it.max)}.")
        }
        r.skinTempC?.let {
            add(
                "Skin temperature ranged from ${Temperature.fahrenheitText(it.min)} to ${Temperature.fahrenheitText(it.max)}. Skin runs " +
                    "cooler than the body's core, so this is not a fever reading."
            )
        }
        r.motionPeakG?.let {
            add(
                if (it >= t.fallImpactG) {
                    "The hardest movement recorded was ${one(it)} g, which is at the level treated as a possible impact."
                } else {
                    "The hardest movement recorded was ${one(it)} g."
                }
            )
        }
        r.emgMean?.let {
            add(
                "The muscle sensor averaged ${int(it.mean)} and peaked at ${r.emgPeak ?: int(it.max)} " +
                    "on its 0–4095 scale. These levels are not calibrated to this person."
            )
        }
        add(eventSentence(r))
        if (r.gapCount > 0) {
            val times = if (r.gapCount == 1) "once" else "${r.gapCount} times"
            add(
                "Readings stopped $times for more than a minute; the longest break was " +
                    "${duration(r.longestGapMillis)}."
            )
        }
    }

    private fun sourceSentence(r: SessionReport): String {
        val lead = "Recorded for ${duration(r.durationMillis)}: ${r.sampleCount} readings"
        return when {
            r.sources == setOf(ReadingSource.SIMULATED) ->
                "$lead of simulated vitals. These are not measurements from a person."
            r.sources == setOf(ReadingSource.BLE) ->
                "$lead from the wearable."
            r.sources == setOf(ReadingSource.MANUAL) ->
                "$lead entered by hand."
            r.isSimulated ->
                "$lead, some of them simulated. Simulated readings are not measurements from a person."
            else ->
                "$lead from more than one source."
        }
    }

    private fun eventSentence(r: SessionReport): String {
        if (r.events.isEmpty()) return "No alerts were raised during the session."
        val byLabel = r.events.groupingBy { it.label }.eachCount()
            .entries.joinToString(", ") { (label, n) -> if (n == 1) label else "$label ($n)" }
        val count = if (r.events.size == 1) "One alert was" else "${r.events.size} alerts were"
        val tier = ResponseTier.forSeverity(r.highestSeverity!!).label
        return "$count raised: $byLabel. The most serious one called for \"$tier\"."
    }

    private fun int(value: Float): Int = value.roundToInt()

    private fun one(value: Float): String = String.format(Locale.US, "%.1f", value)

    /** "less than a minute", "about 1 minute", "about 42 minutes", "about 2 hours 5 minutes". */
    internal fun duration(millis: Long): String {
        val minutes = (millis / 60_000L).toInt()
        if (minutes < 1) return "less than a minute"
        val hours = minutes / 60
        val rest = minutes % 60
        val hourText = when (hours) { 0 -> ""; 1 -> "1 hour"; else -> "$hours hours" }
        val minuteText = when (rest) { 0 -> ""; 1 -> "1 minute"; else -> "$rest minutes" }
        return "about " + listOf(hourText, minuteText).filter { it.isNotEmpty() }.joinToString(" ")
    }
}
