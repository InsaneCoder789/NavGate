package com.rohanc.navgate.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CityModeTest {
    @Test fun `there are exactly two regions and they say what they do`() {
        assertEquals(listOf("PAN INDIA", "KIIT"), CityMode.entries.map { it.label })
        assertEquals(listOf("india", "kiit"), CityMode.entries.map { it.regionId })
        assert(CityMode.entries.all { it.summary.length > 30 })
    }
    @Test fun `what an older version stored still opens the right region`() {
        assertEquals(CityMode.Kiit, CityMode.fromStored("KiitBeta")); assertEquals(CityMode.Kiit, CityMode.fromStored("Kiit"))
        assertEquals(CityMode.PanIndia, CityMode.fromStored("Mumbai")); assertEquals(CityMode.PanIndia, CityMode.fromStored("PanIndia"))
        assertNull(CityMode.fromStored("Atlantis")); assertNull(CityMode.fromStored(null))
    }
}
