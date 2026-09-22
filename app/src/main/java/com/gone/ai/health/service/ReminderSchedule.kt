package com.gone.ai.health.service

import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * When the next "Make me Healthy" reminder is due.
 *
 * Reminders used to be an hourly repeating alarm around the clock: the "during work hours"
 * promise in the Tools screen was never kept, and the phone buzzed at 3 a.m. Now each
 * reminder falls on the top of an hour inside the person's active hours, both ends included,
 * and the next one is scheduled when it fires.
 */
object ReminderSchedule {

    const val DEFAULT_START_HOUR = 9
    const val DEFAULT_END_HOUR = 21

    /** Active hours, both ends included, as whole hours of a 24-hour day. */
    data class Window(val startHour: Int, val endHour: Int) {
        init {
            require(startHour in 0..23 && endHour in 0..23) { "hours must be 0..23" }
            require(startHour <= endHour) { "the window must start before it ends" }
        }

        fun contains(hour: Int) = hour in startHour..endHour

        val label: String get() = "%02d:00–%02d:00".format(startHour, endHour)
    }

    /** The next top of the hour after [now] that falls inside [window]. */
    fun next(now: ZonedDateTime, window: Window): ZonedDateTime {
        val nextHour = now.truncatedTo(ChronoUnit.HOURS).plusHours(1)
        return when {
            window.contains(nextHour.hour) && nextHour.toLocalDate() == now.toLocalDate() -> nextHour
            now.hour < window.startHour -> now.truncatedTo(ChronoUnit.DAYS).withHour(window.startHour)
            else -> now.truncatedTo(ChronoUnit.DAYS).plusDays(1).withHour(window.startHour)
        }
    }

    /**
     * Whether a reminder firing at [time] should still be shown. Alarms are inexact and the
     * phone may deliver one late, e.g. after Doze; one that lands outside the window is skipped
     * rather than shown at night.
     */
    fun shouldShow(time: ZonedDateTime, window: Window) = window.contains(time.hour)

    /** The reminder after [last] when taking turns: drink, stand, move, drink… */
    fun <T : Enum<T>> nextInTurn(values: List<T>, last: T?): T {
        if (last == null) return values.first()
        return values[(values.indexOf(last) + 1) % values.size]
    }
}
