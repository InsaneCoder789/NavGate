package com.rohanc.navgate.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderBudgetTest {
    private fun ms(powerSave: Boolean = false, thermal: Int = 0, speed: Double = 10.0, driving: Boolean = true, camera: Boolean = false) =
        RenderBudget.intervalMillis(powerSave, thermal, speed, driving, camera)

    @Test fun `driving on a charged cool phone redraws at every display frame`() = assertEquals(0L, ms())
    @Test fun `walking and the camera views are as smooth as driving`() { assertEquals(0L, ms(driving = false, speed = 1.3)); assertEquals(0L, ms(camera = true)) }
    @Test fun `battery saver and a warm phone still draw at 60 Hz`() {
        assertEquals(16L, ms(powerSave = true)); assertEquals(16L, ms(powerSave = true, camera = true)); assertEquals(16L, ms(thermal = 2))
    }
    @Test fun `standing still is 30 Hz so the zoom when guidance starts stays smooth`() { assertEquals(33L, ms(speed = 0.0)); assertEquals(33L, ms(speed = 0.2, camera = true)) }
    @Test fun `a hot phone eases off in steps and a critical one runs at 30 Hz`() {
        assertEquals(22L, ms(thermal = 3)); assertEquals(33L, ms(thermal = 4)); assertEquals(33L, ms(thermal = 6)); assertEquals(0L, ms(thermal = 1))
    }
    @Test fun `no setting is ever slower than 30 updates a second`() {
        for (p in listOf(true, false)) for (w in listOf(0, 2, 3, 4)) for (d in listOf(true, false)) for (c in listOf(true, false)) for (s in listOf(0.0, 1.0, 20.0))
            assertTrue(ms(p, w, s, d, c) in 0L..33L)
    }
}
