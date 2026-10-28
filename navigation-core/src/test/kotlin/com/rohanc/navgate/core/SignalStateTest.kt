package com.rohanc.navgate.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SignalStateTest {
    @Test fun `no fix yet is searching`() { assertEquals(SignalGrade.Searching, SignalState.from(null, null, false).grade) }

    @Test fun `accuracy bands`() {
        assertEquals(SignalGrade.Strong, SignalState.from(5.0, 0.5, true).grade)
        assertEquals(SignalGrade.Strong, SignalState.from(8.0, 0.5, true).grade)
        assertEquals(SignalGrade.Fair, SignalState.from(8.1, 0.5, true).grade)
        assertEquals(SignalGrade.Fair, SignalState.from(20.0, 0.5, true).grade)
        assertEquals(SignalGrade.Weak, SignalState.from(20.1, 0.5, true).grade)
        assertEquals(SignalGrade.Weak, SignalState.from(null, 0.5, true).grade)
    }

    @Test fun `while guiding three seconds of silence is a lost signal`() {
        assertEquals(SignalGrade.Strong, SignalState.from(5.0, 3.0, true).grade)
        assertEquals(SignalGrade.Lost, SignalState.from(5.0, 3.1, true).grade)
    }

    @Test fun `browsing tolerates a longer gap before calling it lost`() {
        assertEquals(SignalGrade.Strong, SignalState.from(5.0, 9.0, false).grade)
        assertEquals(SignalGrade.Lost, SignalState.from(5.0, 10.5, false).grade)
    }

    @Test fun `the figures are carried for the screens`() {
        val s = SignalState.from(12.0, 4.7, true, rejectedJumps = 2)
        assertEquals(4, s.secondsSinceFix); assertEquals(2, s.rejectedJumps); assertEquals(12.0, s.accuracyMeters!!, 0.0)
    }

    @Test fun `garbage inputs do not crash and do not look strong`() {
        assertEquals(SignalGrade.Weak, SignalState.from(Double.NaN, 1.0, true).grade)
        assertEquals(SignalGrade.Searching, SignalState.from(5.0, Double.NaN, true).grade)
    }
}
