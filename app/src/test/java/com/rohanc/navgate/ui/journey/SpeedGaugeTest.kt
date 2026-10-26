package com.rohanc.navgate.ui.journey

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class SpeedGaugeTest {
    @Test fun `the colour follows the speed band`() {
        assertEquals(Color(0xFF5B9BFF), gaugeColor(30.0)); assertEquals(Color(0xFF38D0F8), gaugeColor(100.0))
        assertEquals(Color(0xFFFFB020), gaugeColor(150.0)); assertEquals(Color(0xFFFF4D4D), gaugeColor(250.0))
    }
    @Test fun `the scale covers 250 km per hour with room to spare`() = assertTrue(GAUGE_MAX_KMH > 250.0)
}
