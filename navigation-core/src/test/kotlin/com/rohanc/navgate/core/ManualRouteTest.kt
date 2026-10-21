package com.rohanc.navgate.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ManualRouteTest {
    private val start=RoutePin("a","Gateway of India",GeoPoint(18.92188,72.83468))
    private val end=RoutePin("b","Marine Drive · समुद्र",GeoPoint(18.9341,72.82365))
    @Test fun `pins labels mode and order round trip without coordinate loss`() {
        for(mode in listOf("walking","driving")) {
            val route=ManualRoute("route","Mumbai waterfront",mode,listOf(start,end))
            assertEquals(route,Json.decodeFromString<ManualRoute>(Json.encodeToString(route)))
            // Independent Mumbai source endpoints ~1.79 km as the crow flies, not a road distance.
            assertTrue(route.straightLineMeters in 1750.0..1850.0)
            assertEquals(route.straightLineMeters,route.copy(pins=route.pins.reversed()).straightLineMeters,0.001)
        }
    }
    @Test fun `invalid points empty labels duplicate pins and malformed itineraries are rejected`() {
        assertThrows(IllegalArgumentException::class.java){start.copy(point=GeoPoint(Double.NaN,72.0))}
        assertThrows(IllegalArgumentException::class.java){start.copy(label=" ")}
        assertThrows(IllegalArgumentException::class.java){ManualRoute("r","x","flying",listOf(start,end))}
        assertThrows(IllegalArgumentException::class.java){ManualRoute("r","x","walking",listOf(start))}
        assertThrows(IllegalArgumentException::class.java){ManualRoute("r","x","walking",listOf(start,start))}
        assertThrows(IllegalArgumentException::class.java){ManualRoute("r","x","walking",listOf(start,start.copy(id="z")))}
    }
}
