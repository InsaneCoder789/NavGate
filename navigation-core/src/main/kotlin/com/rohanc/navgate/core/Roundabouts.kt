package com.rohanc.navgate.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** A roundabout found in the map: its centre, mean radius, and the bearings (from the centre) of the roads that meet it. */
class RoundaboutRing(val center: GeoPoint, val radiusMeters: Double, val armBearings: List<Double>)

/**
 * Walking and driving routers describe a roundabout as two ordinary corners and a line cut across the middle of it. This puts it back
 * the way it is on the ground: the line follows the ring from the road you enter on to the road you leave on, and the two corners become
 * one step, "At the roundabout, take the 2nd exit onto X".
 */
object RoundaboutRewriter {
    private const val STEP_DEGREES = 12.0
    /** How far outside the ring a route still counts as using it (the footpath or approach lies a little outside the ring road). */
    private fun reach(r: RoundaboutRing) = r.radiusMeters + max(8.0, r.radiusMeters * 0.6)

    fun apply(route: CampusRoute, rings: List<RoundaboutRing>): CampusRoute {
        var current = route
        // Later rings first, so earlier distances stay valid while the route is rewritten.
        for (ring in rings.sortedByDescending { r -> route.geometry.indexOfFirst { it.distanceTo(r.center) <= reach(r) } }) {
            current = rewriteOne(current, ring) ?: current
        }
        return current
    }

    private fun rewriteOne(route: CampusRoute, ring: RoundaboutRing): CampusRoute? {
        val g = route.geometry
        val reach = reach(ring)
        val first = g.indexOfFirst { it.distanceTo(ring.center) <= reach }
        if (first < 0) return null
        var last = first
        while (last + 1 < g.size && g[last + 1].distanceTo(ring.center) <= reach) last++
        // Where the line enters and leaves the zone around the ring (interpolated between vertices).
        val entry = crossing(g, first - 1, first, ring.center, reach) ?: g[first]
        val exit = crossing(g, last + 1, last, ring.center, reach) ?: g[last]
        if (first == 0 || last == g.lastIndex) return null          // starts or ends inside the roundabout: nothing to guide through
        val entryAngle = ring.center.bearingTo(entry); val exitAngle = ring.center.bearingTo(exit)
        val sweep = signedSweep(entryAngle, exitAngle)
        // It must really be taken: the route has to use a different arm to leave by, or pass close to the middle.
        val closest = (first..last).minOf { g[it].distanceTo(ring.center) }
        if (abs(sweep) < 25.0 && closest > ring.radiusMeters * 0.9) return null
        if (abs(sweep) < 8.0) return null

        val steps = max(1, (abs(sweep) / STEP_DEGREES).roundToInt())
        val arc = (0..steps).map { ring.center.offset(ring.radiusMeters, entryAngle + sweep * it / steps) }
        val newGeometry = g.subList(0, first) + entry + arc + exit + g.subList(last + 1, g.size)

        val oldLength = g.lengthMeters(); val newLength = newGeometry.lengthMeters()
        val delta = newLength - oldLength
        val entryAlong = g.subList(0, first).lengthMeters() + (g.getOrNull(first - 1)?.distanceTo(entry) ?: 0.0)
        val exitAlongOld = g.subList(0, last + 1).lengthMeters() + g[last].distanceTo(exit)

        val exitNumber = exitNumber(ring, entryAngle, exitAngle, sweep)
        val after = route.maneuvers.firstOrNull { it.alongMeters > route.shapeToRouteMeters(exitAlongOld) + 0.5 }
        val inside = route.maneuvers.filter { it.kind != "Arrive" && it.kind != "Start" && it.target.distanceTo(ring.center) <= reach + 6.0 }
        if (inside.isEmpty()) return null
        val road = (inside.mapNotNull { roadName(it.instruction) } + listOfNotNull(after?.takeIf { it.kind != "Arrive" }?.let { roadName(it.instruction) })).lastOrNull()
        val text = buildString {
            append("At the roundabout, take the ")
            append(exitNumber?.let { ManeuverKinds.ordinal(it) + " exit" } ?: "exit")
            if (road != null) append(" onto ").append(road)
        }
        val merged = Maneuver(text, ring.center.offset(ring.radiusMeters, entryAngle), route.shapeToRouteMeters(entryAlong), "Roundabout", exitNumber, sweep)
        val shift = if (route.geometry.lengthMeters() > 0) delta / g.lengthMeters() * route.distanceMeters else 0.0
        val rebuilt = ArrayList<Maneuver>()
        var placed = false
        for (m in route.maneuvers) {
            if (m in inside) { if (!placed) { rebuilt += merged; placed = true }; continue }
            if (m.alongMeters >= merged.alongMeters && !placed) { rebuilt += merged; placed = true }
            rebuilt += if (m.alongMeters > merged.alongMeters) m.copy(alongMeters = (m.alongMeters + shift).coerceAtLeast(merged.alongMeters)) else m
        }
        val newDistance = (route.distanceMeters + shift).coerceAtLeast(1.0)
        val times = if (route.shapeElapsedSeconds.size == g.size) {
            val et = route.shapeElapsedSeconds
            val t0 = et[first - 1] + (et[first] - et[first - 1]) * fractionAlong(g, first - 1, first, entry)
            val t1 = et[last] + (et[minOf(last + 1, g.lastIndex)] - et[last]) * fractionAlong(g, last, minOf(last + 1, g.lastIndex), exit)
            // Time inside the roundabout is spread over the new arc by length; everything after it keeps its own offset.
            val inner = ArrayList<Double>(); var run = 0.0; val arcPts = listOf(entry) + arc + exit
            arcPts.forEachIndexed { i, p -> if (i > 0) run += arcPts[i - 1].distanceTo(p); inner += t0 + (t1 - t0) * (if (run <= 0) 0.0 else run / arcPts.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }) }
            et.subList(0, first) + inner + et.subList(last + 1, et.size)
        } else emptyList()
        val duration = times.lastOrNull() ?: route.estimatedSeconds
        return route.copy(geometry = newGeometry, distanceMeters = newDistance, estimatedSeconds = max(duration, 0.0), shapeElapsedSeconds = times, maneuvers = rebuilt)
    }

    /** The point where the segment [a]-[b] crosses the circle of [radius] around [center] (a is outside, b inside). */
    private fun crossing(g: List<GeoPoint>, outside: Int, inside: Int, center: GeoPoint, radius: Double): GeoPoint? {
        val a = g.getOrNull(outside) ?: return null; val b = g[inside]
        val da = a.distanceTo(center); val db = b.distanceTo(center)
        if (da <= radius || db > radius || da == db) return null
        val f = ((da - radius) / (da - db)).coerceIn(0.0, 1.0)
        return GeoPoint(a.latitude + (b.latitude - a.latitude) * f, a.longitude + (b.longitude - a.longitude) * f)
    }
    private fun fractionAlong(g: List<GeoPoint>, from: Int, to: Int, p: GeoPoint): Double {
        val total = g[from].distanceTo(g[to]); return if (total <= 0) 0.0 else (g[from].distanceTo(p) / total).coerceIn(0.0, 1.0)
    }

    /** Degrees to turn from [from] to [to], the shorter way round, positive clockwise (to the right). */
    private fun signedSweep(from: Double, to: Double): Double = ((to - from + 540.0) % 360.0) - 180.0

    /** Which exit the route leaves by, counting the roads that meet the ring in the direction it travels, not counting the one it came in on. */
    private fun exitNumber(ring: RoundaboutRing, entryAngle: Double, exitAngle: Double, sweep: Double): Int? {
        if (ring.armBearings.size < 2) return null
        fun nearest(angle: Double) = ring.armBearings.minByOrNull { abs(signedSweep(angle, it)) }!!
        val entryArm = nearest(entryAngle); val exitArm = nearest(exitAngle)
        if (entryArm == exitArm) return null
        val dir = if (sweep >= 0) 1.0 else -1.0
        fun travelled(arm: Double): Double { val s = signedSweep(entryArm, arm) * dir; return if (s <= 0) s + 360.0 else s }
        val order = ring.armBearings.filter { it != entryArm }.sortedBy { travelled(it) }
        return order.indexOf(exitArm).takeIf { it >= 0 }?.plus(1)
    }

    internal fun roadName(instruction: String): String? =
        Regex("(?:onto|on)\\s+(.+?)\\.?$").find(instruction)?.groupValues?.get(1)?.takeIf { it.isNotBlank() && !it.startsWith("the ") && it.length < 60 }
}

/** A point [meters] away from this one along [bearing] (degrees clockwise from north); flat-earth maths, fine for a few hundred metres. */
fun GeoPoint.offset(meters: Double, bearing: Double): GeoPoint {
    val r = Math.toRadians(bearing)
    return GeoPoint(latitude + meters * cos(r) / 111_320.0, longitude + meters * sin(r) / (111_320.0 * cos(Math.toRadians(latitude))))
}

/** Routes that come back from [delegate] with their roundabouts put right. Without map data for the area they pass through unchanged. */
class RoundaboutRoutingProvider(
    private val delegate: RoutingProvider,
    private val rings: suspend (List<GeoPoint>) -> List<RoundaboutRing>,
) : RoutingProvider {
    override suspend fun plan(request: PlanRequest, pinnedVersion: String?): RouteResult {
        val result = delegate.plan(request, pinnedVersion)
        return if (result is RouteResult.Ready) RouteResult.Ready(fix(result.route)) else result
    }
    override suspend fun alternatives(request: PlanRequest): List<CampusRoute> = delegate.alternatives(request).map { fix(it) }

    private suspend fun fix(route: CampusRoute): CampusRoute {
        if (!route.external) return route
        val found = try { rings(route.geometry) } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Exception) { emptyList() }
        if (found.isEmpty()) return route
        val fixed = RoundaboutRewriter.apply(route, found)
        return if (RouteIntegrity.shapeErrors(fixed).isEmpty()) fixed else route
    }
}
