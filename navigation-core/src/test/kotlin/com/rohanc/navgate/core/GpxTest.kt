package com.rohanc.navgate.core

import org.junit.Assert.*
import org.junit.Test

class GpxTest {
    @Test fun `stops become waypoints and a route with escaped names`() {
        val xml = Gpx.write("Tea & <Temples>", listOf("Start" to GeoPoint(19.0, 72.8), "R&D \"Lab\"" to GeoPoint(19.01, 72.81)))
        assertTrue(xml.contains("""<wpt lat="19.000000" lon="72.800000">"""))
        assertEquals(2, Regex("<rtept ").findAll(xml).count())
        assertTrue(xml.contains("Tea &amp; &lt;Temples&gt;") && xml.contains("R&amp;D &quot;Lab&quot;"))
        assertFalse(xml.contains("<trk>"))
    }

    @Test fun `a drawn road line is written as a track`() {
        val xml = Gpx.write("Trip", listOf("A" to GeoPoint(1.0, 2.0), "B" to GeoPoint(1.1, 2.1)), (0..5).map { GeoPoint(1.0 + it * 0.02, 2.0) })
        assertEquals(6, Regex("<trkpt ").findAll(xml).count())
    }
}
