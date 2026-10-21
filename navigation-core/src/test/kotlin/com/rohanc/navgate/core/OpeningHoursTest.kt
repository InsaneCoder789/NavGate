package com.rohanc.navgate.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class OpeningHoursTest {
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, 19 + day, hour, minute) // 19 Oct 2026 is a Monday

    @Test fun `shop open on weekdays and closed on sunday`() {
        val hours = "Mo-Sa 09:00-21:00; Su off"
        assertEquals(OpenStatus(true, "Open · Closes 21:00"), OpeningHours.status(hours, at(2, 12)))
        // Saturday night: Sunday is closed, so the next opening is Monday.
        assertEquals(OpenStatus(false, "Closed · Opens 09:00 Mon"), OpeningHours.status(hours, at(5, 22)))
        assertEquals(OpenStatus(false, "Closed · Opens 09:00 tomorrow"), OpeningHours.status(hours, at(6, 12)))
    }

    @Test fun `closed before opening reports todays opening time`() {
        assertEquals(OpenStatus(false, "Closed · Opens 10:00"), OpeningHours.status("Mo-Su 10:00-23:00", at(0, 7)))
    }

    @Test fun `split shifts and a lunch break`() {
        val hours = "Mo-Fr 09:00-13:00,14:00-18:00"
        assertTrue(OpeningHours.status(hours, at(1, 10))!!.open)
        assertEquals("Closed · Opens 14:00", OpeningHours.status(hours, at(1, 13, 30))!!.label)
    }

    @Test fun `hours past midnight keep a place open into the next morning`() {
        val hours = "Mo-Su 18:00-02:00"
        assertEquals(OpenStatus(true, "Open · Closes 02:00"), OpeningHours.status(hours, at(1, 1, 0)))
        assertEquals("Open · Closes 02:00", OpeningHours.status(hours, at(1, 23, 0))!!.label)
        assertFalse(OpeningHours.status(hours, at(1, 3, 0))!!.open)
    }

    @Test fun `always open and later rules override earlier ones`() {
        assertEquals(OpenStatus(true, "Open 24 hours"), OpeningHours.status("24/7", at(3, 4)))
        val hours = "Mo-Su 08:00-20:00; Sa 10:00-14:00"
        assertEquals("Open · Closes 14:00", OpeningHours.status(hours, at(5, 11))!!.label)
        assertFalse(OpeningHours.status(hours, at(5, 15))!!.open)
    }

    @Test fun `unsupported syntax is not guessed`() {
        assertNull(OpeningHours.status("Mo-Fr 09:00-17:00; PH off", at(0, 10)))
        assertNull(OpeningHours.status("sunrise-sunset", at(0, 10)))
        assertNull(OpeningHours.status("", at(0, 10)))
        assertNull(OpeningHours.status(null, at(0, 10)))
    }
}
