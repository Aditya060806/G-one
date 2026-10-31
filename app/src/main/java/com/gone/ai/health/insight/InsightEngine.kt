package com.gone.ai.health.insight

import com.gone.ai.health.domain.AnomalyType
import com.gone.ai.health.domain.Severity
import com.gone.ai.health.domain.SleepLog
import com.gone.ai.health.domain.StressLog
import com.gone.ai.health.domain.VitalsSample
import com.gone.ai.health.explain.SpokenAlerts
import com.gone.ai.voice.AppLanguage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** An alert as "Ask your health" needs it. */
data class AlertRecord(val type: AnomalyType?, val severity: Severity?, val at: Long)

/** Everything a question may draw on, for one period. Filtered to the period here as well. */
data class InsightData(
    val readings: List<VitalsSample> = emptyList(),
    val alerts: List<AlertRecord> = emptyList(),
    val sleep: List<SleepLog> = emptyList(),
    val stress: List<StressLog> = emptyList()
)

data class InsightPoint(val timestamp: Long, val value: Float)

data class InsightBar(val label: String, val value: Float, val emphasised: Boolean = false)

/** What to draw beside the answer. */
sealed class InsightChart {
    data object None : InsightChart()
    data class Line(val points: List<InsightPoint>, val highlight: InsightPoint? = null) : InsightChart()
    data class Bars(val bars: List<InsightBar>) : InsightChart()
}

/**
 * An answer.
 *
 * @property heading how the question was read, so the wearer can see it was understood.
 * @property answer one sentence, every number in it computed from [InsightData].
 * @property basis what the answer was worked out from.
 */
data class Insight(
    val heading: String,
    val answer: String,
    val chart: InsightChart,
    val basis: String,
    val hasData: Boolean
)

/**
 * Answers a [HealthQuery] from stored records.
 *
 * PURE KOTLIN AND DETERMINISTIC. The same records and question always give the same sentence.
 * The language model never reaches this class: whatever produced the query, the numbers here
 * come from readings, alerts and journal entries, and the sentences are fixed templates in
 * each language. That is what lets a spoken answer be trusted as much as the chart beside it.
 */
object InsightEngine {

    /** Charts are thinned to about this many points; impacts survive because movement keeps its peak. */
    private const val MAX_CHART_POINTS = 240

    data class Periods(val main: Pair<Long, Long>, val compare: Pair<Long, Long>?, val compareRange: TimeRange?)

    /** The time spans to load before calling [answer]. */
    fun periods(query: HealthQuery, now: Long, zone: ZoneId): Periods {
        val main = query.range.resolve(now, zone)
        if (query.op != InsightOp.COMPARE) return Periods(main, null, null)
        val other = query.compareTo ?: query.range.previous(now, zone)
        return Periods(main, other.resolve(now, zone), other)
    }

    fun answer(
        query: HealthQuery,
        data: InsightData,
        compareData: InsightData?,
        now: Long,
        zone: ZoneId,
        language: AppLanguage
    ): Insight {
        val periods = periods(query, now, zone)
        val w = Words(language, zone)
        val main = filtered(data, periods.main, zone)
        val other = periods.compare?.let { filtered(compareData ?: InsightData(), it, zone) }
        val range = w.range(query.range, periods.main)
        val compareRange = periods.compareRange?.let { w.range(it, periods.compare!!) }
        return when {
            query.metric.isReading -> readings(query, main.readings, other?.readings, range, compareRange, w)
            query.metric == InsightMetric.ALERTS -> alerts(query, main.alerts, range, w)
            query.metric == InsightMetric.SLEEP -> sleep(query, main.sleep, other?.sleep, range, compareRange, w)
            else -> stress(query, main.stress, other?.stress, range, compareRange, w)
        }
    }

    private fun filtered(data: InsightData, span: Pair<Long, Long>, zone: ZoneId): InsightData {
        val (from, to) = span
        val fromDate = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        val toDate = Instant.ofEpochMilli(to).atZone(zone).toLocalDate()
        return InsightData(
            readings = data.readings.filter { it.timestamp in from..to }.sortedBy { it.timestamp },
            alerts = data.alerts.filter { it.at in from..to }.sortedBy { it.at },
            sleep = data.sleep.filter { !it.wakeDate.isBefore(fromDate) && !it.wakeDate.isAfter(toDate) }.sortedBy { it.wakeDate },
            stress = data.stress.filter { !it.date.isBefore(fromDate) && !it.date.isAfter(toDate) }.sortedBy { it.date }
        )
    }

    // ── Wearable readings ─────────────────────────────────────────────────────

    private fun readings(
        q: HealthQuery,
        samples: List<VitalsSample>,
        compareSamples: List<VitalsSample>?,
        range: String,
        compareRange: String?,
        w: Words
    ): Insight {
        val m = q.metric
        val points = samples.mapNotNull { s -> value(s, m, q.op)?.let { InsightPoint(s.timestamp, it) } }
        val heading = w.heading(q.op, m, range, compareRange)
        if (points.isEmpty()) {
            return Insight(heading, w.noReadings(m, range), InsightChart.None, w.basisReadings(0), hasData = false)
        }
        val basis = w.basisReadings(points.size)
        val chartPoints = thin(points, keepPeaks = m == InsightMetric.MOVEMENT)

        return when (q.op) {
            InsightOp.HIGHEST, InsightOp.LOWEST -> {
                val pick = if (q.op == InsightOp.HIGHEST) points.maxBy { it.value } else points.minBy { it.value }
                Insight(heading, w.extreme(q.op, m, range, w.value(m, pick.value), w.time(pick.timestamp)),
                    InsightChart.Line(chartPoints, pick), basis, true)
            }
            InsightOp.AVERAGE ->
                Insight(heading, w.average(m, range, w.value(m, points.map { it.value }.average().toFloat()), points.size),
                    InsightChart.Line(chartPoints), basis, true)
            InsightOp.LATEST -> {
                val last = points.last()
                Insight(heading, w.latest(m, w.value(m, last.value), w.time(last.timestamp)),
                    InsightChart.Line(chartPoints, last), basis, true)
            }
            InsightOp.COMPARE -> {
                val a = points.map { it.value }.average().toFloat()
                val others = compareSamples.orEmpty().mapNotNull { value(it, m, q.op) }
                if (others.isEmpty()) {
                    return Insight(heading, w.noReadings(m, compareRange!!), InsightChart.Line(chartPoints), basis, true)
                }
                val b = others.average().toFloat()
                Insight(heading, w.compare(m, range, compareRange!!, w.value(m, a), w.value(m, b), direction(a, b, m)),
                    InsightChart.Bars(listOf(InsightBar(w.capital(range), a, true), InsightBar(w.capital(compareRange), b))),
                    w.basisReadings(points.size + others.size), true)
            }
            // TREND, and COUNT (which the parser does not send for readings).
            else -> {
                val values = points.map { it.value }
                Insight(heading,
                    w.trend(m, range, w.value(m, values.min()), w.value(m, values.max()), trend(values, m)),
                    InsightChart.Line(chartPoints), basis, true)
            }
        }
    }

    private fun value(s: VitalsSample, m: InsightMetric, op: InsightOp): Float? = when (m) {
        InsightMetric.HEART_RATE -> s.heartRate?.toFloat()
        InsightMetric.SPO2 -> s.spo2?.toFloat()
        InsightMetric.SKIN_TEMPERATURE -> s.skinTempC ?: s.bodyTempC
        InsightMetric.MOVEMENT -> s.motionMagnitudeG
        // The peak answers "highest"; the level answers everything else.
        InsightMetric.MUSCLE -> (if (op == InsightOp.HIGHEST) s.emgMax ?: s.emgMean else s.emgMean)?.toFloat()
        else -> null
    }?.takeIf { it.isFinite() }

    /** Differences smaller than this are "about the same", per metric. */
    private fun tolerance(m: InsightMetric): Float = when (m) {
        InsightMetric.HEART_RATE -> 2f
        InsightMetric.SPO2 -> 1f
        InsightMetric.SKIN_TEMPERATURE -> 0.2f
        InsightMetric.MOVEMENT -> 0.05f
        InsightMetric.MUSCLE -> 50f
        InsightMetric.SLEEP -> 15f
        InsightMetric.STRESS -> 0.3f
        InsightMetric.ALERTS -> 0.5f
    }

    private fun direction(a: Float, b: Float, m: InsightMetric): Int = when {
        a - b > tolerance(m) -> 1
        b - a > tolerance(m) -> -1
        else -> 0
    }

    /** Rising, falling or steady: the last third against the first. Null with too few values to say. */
    private fun trend(values: List<Float>, m: InsightMetric): Int? {
        if (values.size < 6) return null
        val third = values.size / 3
        return direction(values.takeLast(third).average().toFloat(), values.take(third).average().toFloat(), m)
    }

    /** About [MAX_CHART_POINTS] points: the mean of each slice, or its peak for movement. */
    private fun thin(points: List<InsightPoint>, keepPeaks: Boolean): List<InsightPoint> {
        if (points.size <= MAX_CHART_POINTS) return points
        val size = (points.size + MAX_CHART_POINTS - 1) / MAX_CHART_POINTS
        return points.chunked(size).map { slice ->
            if (keepPeaks) slice.maxBy { it.value }
            else InsightPoint(slice[slice.size / 2].timestamp, slice.map { it.value }.average().toFloat())
        }
    }

    // ── Alerts ────────────────────────────────────────────────────────────────

    private fun alerts(q: HealthQuery, all: List<AlertRecord>, range: String, w: Words): Insight {
        val alerts = q.alertType?.let { type -> all.filter { it.type == type } } ?: all
        val heading = w.heading(if (q.op == InsightOp.LATEST) InsightOp.LATEST else InsightOp.COUNT, InsightMetric.ALERTS, range, null, q.alertType)
        val chart = if (alerts.isEmpty()) InsightChart.None else InsightChart.Bars(
            alerts.groupBy { w.dayLabel(it.at) }.map { (day, list) -> InsightBar(day, list.size.toFloat()) }
        )
        val answer = if (q.op == InsightOp.LATEST) {
            alerts.lastOrNull()?.let { w.latestAlert(it, q.alertType) } ?: w.noAlerts(range, q.alertType)
        } else {
            val labels = alerts.groupingBy { it.type }.eachCount().entries
                .sortedByDescending { it.value }
                .take(3)
                .map { (type, n) -> w.alertLabel(type) + if (n > 1) " ×$n" else "" }
            w.alertCount(alerts.size, range, labels, q.alertType)
        }
        return Insight(heading, answer, chart, w.basisAlerts(), alerts.isNotEmpty())
    }

    // ── Journals ──────────────────────────────────────────────────────────────

    private fun sleep(
        q: HealthQuery, logs: List<SleepLog>, compareLogs: List<SleepLog>?, range: String, compareRange: String?, w: Words
    ): Insight {
        val heading = w.heading(q.op, InsightMetric.SLEEP, range, compareRange)
        if (logs.isEmpty()) return Insight(heading, w.noSleep(range), InsightChart.None, w.basisJournal(0), false)
        val bars = InsightChart.Bars(logs.map { InsightBar(w.dayLabel(it.wakeDate), it.sleepMinutes.toFloat()) })
        val minutes = logs.map { it.sleepMinutes.toFloat() }
        val answer = when (q.op) {
            InsightOp.HIGHEST -> logs.maxBy { it.sleepMinutes }.let { w.sleepExtreme(true, range, w.duration(it.sleepMinutes), w.date(it.wakeDate)) }
            InsightOp.LOWEST -> logs.minBy { it.sleepMinutes }.let { w.sleepExtreme(false, range, w.duration(it.sleepMinutes), w.date(it.wakeDate)) }
            InsightOp.LATEST -> logs.last().let { w.sleepLatest(w.duration(it.sleepMinutes), w.date(it.wakeDate)) }
            InsightOp.COMPARE -> {
                val others = compareLogs.orEmpty()
                if (others.isEmpty()) w.noSleep(compareRange!!)
                else {
                    val a = minutes.average().toFloat()
                    val b = others.map { it.sleepMinutes.toFloat() }.average().toFloat()
                    w.compare(InsightMetric.SLEEP, range, compareRange!!, w.duration(a.roundToInt()), w.duration(b.roundToInt()), direction(a, b, InsightMetric.SLEEP))
                }
            }
            else -> w.sleepAverage(range, w.duration(minutes.average().roundToInt()), logs.size, w.decimal(logs.map { it.quality }.average().toFloat()))
        }
        return Insight(heading, answer, bars, w.basisJournal(logs.size + (if (q.op == InsightOp.COMPARE) compareLogs.orEmpty().size else 0)), true)
    }

    private fun stress(
        q: HealthQuery, logs: List<StressLog>, compareLogs: List<StressLog>?, range: String, compareRange: String?, w: Words
    ): Insight {
        val heading = w.heading(q.op, InsightMetric.STRESS, range, compareRange)
        if (logs.isEmpty()) return Insight(heading, w.noStress(range), InsightChart.None, w.basisJournal(0), false)
        val bars = InsightChart.Bars(logs.map { InsightBar(w.dayLabel(it.date), it.level.toFloat()) })
        val levels = logs.map { it.level.toFloat() }
        val answer = when (q.op) {
            InsightOp.HIGHEST -> logs.maxBy { it.level }.let { w.stressExtreme(true, range, it.level, w.date(it.date)) }
            InsightOp.LOWEST -> logs.minBy { it.level }.let { w.stressExtreme(false, range, it.level, w.date(it.date)) }
            InsightOp.LATEST -> logs.last().let { w.stressLatest(it.level, w.date(it.date)) }
            InsightOp.COMPARE -> {
                val others = compareLogs.orEmpty()
                if (others.isEmpty()) w.noStress(compareRange!!)
                else {
                    val a = levels.average().toFloat()
                    val b = others.map { it.level.toFloat() }.average().toFloat()
                    w.compare(InsightMetric.STRESS, range, compareRange!!, w.outOfFive(a), w.outOfFive(b), direction(a, b, InsightMetric.STRESS))
                }
            }
            else -> w.stressAverage(range, w.outOfFive(levels.average().toFloat()), logs.size)
        }
        return Insight(heading, answer, bars, w.basisJournal(logs.size + (if (q.op == InsightOp.COMPARE) compareLogs.orEmpty().size else 0)), true)
    }

    // ── Wording ───────────────────────────────────────────────────────────────

    /**
     * Every sentence, in each language. Numbers are written with ASCII digits in both, as
     * Indian phones show and speak them that way.
     *
     * Hindi sentences are built around मान ("value") and औसत ("average"), which are masculine,
     * so the verb agrees whatever the measured thing is: धड़कन is feminine, तापमान masculine.
     */
    private class Words(val lang: AppLanguage, val zone: ZoneId) {
        private val hi = lang == AppLanguage.HINDI
        private val locale: Locale = lang.locale
        private val timeFormat = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", locale)
        private val dateFormat = DateTimeFormatter.ofPattern("EEE d MMM", locale)
        private val dayFormat = DateTimeFormatter.ofPattern("EEE d", locale)

        fun time(at: Long): String = timeFormat.format(Instant.ofEpochMilli(at).atZone(zone))
        fun date(day: LocalDate): String = dateFormat.format(day)
        fun dayLabel(at: Long): String = dayFormat.format(Instant.ofEpochMilli(at).atZone(zone))
        fun dayLabel(day: LocalDate): String = dayFormat.format(day)
        fun capital(s: String): String = s.replaceFirstChar { it.titlecase(locale) }
        fun decimal(v: Float): String = String.format(Locale.US, "%.1f", v)

        fun metric(m: InsightMetric): String = when (m) {
            InsightMetric.HEART_RATE -> if (hi) "दिल की धड़कन" else "heart rate"
            InsightMetric.SPO2 -> if (hi) "रक्त ऑक्सीजन" else "blood oxygen"
            InsightMetric.SKIN_TEMPERATURE -> if (hi) "त्वचा का तापमान" else "skin temperature"
            InsightMetric.MOVEMENT -> if (hi) "हरकत" else "movement"
            InsightMetric.MUSCLE -> if (hi) "मांसपेशियों की गतिविधि" else "muscle activity"
            InsightMetric.ALERTS -> if (hi) "चेतावनियाँ" else "alerts"
            InsightMetric.SLEEP -> if (hi) "नींद" else "sleep"
            InsightMetric.STRESS -> if (hi) "तनाव" else "stress"
        }

        fun value(m: InsightMetric, v: Float): String = when (m) {
            InsightMetric.HEART_RATE -> "${v.roundToInt()}" + if (hi) " प्रति मिनट" else " bpm"
            InsightMetric.SPO2 -> "${v.roundToInt()}%"
            InsightMetric.SKIN_TEMPERATURE -> decimal(v) + if (hi) " डिग्री सेल्सियस" else " °C"
            InsightMetric.MOVEMENT -> String.format(Locale.US, "%.2f g", v)
            InsightMetric.MUSCLE -> if (hi) "0–4095 पैमाने पर ${v.roundToInt()}" else "${v.roundToInt()} on the 0–4095 scale"
            InsightMetric.SLEEP -> duration(v.roundToInt())
            InsightMetric.STRESS -> outOfFive(v)
            InsightMetric.ALERTS -> "${v.roundToInt()}"
        }

        fun duration(minutes: Int): String {
            val h = minutes / 60
            val m = minutes % 60
            return if (hi) "$h घंटे $m मिनट" else "$h h $m min"
        }

        fun outOfFive(v: Float): String = if (hi) "5 में से ${decimal(v)}" else "${decimal(v)} out of 5"

        fun range(r: TimeRange, span: Pair<Long, Long>): String = when (r) {
            TimeRange.Today -> if (hi) "आज" else "today"
            TimeRange.Yesterday -> if (hi) "कल" else "yesterday"
            TimeRange.LastNight -> if (hi) "कल रात" else "last night"
            TimeRange.ThisWeek -> if (hi) "इस हफ़्ते" else "this week"
            TimeRange.PreviousWeek -> if (hi) "पिछले हफ़्ते" else "last week"
            is TimeRange.LastHours -> when {
                r.hours == 1 -> if (hi) "पिछले एक घंटे में" else "in the last hour"
                hi -> "पिछले ${r.hours} घंटों में"
                else -> "in the last ${r.hours} hours"
            }
            is TimeRange.LastDays -> when {
                r.days == 1 -> if (hi) "पिछले एक दिन में" else "in the last day"
                hi -> "पिछले ${r.days} दिनों में"
                else -> "in the last ${r.days} days"
            }
            is TimeRange.Between -> {
                val from = date(Instant.ofEpochMilli(span.first).atZone(zone).toLocalDate())
                val to = date(Instant.ofEpochMilli(span.second).atZone(zone).toLocalDate())
                if (hi) "$from से $to तक" else "from $from to $to"
            }
        }

        fun heading(op: InsightOp, m: InsightMetric, range: String, compareRange: String?, alertType: AnomalyType? = null): String {
            val what = if (alertType != null) alertLabel(alertType) else metric(m)
            val opWord = when (op) {
                InsightOp.HIGHEST -> if (hi) "सबसे अधिक" else "Highest"
                InsightOp.LOWEST -> if (hi) "सबसे कम" else "Lowest"
                InsightOp.AVERAGE -> if (hi) "औसत" else "Average"
                InsightOp.TREND -> if (hi) "रुझान:" else "Trend of"
                InsightOp.COUNT -> if (hi) "गिनती:" else "Number of"
                InsightOp.LATEST -> if (hi) "ताज़ा:" else "Latest"
                InsightOp.COMPARE -> if (hi) "तुलना:" else "Compare"
            }
            val period = if (compareRange != null) "$range ${if (hi) "बनाम" else "vs"} $compareRange" else range
            return "$opWord $what · $period"
        }

        fun basisReadings(n: Int): String =
            if (hi) "इस फ़ोन में सहेजी गई $n रीडिंग से। हर संख्या आपके रिकॉर्ड से है।"
            else "From $n wearable readings stored on this phone. Every number comes from your records."

        fun basisAlerts(): String =
            if (hi) "G-one द्वारा दर्ज चेतावनियों से।" else "From the alerts G-one recorded on this phone."

        fun basisJournal(n: Int): String =
            if (hi) "आपकी डायरी की $n प्रविष्टियों से।" else "From $n entries you made in your journal."

        // Readings

        fun noReadings(m: InsightMetric, range: String): String =
            if (hi) "${capital(range)} ${metric(m)} की कोई रीडिंग नहीं है।"
            else "There are no ${metric(m)} readings ${range}."

        fun extreme(op: InsightOp, m: InsightMetric, range: String, value: String, time: String): String {
            val highest = op == InsightOp.HIGHEST
            return if (hi) "${capital(range)} ${metric(m)} का ${if (highest) "सबसे अधिक" else "सबसे कम"} मान $value था ($time)।"
            else "Your ${if (highest) "highest" else "lowest"} ${metric(m)} $range was $value, at $time."
        }

        fun average(m: InsightMetric, range: String, value: String, n: Int): String =
            if (hi) "${capital(range)} ${metric(m)} का औसत $value था ($n रीडिंग से)।"
            else "Your average ${metric(m)} $range was $value, from $n readings."

        fun latest(m: InsightMetric, value: String, time: String): String =
            if (hi) "${capital(metric(m))} का ताज़ा मान $value था ($time)।"
            else "Your latest ${metric(m)} was $value, at $time."

        fun trend(m: InsightMetric, range: String, min: String, max: String, direction: Int?): String {
            if (hi) {
                val dir = when (direction) { 1 -> ", और कुल मिलाकर बढ़ रहे थे"; -1 -> ", और कुल मिलाकर घट रहे थे"; 0 -> ", और कुल मिलाकर स्थिर थे"; else -> "" }
                return "${capital(range)} ${metric(m)} के मान $min से $max के बीच थे$dir।"
            }
            val dir = when (direction) { 1 -> ", and rose overall"; -1 -> ", and fell overall"; 0 -> ", and stayed steady overall"; else -> "" }
            return "Your ${metric(m)} $range ranged from $min to $max$dir."
        }

        fun compare(m: InsightMetric, range: String, other: String, a: String, b: String, direction: Int): String {
            if (hi) {
                val verdict = when (direction) { 1 -> "ज़्यादा था"; -1 -> "कम था"; else -> "लगभग बराबर था" }
                return "${capital(range)} ${metric(m)} का औसत $a था, और $other $b। ${capital(range)} का औसत $verdict।"
            }
            val verdict = when (direction) { 1 -> "higher"; -1 -> "lower"; else -> "about the same" }
            return "Your average ${metric(m)} was $a $range, against $b $other: $verdict."
        }

        // Alerts

        fun alertLabel(type: AnomalyType?): String =
            type?.let { SpokenAlerts.label(it, lang) } ?: if (hi) "अन्य चेतावनी" else "Other alert"

        fun noAlerts(range: String, type: AnomalyType?): String = when {
            type == AnomalyType.FALL_DETECTED -> if (hi) "${capital(range)} गिरने की कोई घटना दर्ज नहीं हुई।" else "No falls were detected $range."
            hi -> "${capital(range)} कोई चेतावनी नहीं आई।"
            else -> "No alerts were raised $range."
        }

        fun alertCount(n: Int, range: String, labels: List<String>, type: AnomalyType?): String {
            if (n == 0) return noAlerts(range, type)
            if (type == AnomalyType.FALL_DETECTED) {
                return if (hi) "${capital(range)} $n बार संभावित गिरावट दर्ज हुई।"
                else if (n == 1) "1 possible fall was detected $range." else "$n possible falls were detected $range."
            }
            val list = labels.joinToString(", ")
            return if (hi) "${capital(range)} $n ${if (n == 1) "चेतावनी आई" else "चेतावनियाँ आईं"}: $list।"
            else if (n == 1) "1 alert was raised $range: $list." else "$n alerts were raised $range: $list."
        }

        fun latestAlert(a: AlertRecord, type: AnomalyType?): String = when {
            type == AnomalyType.FALL_DETECTED ->
                if (hi) "आखिरी संभावित गिरावट ${time(a.at)} पर दर्ज हुई।" else "The last possible fall was detected at ${time(a.at)}."
            hi -> "आखिरी चेतावनी: ${alertLabel(a.type)}, ${time(a.at)} पर।"
            else -> "The last alert was ${alertLabel(a.type)}, at ${time(a.at)}."
        }

        // Sleep

        fun noSleep(range: String): String =
            if (hi) "${capital(range)} आपने कोई नींद दर्ज नहीं की है।" else "You have not logged any sleep $range."

        fun sleepAverage(range: String, avg: String, nights: Int, quality: String): String =
            if (hi) "${capital(range)} आपकी औसत नींद $avg थी ($nights रातों के रिकॉर्ड से)। औसत गुणवत्ता 5 में से $quality।"
            else "You slept $avg on average $range, over $nights ${if (nights == 1) "night" else "nights"} you logged. Average quality $quality out of 5."

        fun sleepExtreme(longest: Boolean, range: String, d: String, wake: String): String =
            if (hi) "${capital(range)} ${if (longest) "सबसे लंबी" else "सबसे छोटी"} नींद $d थी, $wake की सुबह तक।"
            else "Your ${if (longest) "longest" else "shortest"} sleep $range was $d, waking on $wake."

        fun sleepLatest(d: String, wake: String): String =
            if (hi) "आखिरी दर्ज नींद: $d, $wake को जागे।" else "Your last logged sleep was $d, waking on $wake."

        // Stress

        fun noStress(range: String): String =
            if (hi) "${capital(range)} आपने तनाव दर्ज नहीं किया है।" else "You have not logged stress $range."

        fun stressAverage(range: String, avg: String, days: Int): String =
            if (hi) "${capital(range)} आपका औसत तनाव $avg था ($days दिनों के रिकॉर्ड से)।"
            else "Your average stress $range was $avg, over $days ${if (days == 1) "day" else "days"} you logged."

        fun stressExtreme(highest: Boolean, range: String, level: Int, day: String): String =
            if (hi) "${capital(range)} ${if (highest) "सबसे ज़्यादा" else "सबसे कम"} तनाव $day को था: 5 में से $level।"
            else "Your ${if (highest) "most stressful" else "calmest"} day $range was $day, at $level out of 5."

        fun stressLatest(level: Int, day: String): String =
            if (hi) "आखिरी प्रविष्टि: $day को 5 में से $level।" else "Your last entry was $level out of 5, on $day."
    }
}
