package com.gone.ai.health.domain

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class WellnessLogTest {
    private val today = LocalDate.of(2026, 9, 19)
    @Test fun `overnight sleep subtracts reported awake time`() {
        val log = SleepLog(today, parseClockMinute("23:00"), parseClockMinute("07:00"), 30, 4)
        log.validate(today)
        assertEquals(450, log.sleepMinutes)
    }
    @Test fun `daytime sleep works for shift workers`() {
        val log = SleepLog(today, parseClockMinute("09:00"), parseClockMinute("16:30"), 0, 3)
        log.validate(today)
        assertEquals(450, log.sleepMinutes)
    }
    @Test(expected = IllegalArgumentException::class) fun `invalid time rejected`() { parseClockMinute("25:00") }
    @Test(expected = IllegalArgumentException::class) fun `ambiguous equal times rejected`() { SleepLog(today, 60, 60, 0, 3).validate(today) }
    @Test(expected = IllegalArgumentException::class) fun `awake time cannot exceed time in bed`() { SleepLog(today, 0, 60, 90, 3).validate(today) }
    @Test(expected = IllegalArgumentException::class) fun `future stress entry rejected`() { StressLog(today.plusDays(1), 3).validate(today) }
    @Test(expected = IllegalArgumentException::class) fun `stress outside self report scale rejected`() { StressLog(today, 6).validate(today) }
    @Test fun `missing nights are not averaged as zero and old data is excluded`() {
        val records = listOf(SleepLog(today, 0, 480, 0, 4), SleepLog(today.minusDays(2), 0, 360, 0, 3), SleepLog(today.minusDays(7), 0, 60, 0, 3))
        assertEquals(420, sleepAverageLastWeek(records, today))
        assertNull(sleepAverageLastWeek(emptyList(), today))
    }
}
