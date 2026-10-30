package com.rohanc.navgate.ui.map

import com.rohanc.navgate.core.FixGate
import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.core.LocationFix
import com.rohanc.navgate.core.CalibrationState
import com.rohanc.navgate.core.MotionEstimate
import com.rohanc.navgate.core.RouteGeometry
import com.rohanc.navgate.core.CampusRoute
import com.rohanc.navgate.core.Maneuver
import com.rohanc.navgate.core.NavigationSession
import com.rohanc.navgate.core.distanceToManeuverAt
import kotlinx.coroutines.runBlocking
import com.rohanc.navgate.ui.ar.ArWalkSimulator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.PriorityQueue
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * The KIIT campus, simulated in code: the real road network (OpenStreetMap, 2,298 road ways) and the real places of the KIIT sheet. Routes are found between sheet places,
 * a car is driven along each with GPS that jumps, drifts and drops out, and the same pieces the app runs (the fix gate, the route tracker, the marker and camera state) are
 * stepped frame by frame. Every metric has a budget, and a run passes when at most 5% of its samples break it.
 */
class KiitSimulationTest {
    private class Edge(val to: Int, val meters: Double)
    private class Net(val points: List<GeoPoint>, val edges: List<List<Edge>>, val component: Set<Int>)

    private companion object {
        const val MAX_VIOLATING = 0.05
        var net: Net? = null
        var places: List<GeoPoint> = emptyList()
        val routes = ArrayList<List<GeoPoint>>()

        private fun find(path: String): File? = listOf("../$path", path).map(::File).firstOrNull { it.exists() }

        @JvmStatic @BeforeClass fun load() {
            val osm = find("data/osm/kiit-overpass.json") ?: return
            val cat = find("app/src/main/assets/regions/kiit-catalog.json") ?: return
            val el = Json.parseToJsonElement(osm.readText()).jsonObject["elements"]!!.jsonArray
            val index = HashMap<Long, Int>(); val pts = ArrayList<GeoPoint>()
            for (e in el) { val o = e.jsonObject; if (o["type"]!!.jsonPrimitive.content == "node") { index[o["id"]!!.jsonPrimitive.content.toLong()] = pts.size; pts += GeoPoint(o["lat"]!!.jsonPrimitive.double, o["lon"]!!.jsonPrimitive.double) } }
            val adj = List(pts.size) { ArrayList<Edge>() }
            val skip = setOf("footway", "path", "steps", "pedestrian", "cycleway", "corridor", "bridleway")
            for (e in el) {
                val o = e.jsonObject; if (o["type"]!!.jsonPrimitive.content != "way") continue
                val tags = o["tags"] as? JsonObject ?: continue; val hw = tags["highway"]?.jsonPrimitive?.content ?: continue
                if (hw in skip) continue
                val ids = (o["nodes"] as JsonArray).mapNotNull { index[it.jsonPrimitive.content.toLong()] }
                val one = tags["oneway"]?.jsonPrimitive?.content
                for (i in 0 until ids.size - 1) {
                    val a = ids[i]; val b = ids[i + 1]; val m = pts[a].distanceTo(pts[b])
                    if (one != "-1") adj[a] += Edge(b, m)
                    if (one != "yes" && one != "1") adj[b] += Edge(a, m)
                    if (one == "-1") adj[b] += Edge(a, m).let { Edge(a, m) }.let { Edge(a, m) }
                }
            }
            // The biggest set of roads that can all reach each other (a one-way loop is strongly connected enough: use undirected reach and let Dijkstra skip what it cannot drive).
            val und = List(pts.size) { ArrayList<Int>() }
            for (a in adj.indices) for (e in adj[a]) { und[a] += e.to; und[e.to] += a }
            val seen = BooleanArray(pts.size); var best = emptyList<Int>()
            for (s in pts.indices) {
                if (seen[s] || und[s].isEmpty()) continue
                val comp = ArrayList<Int>(); val q = ArrayDeque<Int>(); q.add(s); seen[s] = true
                while (q.isNotEmpty()) { val x = q.removeFirst(); comp += x; for (y in und[x]) if (!seen[y]) { seen[y] = true; q.add(y) } }
                if (comp.size > best.size) best = comp
            }
            net = Net(pts, adj, best.toSet())
            val cp = Json.parseToJsonElement(cat.readText()).jsonObject["places"]!!.jsonArray
            places = cp.mapNotNull { p -> (p.jsonObject["point"] as? JsonObject)?.let { GeoPoint(it["latitude"]!!.jsonPrimitive.double, it["longitude"]!!.jsonPrimitive.double) } }
            buildRoutes()
        }

        private fun nearestNode(n: Net, p: GeoPoint): Int? {
            var best = -1; var bd = 150.0
            for (i in n.component) { val d = n.points[i].distanceTo(p); if (d < bd) { bd = d; best = i } }
            return if (best >= 0) best else null
        }

        private fun dijkstra(n: Net, from: Int, to: Int): List<GeoPoint>? {
            val dist = HashMap<Int, Double>(); val prev = HashMap<Int, Int>()
            val q = PriorityQueue<Pair<Int, Double>>(compareBy { it.second }); dist[from] = 0.0; q.add(from to 0.0)
            while (q.isNotEmpty()) {
                val (u, d) = q.poll(); if (d > (dist[u] ?: Double.MAX_VALUE)) continue; if (u == to) break
                for (e in n.edges[u]) { val nd = d + e.meters; if (nd < (dist[e.to] ?: Double.MAX_VALUE)) { dist[e.to] = nd; prev[e.to] = u; q.add(e.to to nd) } }
            }
            if (to !in dist) return null
            val out = ArrayList<GeoPoint>(); var c = to; out += n.points[c]
            while (c != from) { c = prev[c]!!; out += n.points[c] }
            return out.reversed()
        }

        private fun buildRoutes() {
            val n = net ?: return; val rng = Random(11)
            var tries = 0
            while (routes.size < 36 && tries++ < 3000) {
                val a = places[rng.nextInt(places.size)]; val b = places[rng.nextInt(places.size)]
                val straight = a.distanceTo(b); if (straight < 600 || straight > 3500) continue
                val na = nearestNode(n, a) ?: continue; val nb = nearestNode(n, b) ?: continue
                val path = dijkstra(n, na, nb) ?: continue
                val g = RouteGeometry(path)
                if (g.length < 500 || g.length > 6000 || turns(g).size < 2) continue
                routes += path
            }
        }

        /** Distances along the route where the road turns by more than 35 degrees within 25 m. */
        fun turns(g: RouteGeometry): List<Double> {
            val out = ArrayList<Double>(); var s = 12.0; var last = -100.0
            while (s < g.length - 12.0) {
                val a = g.bearingAt(s - 10.0); val b = g.bearingAt(s + 10.0)
                if (a != null && b != null && abs(((b - a + 540.0) % 360.0) - 180.0) > 35.0 && s - last > 30.0) { out += s; last = s; s += 25.0 } else s += 1.0
                if (out.isNotEmpty() && out.last() == s - 1.0) last = out.last()
            }
            return out
        }
    }

    private fun angle(a: Double, b: Double) = abs(((a - b + 540.0) % 360.0) - 180.0)
    private fun enu(p: GeoPoint, o: GeoPoint) = ((p.longitude - o.longitude) * 111_320.0 * cos(Math.toRadians(o.latitude))) to ((p.latitude - o.latitude) * 111_320.0)
    private fun offset(p: GeoPoint, east: Double, north: Double) = GeoPoint(p.latitude + north / 111_320.0, p.longitude + east / (111_320.0 * cos(Math.toRadians(p.latitude))))

    private class Counter(val name: String, val budget: String) { var n = 0; var bad = 0; val worst get() = if (n == 0) 0.0 else bad.toDouble() / n
        fun add(violated: Boolean) { n++; if (violated) bad++ } }

    /** A screen: [widthPx] x [heightPx] dp, the car anchored [anchorFromTop] of the way down, the map turned by [bearing] and pitched by [pitchDeg]. */
    private fun toScreen(p: GeoPoint, car: GeoPoint, zoom: Double, bearing: Double, pitchDeg: Double, widthPx: Double, heightPx: Double, anchorFromTop: Double): Pair<Double, Double> {
        val (e, n) = enu(p, car); val b = Math.toRadians(bearing)
        val mpp = 78_271.517 * cos(Math.toRadians(car.latitude)) / Math.pow(2.0, zoom)      // metres per dp for 512-px tiles
        val right = (e * cos(b) - n * sin(b)) / mpp; val up = (e * sin(b) + n * cos(b)) / mpp
        val pr = Math.toRadians(pitchDeg); val d = 1.5 * heightPx
        val depth = d + up * sin(pr)
        val x = widthPx / 2 + right * d / depth; val yUp = up * cos(pr) * d / depth
        return x to (heightPx * anchorFromTop - yUp)
    }

    private val why = java.util.TreeMap<String, IntArray>()
    private fun note(bucket: String, bad: Boolean) { val a = why.getOrPut(bucket) { IntArray(2) }; a[0]++; if (bad) a[1]++ }

    private class Run(val posErr: Counter, val jump: Counter, val heading: Counter, val camera: Counter, val turnShown: Counter, val turnDist: Counter, val early: Counter, val firstHeadingMax: Double, val framesNorthUp: Counter)

    private fun campusRoute(path: List<GeoPoint>, turnAt: List<Double>): CampusRoute {
        val g = RouteGeometry(path); val len = g.length
        val maneuvers = ArrayList<Maneuver>(); maneuvers += Maneuver("Head out", path.first(), 0.0, "Start")
        for (s in turnAt) { val d = ((g.bearingAt(s + 10.0)!! - g.bearingAt(s - 10.0)!! + 540.0) % 360.0) - 180.0; maneuvers += Maneuver(if (d > 0) "Turn right" else "Turn left", g.pointAt(s), s, if (d > 0) "Right" else "Left") }
        maneuvers += Maneuver("Arrive", path.last(), len, "Arrive")
        val times = DoubleArray(path.size).also { for (i in 1 until path.size) it[i] = it[i - 1] + path[i - 1].distanceTo(path[i]) / 9.0 }
        return CampusRoute("kiit-sim", 1, "kiit", "v", "dest", "", path, emptyList(), len, times.last(), maneuvers, "", source = "valhalla", profile = "driving", provider = "p",
            fetchedAtEpochMillis = 1, destinationPoint = path.last(), shapeElapsedSeconds = times.toList())
    }

    private fun drive(path: List<GeoPoint>, seed: Long, northUp: Boolean = false, pitch: Double = 0.0, debug: Boolean = false): Run = runBlocking {
        val rng = Random(seed); val route = RouteGeometry(path); val turnAt = turns(route); val campus = campusRoute(path, turnAt)
        val session = NavigationSession(); val gate = FixGate(true); val marker = MarkerState()
        session.preview(campus); session.location(LocationFix(path.first(), 100, 4.0, null, 0.0), 100); session.start(100)
        val c = Run(Counter("position", "12 m"), Counter("jump", "speed*dt+0.8 m"), Counter("heading", "15 deg"), Counter("camera", "25 deg"), Counter("turn visible", "inside the screen at 45 m"),
            Counter("turn distance", "max(6 m, 5%)"), Counter("early turn", "no advance >15 m early"), 0.0, Counter("north up", "1 deg"))
        var along = 0.0; var t = 0.0; val dt = 1.0 / 60.0; var gpsE = 0.0; var gpsN = 0.0; var speed = 0.0
        var prevPoint: GeoPoint? = null; var firstMax = 0.0; var frames = 0; var lastJump = -99.0
        val dropouts = listOf(40.0 + rng.nextDouble() * 20, 110.0 + rng.nextDouble() * 20).map { it..(it + 4.0 + rng.nextDouble() * 4.0) }
        while (along < route.length - 3.0 && t < 900.0) {
            // A town driver: cruise at 9 m/s, ease to 3.5 m/s for a turn, 2 m/s^2 either way.
            val nextTurn = turnAt.firstOrNull { it > along - 3.0 }
            val want = if (nextTurn != null) min(9.0, 3.5 + 0.55 * max(0.0, nextTurn - along - 5.0)) else 9.0
            speed += (want - speed).coerceIn(-2.5 * dt, 2.0 * dt); speed = max(speed, 0.0)
            along += speed * dt
            val truth = route.pointAt(along); val roadBearing = route.bearingAt(along) ?: 0.0
            val ms = 1_000L + (t * 1000).toLong()
            if (frames % 60 == 0 && dropouts.none { t in it }) {
                gpsE = gpsE * 0.8 + rng.nextGaussian() * 2.2; gpsN = gpsN * 0.8 + rng.nextGaussian() * 2.2
                var fe = gpsE; var fn = gpsN; var acc = 4.0
                if (rng.nextDouble() < 0.04) { val d = 100.0 + rng.nextDouble() * 300.0; val a = rng.nextDouble() * 2 * Math.PI; fe += d * sin(a); fn += d * cos(a); acc = 3.0; lastJump = t }       // a confident wrong fix
                val (te, tn) = enu(truth, route.points.first()); val p = offset(route.points.first(), te + fe, tn + fn)
                val course = if (speed > 2.0) (roadBearing + rng.nextGaussian() * 6.0 + 360.0) % 360.0 else null
                val fix = LocationFix(p, ms, acc, course, if (speed > 0.3) speed + rng.nextGaussian() * 0.4 else 0.0)
                if (gate.accept(fix)) session.location(fix, ms)
            }
            session.tick(ms)
            val snap = session.state.value
            frames++
            val shapeAlong = campus.routeToShapeMeters(snap.alongMeters)
            val e = MotionEstimate(snap.snappedLocation ?: snap.location ?: path.first(), snap.speedAlongMps, snap.routeBearing, 0.0, 0.0, (snap.accuracyMeters ?: 5.0).coerceAtLeast(3.0), CalibrationState.Good, snap.speedAlongMps < 0.5, false, ms)
            val reported = snap.distanceToManeuverAt(ms)
            marker.step(dt, e, route, TrackInput(shapeAlong, snap.speedAlongMps, snap.fixElapsedMillis, snap.lockedToRoute), pitch, 17.0, 0.0, nowMillis = ms, northUp = northUp, turnDistanceMeters = reported)
            if (frames > 120) {
                val errM = marker.point.distanceTo(truth); c.posErr.add(errM > 12.0)
                val bucket = when { t < 10.0 -> "start"; dropouts.any { t in (it.start)..(it.endInclusive + 8.0) } -> "dropout"; t - lastJump < 6.0 -> "after a jump"; else -> "steady" }
                note("pos $bucket", errM > 12.0); note("heading $bucket", angle(marker.heading, roadBearing) > 15.0)
                prevPoint?.let { c.jump.add(marker.point.distanceTo(it) > speed * dt + 0.8) }
                c.heading.add(angle(marker.heading, roadBearing) > 15.0)
                val aheadTruth = route.bearingAt(min(route.length, along + 6.0 + 0.45 * min(speed, 25.0))) ?: roadBearing
                if (northUp) c.framesNorthUp.add(angle(marker.cameraBearing, 0.0) > 1.0) else c.camera.add(angle(marker.cameraBearing, aheadTruth) > 25.0)
                if (nextTurn != null) {
                    val trueDist = nextTurn - along
                    if (trueDist in 5.0..45.0 && !northUp) {
                        val (x, y) = toScreen(route.pointAt(nextTurn), marker.point, marker.zoom, marker.cameraBearing, pitch, 392.0, 800.0, 0.68)
                        c.turnShown.add(!(x in 24.0..368.0 && y in 40.0..780.0))
                    }
                    if (debug && frames % 30 == 0 && trueDist < 170.0) System.err.println("  DBG t=%.1f true=%.1f reported=%.1f idx=%d along=%.1f snapAlong=%.1f".format(t, trueDist, reported, snap.maneuverIndex, along, snap.alongMeters))
                    if (trueDist in 8.0..160.0) c.turnDist.add(abs(reported - trueDist) > max(6.0, 0.05 * trueDist))
                    c.early.add(reported <= 0.0 && trueDist > 15.0)
                }
            } else if (frames > 2) firstMax = max(firstMax, angle(marker.heading, roadBearing))
            prevPoint = marker.point; t += dt
        }
        Run(c.posErr, c.jump, c.heading, c.camera, c.turnShown, c.turnDist, c.early, firstMax, c.framesNorthUp)
    }

    private fun pooled(runs: List<Run>, pick: (Run) -> Counter): Pair<Double, Int> { val n = runs.sumOf { pick(it).n }; val bad = runs.sumOf { pick(it).bad }; return (if (n == 0) 0.0 else bad.toDouble() / n) to n }

    @Test fun `the road network and the routes load`() {
        assumeTrue("KIIT data not in this checkout", net != null)
        System.err.println("KIIT NET %d nodes, %d in the biggest road network, %d places, %d routes (%.1f km total)".format(net!!.points.size, net!!.component.size, places.size, routes.size, routes.sumOf { RouteGeometry(it).length } / 1000))
        assertTrue("only ${routes.size} routes", routes.size >= 20)
    }

    @Test fun `driving KIIT with a jumping GPS stays on the road, faces the right way and shows the turn`() {
        assumeTrue("KIIT data not in this checkout", net != null && routes.isNotEmpty())
        val runs = routes.mapIndexed { i, r -> drive(r, 100L + i).also { x -> System.err.println("  route %2d %5.0f m turns %d: pos %.1f%% heading %.1f%% turnDist %.1f%%".format(i, RouteGeometry(r).length, turns(RouteGeometry(r)).size, 100 * x.posErr.worst, 100 * x.heading.worst, 100 * x.turnDist.worst)) } }
        val rows = listOf("position error > 12 m" to { r: Run -> r.posErr }, "frame jump beyond speed" to { r: Run -> r.jump }, "car heading > 15 deg off the road" to { r: Run -> r.heading },
            "camera > 25 deg off the way ahead" to { r: Run -> r.camera }, "next turn not on screen at 45 m" to { r: Run -> r.turnShown },
            "distance to turn off by > max(6 m, 5%)" to { r: Run -> r.turnDist }, "turn advanced > 15 m early" to { r: Run -> r.early })
        val sb = StringBuilder("KIIT DRIVE ${runs.size} routes\n")
        val failures = ArrayList<String>()
        for ((name, pick) in rows) { val (share, n) = pooled(runs, pick); sb.append("  %-40s %5.2f%% of %d samples\n".format(name, share * 100, n)); if (share > MAX_VIOLATING) failures += name }
        val firstWorst = runs.maxOf { it.firstHeadingMax }
        sb.append("  worst heading error in the first second: %.1f deg\n".format(firstWorst))
        why.forEach { (k, v) -> sb.append("    %-22s %5.2f%% of %d\n".format(k, 100.0 * v[1] / v[0], v[0])) }
        System.err.print(sb)
        assertTrue("over 5%: $failures", failures.isEmpty())
        assertTrue("the car started facing %.1f deg off the road".format(firstWorst), firstWorst < 12.0)
    }

    @Test fun `north up keeps north at the top and the zoom is a quarter further out`() {
        assumeTrue("KIIT data not in this checkout", net != null && routes.isNotEmpty())
        val runs = routes.take(10).mapIndexed { i, r -> drive(r, 500L + i, northUp = true) }
        val (share, n) = pooled(runs) { it.framesNorthUp }; System.err.println("KIIT NORTH-UP %.2f%% of %d frames off north".format(share * 100, n))
        assertTrue(share <= MAX_VIOLATING)
        val old = 17.0 + 2.6; val now = zoomFor(7.0, false); val ratio = Math.pow(2.0, now - old)
        assertEquals("the map shows 25% more at the same size", 0.75, ratio, 0.75 * 0.05)
    }

    @Test fun `the 3D map still shows the next turn ahead of the car`() {
        assumeTrue("KIIT data not in this checkout", net != null && routes.isNotEmpty())
        val runs = routes.take(12).mapIndexed { i, r -> drive(r, 900L + i, pitch = 55.0) }
        val (share, n) = pooled(runs) { it.turnShown }; System.err.println("KIIT 3D (pitch 55) next turn off screen %.2f%% of %d samples".format(share * 100, n))
        assertTrue(share <= MAX_VIOLATING)
    }

    @Test fun `walking KIIT in AR the line lies on the road`() {
        assumeTrue("KIIT data not in this checkout", net != null && routes.isNotEmpty())
        var bad = 0; var total = 0; val yaw = ArrayList<Double>(); val dir = ArrayList<Double>(); val lat = ArrayList<Double>()
        for ((i, path) in routes.take(8).withIndex()) {
            val g = RouteGeometry(path); if (g.length < 300) continue
            val r = ArWalkSimulator(g).simulate(if (i % 2 == 0) 50.0 else -60.0, 1L + i, seconds = 150.0)
            yaw += r.yawError; dir += r.lineAngle; lat += r.lateral
            total += r.lineAngle.size; bad += r.lineAngle.count { it > 30.0 } + 0
        }
        fun pct(l: List<Double>, p: Double) = l.sorted()[((l.size - 1) * p).toInt()]
        val dirShare = dir.count { it > 30.0 }.toDouble() / dir.size; val latShare = lat.count { it > 7.0 }.toDouble() / lat.size; val yawShare = yaw.count { it > 12.0 }.toDouble() / yaw.size
        System.err.println("KIIT AR walk: yaw p90 %.1f deg (%.1f%% over 12), line direction p90 %.1f deg (%.1f%% over 30), line off road at 10 m p90 %.2f m (%.1f%% over 7)".format(
            pct(yaw, .9), yawShare * 100, pct(dir, .9), dirShare * 100, pct(lat, .9), latShare * 100))
        assertTrue("yaw $yawShare", yawShare <= MAX_VIOLATING); assertTrue("dir $dirShare", dirShare <= MAX_VIOLATING); assertTrue("lateral $latShare", latShare <= MAX_VIOLATING)
    }
}
