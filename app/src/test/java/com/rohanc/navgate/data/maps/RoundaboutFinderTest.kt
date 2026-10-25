package com.rohanc.navgate.data.maps

import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.core.offset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses the real OpenFreeMap tile over Kandivali East (z14, x 11508, y 7301), so the finder is checked against actual road data. */
class RoundaboutFinderTest {
    private val tile = javaClass.getResourceAsStream("/maps/kandivali-z14-11508-7301.pbf")!!.readBytes()
    private val finder = RoundaboutFinder { z, x, y -> if (z == 14 && x == 11508 && y == 7301) tile else null }
    private val centre = GeoPoint(19.197055, 72.872471)

    private fun routeThrough(from: Double, to: Double) = (-6..6).map { i -> centre.offset(i * 10.0, 0.0).let { GeoPoint(it.latitude, it.longitude) } }
        .let { _ -> (6 downTo 1).map { centre.offset(it * 10.0, from) } + centre + (1..6).map { centre.offset(it * 10.0, to) } }

    @Test fun `finds the mini roundabout on the route and the roads that meet it`() {
        val rings = runBlocking { finder.near(routeThrough(268.0, 14.0)) }
        assertEquals(1, rings.size)
        val ring = rings.single()
        assertTrue("centre ${ring.center}", ring.center.distanceTo(centre) < 6.0)
        assertTrue("radius ${ring.radiusMeters}", ring.radiusMeters in 5.0..14.0)
        assertTrue("arms ${ring.armBearings}", ring.armBearings.size >= 3)
    }

    @Test fun `a route far from any roundabout finds none`() {
        val far = (0..10).map { GeoPoint(19.2010 + it * 0.0002, 72.8600) }
        assertTrue(runBlocking { finder.near(far) }.isEmpty())
    }

    @Test fun `without tiles nothing is found and nothing breaks`() {
        val none = RoundaboutFinder { _, _, _ -> null }
        assertTrue(runBlocking { none.near(routeThrough(268.0, 14.0)) }.isEmpty())
    }

    @Test fun `a ring stored in three pieces is still found, with the roads that meet it`() {
        val c = GeoPoint(19.2, 72.87)
        fun at(deg: Double, r: Double) = c.offset(r, deg)
        fun arc(from: Int, to: Int) = (from..to step 15).map { at(it.toDouble(), 14.0) }
        val ring = listOf(MvtLine(arc(0, 120), mapOf("class" to "tertiary")), MvtLine(arc(120, 240), mapOf("class" to "tertiary")), MvtLine(arc(240, 360), mapOf("class" to "tertiary")))
        val arms = listOf(0.0, 100.0, 200.0, 300.0).map { b -> MvtLine((0..4).map { c.offset(14.0 + it * 12.0, b) }, mapOf("class" to "tertiary")) }
        val route = (-6..6).map { c.offset(6.0, 90.0).offset(it * 10.0, 0.0) }
        val rings = finder.rings(ring + arms, route)
        assertEquals(1, rings.size)
        assertEquals(14.0, rings.single().radiusMeters, 1.5)
        assertEquals(4, rings.single().armBearings.size)
    }
}
