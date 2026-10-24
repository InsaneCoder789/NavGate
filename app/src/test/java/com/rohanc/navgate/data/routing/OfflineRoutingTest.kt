package com.rohanc.navgate.data.routing

import com.rohanc.navgate.core.*
import com.rohanc.navgate.data.routing.offline.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos

class OfflineRoutingTest {
    private val o = GeoPoint(19.0, 72.8)
    private fun at(north: Double, east: Double) = GeoPoint(o.latitude + north / 111_320.0, o.longitude + east / (111_320.0 * cos(Math.toRadians(o.latitude))))
    private fun line(vararg p: Pair<Double, Double>): List<GeoPoint> {
        val out = ArrayList<GeoPoint>()
        p.toList().zipWithNext().forEach { (a, b) -> val n = maxOf(1, (Math.hypot(b.first - a.first, b.second - a.second) / 8).toInt()); for (i in 0 until n) out += at(a.first + (b.first - a.first) * i / n, a.second + (b.second - a.second) * i / n) }
        out += at(p.last().first, p.last().second); return out
    }
    private val shape = line(0.0 to 0.0, 600.0 to 0.0, 600.0 to 900.0)
    private val request = PlanRequest(7, "mumbai", shape.first(), "dest", profile = "driving", destinationPoint = shape.last(), destinationTitle = "Dock", routingSource = "valhalla")

    @Test fun `an offline track becomes a valid followable route`() {
        val route = OfflineRouteBuilder.build(request, OfflineTrack(shape, 240.0), 1_000L)
        assertEquals(emptyList<String>(), RouteIntegrity.shapeErrors(route))
        assertEquals("brouter", route.source); assertTrue(route.external)
        assertEquals(listOf("Start", "Right", "Arrive"), route.maneuvers.map { it.kind })
        assertEquals(240.0, route.estimatedSeconds, 1e-9)
        assertEquals(route.geometry.size, route.shapeElapsedSeconds.size)
        assertTrue(route.warnings.first().startsWith("Offline route"))
    }

    @Test fun `a route that starts away from the pin says so`() {
        val far = request.copy(origin = at(-300.0, 0.0))
        assertTrue(OfflineRouteBuilder.build(far, OfflineTrack(shape, 240.0), 1L).warnings.any { it.contains("starts") })
    }

    @Test fun `the guidance session can ride an offline route to arrival`() {
        val route = OfflineRouteBuilder.build(request, OfflineTrack(shape, 240.0), 1L)
        val tracker = RouteTracker(RouteGeometry(route.geometry))
        var last = tracker.update(route.geometry.first(), 5.0, 10.0, 0)
        for (s in 1..150) last = tracker.update(RouteGeometry(route.geometry).pointAt(s * 10.0), 5.0, 10.0, s * 1000L)
        assertTrue(last.alongMeters > route.distanceMeters - 20)
    }

    private class Fake(val result: () -> RouteResult, val alt: List<CampusRoute> = emptyList()) : RoutingProvider {
        var calls = 0
        override suspend fun plan(request: PlanRequest, pinnedVersion: String?): RouteResult { calls++; return result() }
        override suspend fun alternatives(request: PlanRequest) = alt
    }
    private fun ready(): RouteResult = RouteResult.Ready(OfflineRouteBuilder.build(request, OfflineTrack(shape, 240.0), 1L).copy(source = "valhalla", provider = "valhalla-test"))
    private val down = RouteResult.Unavailable(RouteFailure.NoConnection, "No connection")

    private fun offline(installed: Boolean, result: RouteResult? = null): OfflineRoutingProvider {
        val data = object : OfflineStub(installed) {}
        return data.provider(result ?: RouteResult.Ready(OfflineRouteBuilder.build(request, OfflineTrack(shape, 240.0), 1L)))
    }

    @Test fun `online result wins when the server answers`() = runBlocking {
        val primary = Fake(::ready); val off = offline(true)
        val r = FailoverRoutingProvider(primary, off, online = { true }).plan(request) as RouteResult.Ready
        assertEquals("valhalla", r.route.source)
    }

    @Test fun `a failing server falls back to the phone when tiles are installed`() = runBlocking {
        val r = FailoverRoutingProvider(Fake({ down }), offline(true), online = { true }).plan(request)
        assertEquals("brouter", (r as RouteResult.Ready).route.source)
    }

    @Test fun `with no network the phone answers first and the server is never asked`() = runBlocking {
        val primary = Fake(::ready)
        val r = FailoverRoutingProvider(primary, offline(true), online = { false }).plan(request) as RouteResult.Ready
        assertEquals("brouter", r.route.source); assertEquals(0, primary.calls)
    }

    @Test fun `without tiles the online failure is what the user sees`() = runBlocking {
        val r = FailoverRoutingProvider(Fake({ down }), offline(false), online = { true }).plan(request)
        assertEquals(down, r)
    }

    @Test fun `a thrown network error also falls back`() = runBlocking {
        val r = FailoverRoutingProvider(Fake({ throw java.io.IOException("boom") }), offline(true), online = { true }).plan(request)
        assertTrue(r is RouteResult.Ready)
    }

    @Test fun `alternatives fall back to the single offline route`() = runBlocking {
        val alts = FailoverRoutingProvider(Fake(::ready), offline(true), online = { false }).alternatives(request)
        assertEquals(1, alts.size); assertEquals("brouter", alts.single().source)
    }
}

/** Builds an OfflineRoutingProvider whose tiles are present or absent and whose engine is replaced by a canned answer. */
open class OfflineStub(private val installed: Boolean) {
    fun provider(result: RouteResult): OfflineRoutingProvider = OfflineRoutingProvider.Companion.forTesting(installed, result)
}
