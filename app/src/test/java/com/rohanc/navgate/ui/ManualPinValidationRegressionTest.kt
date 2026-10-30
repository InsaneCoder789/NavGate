package com.rohanc.navgate.ui

import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.core.RoutePin
import org.junit.Assert.*
import org.junit.Test

class ManualPinValidationRegressionTest {
    @Test fun emptyFieldsHaveSpecificMessagesInsteadOfParserExceptions() {
        val errors = validateManualPin("", "", "", emptyList())
        assertEquals("Enter a label for this pin.", errors.label)
        assertEquals("Enter latitude.", errors.latitude)
        assertEquals("Enter longitude.", errors.longitude)
        assertFalse(errors.valid)
        assertNotNull(validateManualPin("x".repeat(121), "0", "0", emptyList()).label)
    }

    @Test fun nonFiniteAndOutOfRangeCoordinatesAreRejected() {
        for (value in listOf("NaN", "Infinity", "-Infinity", "abc", "90.0001", "-90.1")) {
            assertNotNull("Latitude $value", validateManualPin("A", value, "0", emptyList()).latitude)
        }
        for (value in listOf("NaN", "Infinity", "abc", "180.0001", "-180.01")) {
            assertNotNull("Longitude $value", validateManualPin("A", "0", value, emptyList()).longitude)
        }
        assertTrue(validateManualPin(" North pole ", "90", "-180", emptyList()).valid)
        assertTrue(validateManualPin(" South pole ", " -90 ", " 180 ", emptyList()).valid)
    }

    @Test fun duplicateConsecutivePointIsReportedAtThePinAndLimitIsEnforced() {
        val start = RoutePin("a", "Start", GeoPoint(19.0, 72.0))
        assertNotNull(validateManualPin("Next", "19", "72", listOf(start)).point)
        assertTrue(validateManualPin("Next", "19.001", "72", listOf(start)).valid)
        assertNotNull(validateManualPin("Next", "19.001", "72", List(100) { start }).point)
    }

    @Test fun editorBackReturnsToRoutesAndPinCancelReturnsToEditor() {
        fun back(screen: String) = frontendBack(screen, false, "Explore", "Explore", "Settings", "place")
        assertEquals("Routes", back("My routes"))
        assertEquals("My routes", back("ManualPin"))
        assertEquals("Routes", selectedFrontendTab("My routes"))
        assertEquals("Routes", selectedFrontendTab("ManualPin"))
    }
}
