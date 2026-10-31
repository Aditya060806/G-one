package com.gone.ai.health.insight

import com.gone.ai.health.domain.AnomalyType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * A question about the wearer's own data, in a form the database can answer.
 *
 * PURE KOTLIN. This is the whole vocabulary of "Ask your health": a question becomes one of
 * these, by fixed rules or by the language model, and every number in the answer is then
 * computed from stored records. The model can choose what to look up; it can never supply a
 * value, because there is nowhere in a [HealthQuery] to put one.
 */
data class HealthQuery(
    val metric: InsightMetric,
    val op: InsightOp,
    val range: TimeRange,
    /** For [InsightOp.COMPARE]: the period to set against [range]. */
    val compareTo: TimeRange? = null,
    /** For [InsightMetric.ALERTS]: only this kind, e.g. falls. Null means every kind. */
    val alertType: AnomalyType? = null
)

/** What can be asked about. Readings come from the wearable; sleep and stress are journals. */
enum class InsightMetric(val key: String) {
    HEART_RATE("heart_rate"),
    SPO2("spo2"),
    SKIN_TEMPERATURE("skin_temperature"),
    MOVEMENT("movement"),
    MUSCLE("muscle"),
    ALERTS("alerts"),
    SLEEP("sleep"),
    STRESS("stress");

    /** Measured by the wearable, five-second readings; the rest are events or daily entries. */
    val isReading: Boolean get() = this in setOf(HEART_RATE, SPO2, SKIN_TEMPERATURE, MOVEMENT, MUSCLE)

    companion object {
        fun fromKey(key: String): InsightMetric? = entries.firstOrNull { it.key == key }
    }
}

enum class InsightOp(val key: String) {
    HIGHEST("highest"),
    LOWEST("lowest"),
    AVERAGE("average"),
    TREND("trend"),
    COUNT("count"),
    LATEST("latest"),
    COMPARE("compare");

    companion object {
        fun fromKey(key: String): InsightOp? = entries.firstOrNull { it.key == key }
    }
}

/** A period of time, resolved against "now" when the question is answered. */
sealed class TimeRange {
    data object Today : TimeRange()
    data object Yesterday : TimeRange()
    /** 20:00 yesterday to 08:00 today, or now if that is earlier. */
    data object LastNight : TimeRange()
    /** Monday 00:00 to now. */
    data object ThisWeek : TimeRange()
    /** The whole of last Monday to Sunday. */
    data object PreviousWeek : TimeRange()
    data class LastHours(val hours: Int) : TimeRange()
    data class LastDays(val days: Int) : TimeRange()
    /** An explicit window; used for "the same length of time before". */
    data class Between(val from: Long, val to: Long) : TimeRange()

    /** Start and end, epoch milliseconds, inclusive. */
    fun resolve(now: Long, zone: ZoneId): Pair<Long, Long> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun startOf(day: LocalDate) = day.atStartOfDay(zone).toInstant().toEpochMilli()
        return when (this) {
            Today -> startOf(today) to now
            Yesterday -> startOf(today.minusDays(1)) to startOf(today) - 1
            LastNight -> {
                val from = today.minusDays(1).atTime(LocalTime.of(20, 0)).atZone(zone).toInstant().toEpochMilli()
                val to = today.atTime(LocalTime.of(8, 0)).atZone(zone).toInstant().toEpochMilli()
                from to minOf(to, now)
            }
            ThisWeek -> startOf(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))) to now
            PreviousWeek -> {
                val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                startOf(monday.minusWeeks(1)) to startOf(monday) - 1
            }
            is LastHours -> now - hours * HOUR to now
            is LastDays -> now - days * DAY to now
            is Between -> from to to
        }
    }

    /**
     * The period to compare this one with when only one was named: the one before it, of the
     * same kind ("today" against yesterday, "this week" against last week).
     */
    fun previous(now: Long, zone: ZoneId): TimeRange = when (this) {
        Today -> Yesterday
        ThisWeek -> PreviousWeek
        else -> {
            val (from, to) = resolve(now, zone)
            val length = to - from + 1
            Between(from - length, from - 1)
        }
    }

    companion object {
        const val HOUR = 60L * 60 * 1000
        const val DAY = 24 * HOUR
        /** The longest period a question may cover. */
        const val MAX_DAYS = 90
    }
}
