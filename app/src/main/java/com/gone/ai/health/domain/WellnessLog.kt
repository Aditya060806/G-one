package com.gone.ai.health.domain

import java.time.LocalDate

/** Explicit user reports, never inferred from a pulse, an EMG value, or missing packets. */
data class SleepLog(val wakeDate: LocalDate, val bedtimeMinute: Int, val wakeMinute: Int, val awakeMinutes: Int, val quality: Int) {
    val inBedMinutes: Int get() = (wakeMinute - bedtimeMinute + 1440) % 1440
    val sleepMinutes: Int get() = inBedMinutes - awakeMinutes
    fun validate(today: LocalDate) {
        require(!wakeDate.isAfter(today)) { "Choose today or an earlier wake-up date." }
        require(bedtimeMinute in 0..1439 && wakeMinute in 0..1439) { "Enter times as HH:mm (24-hour clock)." }
        require(inBedMinutes > 0) { "Bedtime and wake time must differ." }
        require(awakeMinutes in 0 until inBedMinutes) { "Awake time must be shorter than time in bed." }
        require(quality in 1..5) { "Choose a sleep-quality rating from 1 to 5." }
    }
}
data class StressLog(val date: LocalDate, val level: Int) {
    fun validate(today: LocalDate) {
        require(!date.isAfter(today)) { "Choose today or an earlier date." }
        require(level in 1..5) { "Choose a stress rating from 1 to 5." }
    }
}

fun parseClockMinute(text: String): Int {
    require(Regex("\\d{2}:\\d{2}").matches(text)) { "Use HH:mm, for example 23:00." }
    val hour = text.substring(0, 2).toInt()
    val minute = text.substring(3).toInt()
    require(hour in 0..23 && minute in 0..59) { "Enter a valid 24-hour time." }
    return hour * 60 + minute
}

fun sleepAverageLastWeek(logs: List<SleepLog>, today: LocalDate): Int? = logs
    .filter { it.wakeDate in today.minusDays(6)..today }
    .takeIf { it.isNotEmpty() }?.map { it.sleepMinutes }?.average()?.toInt()
