package com.rohanc.navgate.nav

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceNoteTest {
    @Test fun distancesReadNaturally() {
        assertEquals("0 m", GuidanceNote.distanceLabel(0.0))
        assertEquals("0 m", GuidanceNote.distanceLabel(-4.0))
        assertEquals("80 m", GuidanceNote.distanceLabel(81.0))
        assertEquals("120 m", GuidanceNote.distanceLabel(118.0))
        assertEquals("1.1 km", GuidanceNote.distanceLabel(1_140.0))
        assertEquals("12 km", GuidanceNote.distanceLabel(12_300.0))
    }

    @Test fun cardCarriesTurnChipAndTrip() {
        val note = GuidanceNote.of("Turn left onto KIIT Road", 118.0, 600.0, 1_100.0, LocalTime.of(13, 4))
        assertEquals("In 120 m", note.title)
        assertEquals("120 m", note.chip)
        assertEquals("Turn left onto KIIT Road", note.instruction)
        assertEquals("Arrive 1:14 pm · 1.1 km", note.trip)
    }

    @Test fun progressCountsTheMetresAlreadyCovered() {
        val note = GuidanceNote.of("Turn left", 118.0, 600.0, 400.0, LocalTime.NOON, routeMeters = 1_000.0)
        assertEquals(600, note.progressDone)
        assertEquals(1_000, note.progressTotal)
        val unknown = GuidanceNote.of("Turn left", 118.0, 600.0, 400.0, LocalTime.NOON)
        assertEquals(0, unknown.progressTotal)
        assertEquals(0, GuidanceNote.of("x", 1.0, 1.0, 1_500.0, LocalTime.NOON, routeMeters = 1_000.0).progressDone)
    }

    @Test fun atTheTurnItSaysNow() {
        val note = GuidanceNote.of("Turn right", 6.0, 0.0, 0.0, LocalTime.NOON)
        assertEquals("Now", note.title)
        assertEquals("Now", note.chip)
        assertEquals("", note.trip)
    }

    @Test fun floatingWindowOnlyDuringATripOnTheNavigationScreen() {
        assertTrue(pipEligible(true, "Navigation"))
        assertFalse(pipEligible(false, "Navigation"))
        assertFalse(pipEligible(true, "Camera"))
        assertFalse(pipEligible(true, "Directions"))
    }
}
