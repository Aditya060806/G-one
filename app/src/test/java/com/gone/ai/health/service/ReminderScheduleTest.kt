package com.gone.ai.health.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderScheduleTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val window = ReminderSchedule.Window(9, 21)
    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 9, day, hour, minute, 0, 0, zone)

    @Test
    fun `during the day the next reminder is the next top of the hour`() {
        assertEquals(at(17, 11), ReminderSchedule.next(at(17, 10, 40), window))
        assertEquals(at(17, 11), ReminderSchedule.next(at(17, 10, 0), window))
    }

    @Test
    fun `the last hour of the window is included`() {
        assertEquals(at(17, 21), ReminderSchedule.next(at(17, 20, 30), window))
    }

    @Test
    fun `after the window the next reminder is tomorrow morning`() {
        assertEquals(at(18, 9), ReminderSchedule.next(at(17, 21, 5), window))
        assertEquals(at(18, 9), ReminderSchedule.next(at(17, 23, 59), window))
    }

    @Test
    fun `before the window the next reminder is this morning`() {
        assertEquals(at(17, 9), ReminderSchedule.next(at(17, 3, 0), window))
        assertEquals(at(17, 9), ReminderSchedule.next(at(17, 8, 15), window))
    }

    @Test
    fun `an all-day window wraps to midnight`() {
        val allDay = ReminderSchedule.Window(0, 23)
        assertEquals(at(18, 0), ReminderSchedule.next(at(17, 23, 10), allDay))
    }

    @Test
    fun `daylight saving changes keep reminders on the hour`() {
        val london = ZoneId.of("Europe/London")
        // Clocks go back at 02:00 on 25 October 2026; 08:30 that day is still followed by 09:00.
        val before = ZonedDateTime.of(2026, 10, 25, 8, 30, 0, 0, london)
        assertEquals(ZonedDateTime.of(2026, 10, 25, 9, 0, 0, 0, london), ReminderSchedule.next(before, window))
    }

    @Test
    fun `a late alarm outside the window is not shown`() {
        assertTrue(ReminderSchedule.shouldShow(at(17, 21, 40), window))
        assertFalse(ReminderSchedule.shouldShow(at(17, 22, 5), window))
        assertFalse(ReminderSchedule.shouldShow(at(17, 6, 0), window))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a window that ends before it starts is rejected`() {
        ReminderSchedule.Window(21, 9)
    }

    private enum class Kind { A, B, C }

    @Test
    fun `taking turns cycles through every reminder`() {
        val all = Kind.entries.toList()
        assertEquals(Kind.A, ReminderSchedule.nextInTurn(all, null))
        assertEquals(Kind.B, ReminderSchedule.nextInTurn(all, Kind.A))
        assertEquals(Kind.A, ReminderSchedule.nextInTurn(all, Kind.C))
    }
}
