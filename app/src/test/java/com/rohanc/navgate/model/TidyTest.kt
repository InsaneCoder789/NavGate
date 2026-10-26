package com.rohanc.navgate.model

import com.rohanc.navgate.ui.journey.tripStatusLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class TidyTest {
    @Test fun `literal backslash n and real line breaks become one space`() {
        assertEquals("Dlh Orchid Wing-A, 40-B, 1st Cross Rd", "\\nDlh Orchid Wing-A,\n  40-B,   1st Cross Rd\\n".tidy())
        assertEquals("a b c", "a\\r\\nb\r\nc".tidy())
    }
    @Test fun `plain text is unchanged`() = assertEquals("Shri Ganesh Mandir", "Shri Ganesh Mandir".tidy())
    @Test fun `trip states are described in words`() {
        assertEquals("Arrived", tripStatusLabel("Arrived")); assertEquals("Ended early", tripStatusLabel("Stopped")); assertEquals("Interrupted", tripStatusLabel("SignalLimited"))
    }
}
