package com.rohanc.navgate.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionRateTest {
    @Test fun `ten updates a second only at driving speed`() = assertEquals(100L, MotionRate.intervalMillis(busy = true, speedMps = 12.0, powerSave = false))
    @Test fun `half that on foot and a trickle when not guiding`() {
        assertEquals(200L, MotionRate.intervalMillis(true, 1.3, false)); assertEquals(500L, MotionRate.intervalMillis(false, 12.0, false))
    }
    @Test fun `battery saver never goes faster than four a second`() {
        assertEquals(250L, MotionRate.intervalMillis(true, 12.0, true)); assertEquals(500L, MotionRate.intervalMillis(false, 0.0, true))
    }
}
