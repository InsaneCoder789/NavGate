package com.rohanc.navgate.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KiitAreaTest {
    @Test fun `the campuses and their surroundings are inside`() {
        for (p in listOf(GeoPoint(20.3527, 85.8200) /* campus 6 */, GeoPoint(20.3470, 85.8240) /* campus 1 */, GeoPoint(20.3560, 85.8268) /* Patia square */, GeoPoint(20.3430, 85.8063) /* Infocity Road */))
            assertTrue("$p", KiitArea.contains(p))
    }
    @Test fun `the rest of Bhubaneswar and everywhere else is outside`() {
        assertFalse(KiitArea.contains(GeoPoint(20.2384, 85.8340)))   // Lingaraj Temple, old town
        assertFalse(KiitArea.contains(GeoPoint(20.2961, 85.8245)))   // city centre
        assertFalse(KiitArea.contains(GeoPoint(19.0760, 72.8777)))   // Mumbai
        assertFalse(KiitArea.contains(null))
    }
}
