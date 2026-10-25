package com.rohanc.navgate.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderBudgetTest {
    private fun ms(powerSave: Boolean = false, warm: Boolean = false, speed: Double = 10.0, driving: Boolean = true, camera: Boolean = false) =
        RenderBudget.intervalMillis(powerSave, warm, speed, driving, camera)

    @Test fun `driving on a charged cool phone redraws at full rate`() = assertEquals(16L, ms())
    @Test fun `walking and the camera views use half of that`() { assertEquals(33L, ms(driving = false, speed = 1.3)); assertEquals(33L, ms(camera = true)) }
    @Test fun `battery saver and a warm phone slow every view down`() {
        assertEquals(66L, ms(powerSave = true)); assertEquals(66L, ms(powerSave = true, camera = true)); assertEquals(50L, ms(warm = true))
    }
    @Test fun `standing still is a trickle whatever the mode`() { assertEquals(200L, ms(speed = 0.0)); assertEquals(200L, ms(speed = 0.2, camera = true)) }
    @Test fun `no setting is ever faster than the display or slower than a few updates a second`() {
        for (p in listOf(true, false)) for (w in listOf(true, false)) for (d in listOf(true, false)) for (c in listOf(true, false)) for (s in listOf(0.0, 1.0, 20.0))
            assertTrue(ms(p, w, s, d, c) in 16L..200L)
    }
}
