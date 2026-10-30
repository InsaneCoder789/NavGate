package com.rohanc.navgate.ui.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusPresentationTest {
    @Test fun everyAlignmentMessageHasItsOwnWordsNotTheLightbulbOfTheDarkMessage() {
        val lightOnly = statusView("Too dark for AR: turn on more light").icon
        for (m in listOf("Use map guidance until location recovers", "Need heading lock", "Near maneuver; follow text instructions", "Turn slightly right", "Turn slightly left", "Rotate right to realign", "Rotate left to realign")) {
            val v = statusView(m)
            assertTrue("$m has a hint", v.hint.isNotBlank())
            assertTrue("$m shows the sun icon", v.icon != lightOnly)
        }
        assertEquals("Rotate left", statusView("Rotate left to realign").title)
        assertEquals("Location is weak", statusView("Use map guidance until location recovers").title)
    }

    @Test fun onlyWaitingStatesShowASpinner() {
        assertTrue(statusView("Aligning with the road: hold the phone up").spinner)
        assertTrue(statusView("Lining up with the road: walk").spinner)
        assertFalse(statusView("Rotate right to realign").spinner)
        assertFalse(statusView("Too dark for AR").spinner)
    }

    @Test fun aMessageThatBlinksNeverShows() {
        val s = StableStatus()
        var shown = 0
        // On for 300 ms, off for 300 ms, over and over: it never stands long enough.
        for (t in 0..6_000 step 100) { val raw = if ((t / 300) % 2 == 0) "Rotate left to realign" else null; if (s.update(t.toLong(), raw) != null) shown++ }
        assertEquals(0, shown)
    }

    @Test fun aSteadyMessageAppearsAfterAMomentAndStaysAtLeastASecondAndAHalf() {
        val s = StableStatus()
        assertNull(s.update(0, "Rotate left to realign")); assertNull(s.update(500, "Rotate left to realign"))
        assertEquals("Rotate left to realign", s.update(900, "Rotate left to realign"))
        // It goes away in the data at once, but the card holds for its minimum time and goes only once the message has really been gone.
        assertEquals("Rotate left to realign", s.update(1_000, null)); assertEquals("Rotate left to realign", s.update(2_300, null))
        assertNull(s.update(2_500, null))
    }

    @Test fun aDifferentMessageReplacesTheOneShownOnlyOnceItIsSteady() {
        val s = StableStatus()
        s.update(0, "Need heading lock"); s.update(900, "Need heading lock")
        assertEquals("Need heading lock", s.update(2_000, "Rotate right to realign"))
        assertEquals("Need heading lock", s.update(2_200, "Rotate right to realign"))
        assertEquals("Rotate right to realign", s.update(2_400, "Rotate right to realign"))
    }
}
