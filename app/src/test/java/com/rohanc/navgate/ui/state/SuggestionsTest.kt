package com.rohanc.navgate.ui.state

import com.rohanc.navgate.model.RouteHistoryEntry
import com.rohanc.navgate.model.TravelProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class SuggestionsTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
    private fun trip(id: String, title: String, day: Int, hour: Int, status: String = "Arrived", mode: TravelProfile = TravelProfile.Walking, minute: Int = 0) =
        RouteHistoryEntry(id, title, mode, at(day, hour, minute), 900.0, tripId = "t$day$hour$id", status = status)

    @Test fun `a place reached on two days around this time is suggested`() {
        val s = suggestFor(listOf(trip("kp5", "KP-5", 27, 18), trip("kp5", "KP-5", 28, 18, ), trip("mall", "Mall", 28, 11)), at(29, 18, 20), zone)!!
        assertEquals("KP-5", s.title); assertEquals(TravelProfile.Walking, s.mode); assertEquals(2, s.tripsAtThisTime)
    }

    @Test fun `one trip is not a habit`() { assertNull(suggestFor(listOf(trip("kp5", "KP-5", 28, 18)), at(29, 18), zone)) }

    @Test fun `two trips on the same day are one habit day, not two`() { assertNull(suggestFor(listOf(trip("kp5", "KP-5", 28, 18), trip("kp5", "KP-5", 28, 18)), at(29, 18), zone)) }

    @Test fun `trips at another time of day do not count`() { assertNull(suggestFor(listOf(trip("kp5", "KP-5", 27, 8), trip("kp5", "KP-5", 28, 8)), at(29, 18), zone)) }

    @Test fun `stopped and interrupted trips do not count`() {
        assertNull(suggestFor(listOf(trip("kp5", "KP-5", 27, 18, "Stopped"), trip("kp5", "KP-5", 28, 18, "Interrupted")), at(29, 18), zone))
    }

    @Test fun `the time window wraps over midnight`() {
        val s = suggestFor(listOf(trip("late", "Late spot", 27, 23, mode = TravelProfile.Driving, minute = 30), trip("late", "Late spot", 28, 23, mode = TravelProfile.Driving, minute = 30)), LocalDateTime.of(2026, 10, 29, 0, 10).atZone(zone).toInstant().toEpochMilli(), zone)
        assertEquals("Late spot", s!!.title)
    }

    @Test fun `walking and driving to the same place are separate habits`() {
        assertNull(suggestFor(listOf(trip("kp5", "KP-5", 27, 18), trip("kp5", "KP-5", 28, 18, mode = TravelProfile.Driving)), at(29, 18), zone))
    }
}
