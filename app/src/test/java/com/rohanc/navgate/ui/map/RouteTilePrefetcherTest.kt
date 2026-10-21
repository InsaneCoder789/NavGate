package com.rohanc.navgate.ui.map

import com.rohanc.navgate.core.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteTilePrefetcherTest {
    private fun north(meters: Double) = GeoPoint(19.0 + meters / 111_320.0, 72.85)

    @Test fun longRouteBecomesContiguousBoxesCoveringTheRoad() {
        val route = (0..100).map { north(it * 100.0) }               // 10 km straight north
        val boxes = RouteTilePrefetcher.corridorBoxes(route)
        assertTrue(boxes.size in 3..5, "about one box per 2.5 km, got ${boxes.size}")
        // Every route point lies inside some box.
        route.forEach { p -> assertTrue(boxes.any { p.latitude in it[0]..it[2] && p.longitude in it[1]..it[3] }) }
        // Neighbouring boxes overlap or touch: no gap where the map would go blank.
        boxes.zipWithNext().forEach { (a, b) -> assertTrue(b[0] <= a[2] + 1e-9) }
    }

    @Test fun shortRouteIsOneBox() = assertEquals(1, RouteTilePrefetcher.corridorBoxes(listOf(north(0.0), north(800.0))).size)
}
