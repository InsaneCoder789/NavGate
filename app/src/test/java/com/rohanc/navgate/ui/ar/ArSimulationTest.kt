package com.rohanc.navgate.ui.ar

import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.core.RouteGeometry
import com.rohanc.navgate.core.RouteTracker
import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The AR engine run with no camera at all. A simulated ARCore reports the camera pose in its own free-floating world (with the drift
 * real tracking has), a simulated compass is biased and noisy, GPS is noisy and goes through the route tracker as in the app. The
 * question each test asks is the one that matters on the street: is the road line where the road is, and does it stay put?
 */
class ArSimulationTest {
    private val origin = GeoPoint(19.0, 72.85)
    private fun geo(east: Double, north: Double) = GeoPoint(origin.latitude + north / 111_320.0, origin.longitude + east / (111_320.0 * cos(Math.toRadians(origin.latitude))))
    // 120 m north, then 120 m east: a road with one right turn.
    private val route = RouteGeometry(listOf(geo(0.0, 0.0), geo(0.0, 120.0), geo(120.0, 120.0)))

    /** How ARCore's arbitrary world relates to the real one: a yaw, and where the real origin sits in ARCore's world. */
    private class Frame(val thetaDeg: Double, val x0: Double, val z0: Double) {
        val t = Math.toRadians(thetaDeg)
        fun point(east: Double, north: Double): Pair<Double, Double> = Pair(x0 + (east * cos(t) - north * sin(t)), z0 - (east * sin(t) + north * cos(t)))
        fun direction(e: Double, n: Double, u: Double) = doubleArrayOf(e * cos(t) - n * sin(t), u, -(e * sin(t) + n * cos(t)))
    }

    /** A hand-held camera: where it is and where it points, in real-world terms. */
    private class Hand(val east: Double, val north: Double, val height: Double, val azimuthDeg: Double, val pitchDeg: Double)

    private fun reading(h: Hand, f: Frame, driftX: Double, driftZ: Double, timeMs: Long): ArCameraReading {
        val az = Math.toRadians(h.azimuthDeg); val p = Math.toRadians(h.pitchDeg)
        val fwd = f.direction(sin(az) * cos(p), cos(az) * cos(p), sin(p))
        val right = f.direction(cos(az), -sin(az), 0.0)
        val up = f.direction(-sin(az) * sin(p), -cos(az) * sin(p), cos(p))
        val (x, z) = f.point(h.east, h.north)
        return ArCameraReading(doubleArrayOf(x + driftX, h.height, z + driftZ), fwd, right, up, timeMs)
    }

    private fun upAzimuth(h: Hand): Double { val az = Math.toRadians(h.azimuthDeg); val p = Math.toRadians(h.pitchDeg); return (Math.toDegrees(atan2(-sin(az) * sin(p), -cos(az) * sin(p))) + 360) % 360 }

    /** The road, 5 to 30 m ahead of [along], as the engine places it versus where it truly is: average error in metres. */
    private fun placementError(world: ArWorld, f: Frame, along: Double, driftX: Double, driftZ: Double): Double {
        var sum = 0.0; var n = 0
        var s = along + 5.0
        while (s <= along + 30.0) {
            val p = route.pointAt(s)
            val (east, north) = Pair((p.longitude - origin.longitude) * 111_320.0 * cos(Math.toRadians(origin.latitude)), (p.latitude - origin.latitude) * 111_320.0)
            val truth = f.point(east, north)
            val placed = world.aligner.toGround(p) ?: return Double.MAX_VALUE
            // ARCore's own drift moves its idea of every point equally, so the comparison is made in the drifted frame.
            sum += hypot(placed.x - (truth.first + driftX), placed.z - (truth.second + driftZ)); n++
            s += 5.0
        }
        return sum / n
    }

    private class Run(val world: ArWorld, val tracker: RouteTracker, val rng: Random)

    private fun walk(seconds: Int, startAlong: Double, speed: Double, frame: Frame, compassBias: Double, seed: Long, wobbleDeg: Double = 12.0, onFrame: (Int, Run, Double, Double, Double) -> Unit = { _, _, _, _, _ -> }): Run {
        val rng = Random(seed); val world = ArWorld(); val tracker = RouteTracker(route, walking = true)
        var driftX = 0.0; var driftZ = 0.0
        for (frameIndex in 0..seconds * 30) {
            val t = frameIndex / 30.0
            val along = startAlong + speed * t
            val point = route.pointAt(along); val bearing = route.bearingAt(along) ?: 0.0
            val east = (point.longitude - origin.longitude) * 111_320.0 * cos(Math.toRadians(origin.latitude)); val north = (point.latitude - origin.latitude) * 111_320.0
            // The hand sways and the phone looks around a little; ARCore drifts about 1% of the distance walked.
            val hand = Hand(east + sin(t * 2.1) * 0.05, north + cos(t * 1.7) * 0.05, 1.4 + sin(t * 3.0) * 0.02, bearing + sin(t * 0.7) * wobbleDeg, -18.0 + sin(t * 1.1) * 8.0)
            driftX += rng.nextGaussian() * 0.0007 * speed; driftZ += rng.nextGaussian() * 0.0007 * speed
            val ms = (t * 1000).toLong()
            if (frameIndex % 30 == 0) tracker.update(geo(east + rng.nextGaussian() * 3.5, north + rng.nextGaussian() * 3.5), 5.0, speed + rng.nextGaussian() * 0.2, ms)
            val estimate = tracker.predict(ms)
            val compass = (hand.azimuthDeg + compassBias + rng.nextGaussian() * 4.0 + 360) % 360
            world.align(reading(hand, frame, driftX, driftZ, ms), compass, upAzimuth(hand).let { (it + compassBias + 360) % 360 }, route, estimate.alongMeters, estimate.speedMps)
            onFrame(frameIndex, Run(world, tracker, rng), along, driftX, driftZ)
        }
        return Run(world, tracker, rng)
    }

    @Test fun `a compass that is 25 degrees wrong is corrected by walking the road`() {
        val frame = Frame(137.0, 3.0, -8.0)
        var early = 0.0; var late = 0.0
        walk(40, 0.0, 1.4, frame, compassBias = 25.0, seed = 1) { i, run, along, dx, dz ->
            if (i == 15) early = placementError(run.world, frame, along, dx, dz)
            if (i == 30 * 38) late = placementError(run.world, frame, along, dx, dz)
        }
        assertTrue("a compass 25 degrees out starts well off the road: $early m", early > 4.0)
        assertTrue("after walking the road is within a metre and a half: $late m", late < 1.5)
        assertTrue("and clearly better than it started ($early -> $late)", late < early / 2)
    }

    @Test fun `the road line stays put when the phone is waved about and the user does not move`() {
        val frame = Frame(-60.0, -4.0, 11.0)
        val rng = Random(2); val world = ArWorld(); val tracker = RouteTracker(route, walking = true)
        val samples = Array(8) { ArrayList<Pair<Double, Double>>() }
        val start = route.pointAt(20.0)
        val e = (start.longitude - origin.longitude) * 111_320.0 * cos(Math.toRadians(origin.latitude)); val n = (start.latitude - origin.latitude) * 111_320.0
        for (i in 0..30 * 24) {
            val t = i / 30.0; val ms = (t * 1000).toLong()
            // Standing still. The phone sweeps ±70 degrees, pitches from the sky to the feet, and the hand shakes.
            val hand = Hand(e + rng.nextGaussian() * 0.03, n + rng.nextGaussian() * 0.03, 1.4 + rng.nextGaussian() * 0.02, 20.0 + sin(t * 0.9) * 70.0, -40.0 + sin(t * 1.3) * 45.0)
            if (i % 30 == 0) tracker.update(geo(e + rng.nextGaussian() * 5.0, n + rng.nextGaussian() * 5.0), 6.0, 0.0, ms)
            val est = tracker.predict(ms)
            val compass = (hand.azimuthDeg + 10 + rng.nextGaussian() * 5 + 360) % 360
            world.align(reading(hand, frame, 0.0, 0.0, ms), compass, (upAzimuth(hand) + 10 + 360) % 360, route, est.alongMeters, est.speedMps)
            if (i > 30 * 8) for (k in 0 until 8) world.aligner.toGround(route.pointAt(25.0 + k * 4.0))?.let { samples[k].add(it.x to it.z) }
        }
        val spread = samples.map { pts -> val mx = pts.map { it.first }.average(); val mz = pts.map { it.second }.average(); sqrt(pts.map { (it.first - mx) * (it.first - mx) + (it.second - mz) * (it.second - mz) }.average()) }
        // Judged as an angle seen from the user, since the same wobble is a bigger shift the farther along the road you look.
        val angular = spread.mapIndexed { k, metres -> metres / (5.0 + k * 4.0 + 20.0) }
        assertTrue("the road swayed by up to ${angular.max() * 57.3} degrees while the phone was waved about; it must not follow the phone", angular.max() < 0.014)
    }

    @Test fun `GPS wobble while standing still does not slide the line`() {
        val frame = Frame(15.0, 0.0, 0.0)
        val rng = Random(3); val world = ArWorld(); val tracker = RouteTracker(route, walking = true)
        val s = route.pointAt(30.0)
        val e = (s.longitude - origin.longitude) * 111_320.0 * cos(Math.toRadians(origin.latitude)); val n = (s.latitude - origin.latitude) * 111_320.0
        val seen = ArrayList<Pair<Double, Double>>()
        for (i in 0..30 * 40) {
            val ms = (i / 30.0 * 1000).toLong()
            val hand = Hand(e, n, 1.4, 0.0, -15.0)
            if (i % 30 == 0) tracker.update(geo(e + rng.nextGaussian() * 8.0, n + rng.nextGaussian() * 8.0), 9.0, 0.1, ms)   // poor GPS: ±8 m
            val est = tracker.predict(ms)
            world.align(reading(hand, frame, 0.0, 0.0, ms), 0.0, null, route, est.alongMeters, est.speedMps)
            if (i > 30 * 10) world.aligner.toGround(route.pointAt(40.0))?.let { seen.add(it.x to it.z) }
        }
        val spread = (seen.maxOf { it.first } - seen.minOf { it.first }).coerceAtLeast(seen.maxOf { it.second } - seen.minOf { it.second })
        assertTrue("the line crept by $spread m on GPS noise alone", spread < 1.2)
    }

    @Test fun `after ARCore loses tracking and its world jumps the line is back on the road within seconds`() {
        val a = Frame(40.0, 1.0, 2.0); val b = Frame(-100.0, 30.0, -14.0)   // ARCore's world after it relocalises is somewhere else entirely
        var beforeLoss = 0.0; var justAfter = 0.0; var recovered = 0.0
        val rng = Random(4); val world = ArWorld(); val tracker = RouteTracker(route, walking = true)
        for (i in 0..30 * 50) {
            val t = i / 30.0; val ms = (t * 1000).toLong()
            val frame = if (t < 25) a else b
            if (abs(t - 25.0) < 1e-6) world.relocalized()
            val along = 1.4 * t; val p = route.pointAt(along); val bearing = route.bearingAt(along) ?: 0.0
            val e = (p.longitude - origin.longitude) * 111_320.0 * cos(Math.toRadians(origin.latitude)); val n = (p.latitude - origin.latitude) * 111_320.0
            val hand = Hand(e, n, 1.4, bearing, -15.0)
            if (i % 30 == 0) tracker.update(geo(e + rng.nextGaussian() * 3, n + rng.nextGaussian() * 3), 5.0, 1.4, ms)
            val est = tracker.predict(ms)
            val compass = (hand.azimuthDeg + 6 + rng.nextGaussian() * 3 + 360) % 360
            world.align(reading(hand, frame, 0.0, 0.0, ms), compass, null, route, est.alongMeters, est.speedMps)
            if (i == 30 * 24) beforeLoss = placementError(world, frame, along, 0.0, 0.0)
            if (i == 30 * 26) justAfter = placementError(world, frame, along, 0.0, 0.0)
            if (i == 30 * 40) recovered = placementError(world, frame, along, 0.0, 0.0)
        }
        assertTrue("before the loss: $beforeLoss m", beforeLoss < 1.5)
        assertTrue("after realigning the line is back on the road ($recovered m, was $justAfter m just after)", recovered < 1.8)
    }

    @Test fun `the blue circle sits on the ground directly under the phone when it looks at your feet`() {
        // Camera 1.4 m up pointing straight down. The ring centre is the camera's own x and z at ground height.
        val f = Frame(0.0, 0.0, 0.0)
        val hand = Hand(2.0, 3.0, 1.4, 35.0, -90.0)
        val r = reading(hand, f, 0.0, 0.0, 0)
        val centre = doubleArrayOf(r.position[0], r.position[1] - 1.4, r.position[2])
        val rel = DoubleArray(3) { centre[it] - r.position[it] }
        fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        val depth = dot(rel, r.forward)
        assertEquals(1.4, depth, 1e-6)
        assertEquals("ring is centred left-right", 0.0, dot(rel, r.right) / depth, 1e-6)
        assertEquals("ring is centred up-down", 0.0, dot(rel, r.up) / depth, 1e-6)
        // With the phone tilted a little off straight down the ring is still in the lower half of the picture, near the bottom of the view.
        val tilted = reading(Hand(2.0, 3.0, 1.4, 35.0, -60.0), f, 0.0, 0.0, 0)
        val relT = DoubleArray(3) { centre[it] - tilted.position[it] }
        assertTrue("below centre when looking ahead and down", dot(relT, tilted.up) / dot(relT, tilted.forward) < 0)
    }

    @Test fun `a road point ten metres ahead lands where the ground plane says on a level camera`() {
        val f = Frame(0.0, 0.0, 0.0)
        val hand = Hand(0.0, 0.0, 1.4, 0.0, 0.0)             // level, facing north
        val r = reading(hand, f, 0.0, 0.0, 0)
        val world = ArWorld()
        world.align(r, 0.0, null, route, 0.0, 0.0)
        val ground = world.aligner.toGround(route.pointAt(10.0))!!
        val rel = doubleArrayOf(ground.x - r.position[0], -1.4, ground.z - r.position[2])
        val forward = rel[0] * r.forward[0] + rel[1] * r.forward[1] + rel[2] * r.forward[2]
        val up = rel[0] * r.up[0] + rel[1] * r.up[1] + rel[2] * r.up[2]
        val right = rel[0] * r.right[0] + rel[1] * r.right[1] + rel[2] * r.right[2]
        assertEquals("10 m straight ahead", 10.0, forward, 0.2)
        assertEquals("on the road centreline", 0.0, right, 0.2)
        assertEquals("1.4 m below the camera: ${Math.toDegrees(atan2(-up, forward))} degrees below the horizon", Math.toDegrees(atan2(1.4, 10.0)), Math.toDegrees(atan2(-up, forward)), 0.5)
    }

    @Test fun `the turn arrow points down the road you will take`() {
        val frame = Frame(77.0, 5.0, 9.0)
        val world = ArWorld()
        // Standing at the corner approach: aligned from the compass, then ask for the scene with the turn 20 m ahead.
        val along = 100.0; val p = route.pointAt(along); val bearing = route.bearingAt(along) ?: 0.0
        val e = (p.longitude - origin.longitude) * 111_320.0 * cos(Math.toRadians(origin.latitude)); val n = (p.latitude - origin.latitude) * 111_320.0
        world.align(reading(Hand(e, n, 1.4, bearing, -10.0), frame, 0.0, 0.0, 0), bearing, null, route, along, 0.0)
        val path = (0..14).map { route.pointAt(along + it * 4.0) }                // 56 m ahead, past the corner at 120 m
        val scene = world.buildScene(path, 20.0, "Right", 0.0)!!
        val arrow = scene.arrow!!
        val corner = scene.turnPoint!!
        // Express every arrow vertex in real east/north metres from the corner, using the true frame, then check where the arrow reaches.
        val th = frame.t
        var maxEast = -1e9; var minNorth = 1e9
        for (i in 0 until arrow.vertexCount) {
            val e0 = arrow.vertices[i * 5] - corner.x; val n0 = -(arrow.vertices[i * 5 + 2] - corner.z)
            val east = e0 * cos(th) + n0 * sin(th); val north = -e0 * sin(th) + n0 * cos(th)
            maxEast = maxOf(maxEast, east); minNorth = minOf(minNorth, north)
        }
        assertTrue("the head reaches about 5.6 m east of the corner, down the road after the turn (was $maxEast)", maxEast in 5.0..6.2)
        assertTrue("the tail starts about 7 m south of the corner, back along the road you came on (was $minNorth)", minNorth in -7.6..-6.4)
    }

    @Test fun `predicting the camera between frames beats holding the last one`() {
        // The phone turns at 120 degrees per second; ARCore reports at 30 Hz, the screen refreshes at 120 Hz.
        fun quat(angleDeg: Double): DoubleArray { val h = Math.toRadians(angleDeg) / 2; return doubleArrayOf(0.0, sin(h), 0.0, cos(h)) }
        fun angleOf(q: DoubleArray) = Math.toDegrees(2 * atan2(q[1], q[3]))
        var holdError = 0.0; var predictedError = 0.0; var n = 0
        for (frame in 2..60) {
            val tPrev = (frame - 1) / 30.0; val tLast = frame / 30.0
            for (sub in 1..3) {                                                    // three more screen refreshes before the next camera frame
                val fraction = sub / 4.0
                val trueAngle = 120.0 * (tLast + fraction / 30.0)
                val (_, predicted) = PosePredictor.predict(doubleArrayOf(0.0, 0.0, 0.0), quat(120.0 * tPrev), doubleArrayOf(0.0, 0.0, 0.0), quat(120.0 * tLast), fraction * 1.0)
                predictedError += abs(angleOf(predicted) - trueAngle); holdError += abs(120.0 * tLast - trueAngle); n++
            }
        }
        assertTrue("prediction error ${predictedError / n} degrees vs holding ${holdError / n}", predictedError / n < holdError / n * 0.1)
    }

    @Test fun `the camera picture is turned upright and rectangles land where people really are on screen`() {
        val w = 640; val h = 480
        val y = ByteArray(w * h) { 16 }; val u = ByteArray(w * h / 2) { 128.toByte() }; val v = ByteArray(w * h / 2) { 128.toByte() }
        // A bright rectangle in the sensor image at x 0.2..0.4, y 0.5..0.8.
        for (py in (0.5 * h).toInt() until (0.8 * h).toInt()) for (px in (0.2 * w).toInt() until (0.4 * w).toInt()) y[py * w + px] = 235.toByte()
        // Portrait phone, camera sensor rotated 90 degrees: view_x = 1 - image_y, view_y = image_x.
        val forward = floatArrayOf(1f, 0f, 1f, 1f, 0f, 0f)
        val outW = 200; val outH = 400
        val pixels = YuvResampler.toScreenArgb(y, u, v, w, h, w, w, 2, forward, outW, outH)!!
        fun bright(px: Int, py: Int) = (pixels[py * outW + px] and 0xFF) > 150
        // Expected on screen: x in 0.2..0.5, y in 0.2..0.4.
        assertTrue("inside the expected box", bright((0.35 * outW).toInt(), (0.30 * outH).toInt()))
        assertFalse("not where an unrotated image would put it", bright((0.30 * outW).toInt(), (0.65 * outH).toInt()))
        assertFalse("left of the box", bright((0.10 * outW).toInt(), (0.30 * outH).toInt()))
        assertFalse("below the box", bright((0.35 * outW).toInt(), (0.60 * outH).toInt()))
    }

    // ---- the blue circle and the ground ---------------------------------------------------------------------------------------------

    private fun pitchAxes(pitchDeg: Double, azimuthDeg: Double = 0.0): Pair<DoubleArray, DoubleArray> {
        val p = Math.toRadians(pitchDeg); val a = Math.toRadians(azimuthDeg)
        // ARCore world: x east-ish, y up, z south-ish; forward at azimuth 0 points to -z.
        val forward = doubleArrayOf(sin(a) * cos(p), sin(p), -cos(a) * cos(p))
        val up = doubleArrayOf(-sin(a) * sin(p), cos(p), cos(a) * sin(p))
        return forward to up
    }

    @Test fun `the circle appears only when the phone looks down at the feet`() {
        for (pitch in listOf(10.0, 0.0, -20.0, -34.0)) { val (f, u) = pitchAxes(pitch); assertNull("held up at $pitch degrees: no circle", FeetMarker.plan(0.0, 0.0, f, u)) }
        val (f60, u60) = pitchAxes(-60.0); assertEquals("fully shown by 60 degrees down", 1f, FeetMarker.plan(0.0, 0.0, f60, u60)!!.alpha, 0.01f)
        val (f45, u45) = pitchAxes(-45.0); val mid = FeetMarker.plan(0.0, 0.0, f45, u45)!!.alpha
        assertTrue("fading in half way down: $mid", mid in 0.2f..0.8f)
        val (f90, u90) = pitchAxes(-90.0); assertEquals(1f, FeetMarker.plan(0.0, 0.0, f90, u90)!!.alpha, 0.01f)
    }

    @Test fun `the circle is small and sits just behind the phone where the feet are`() {
        val (f, u) = pitchAxes(-70.0, azimuthDeg = 0.0)     // looking down and slightly north (-z)
        val plan = FeetMarker.plan(2.0, 5.0, f, u)!!
        assertEquals("radius is about half a metre, around the feet only", 0.5, plan.radius, 1e-9)
        assertTrue("it is just behind the phone, towards the body (south, +z) by about 0.28 m", abs(plan.z - (5.0 + 0.28)) < 0.02 && abs(plan.x - 2.0) < 0.02)
        // Straight down, there is no lens direction: the body is opposite the top edge of the phone.
        val (f2, u2) = pitchAxes(-90.0, azimuthDeg = 90.0)
        val down = FeetMarker.plan(0.0, 0.0, f2, u2)!!
        assertTrue("with the top edge pointing east the body is to the west: ${down.x}", down.x < -0.2 && abs(down.z) < 0.05)
    }

    @Test fun `lifting or lowering the phone does not move the ground`() {
        val ground = GroundEstimator()
        // The phone is 1.3 m above a ground that sits at world height 0.2.
        assertEquals(0.2, ground.groundY(1.5, 1.3), 1e-9)
        repeat(6) { ground.addSample(0.2, 1.5) }
        assertTrue(ground.found)
        // The user raises the phone to 2.0 m and lowers it to 0.9 m: the ground stays where it is, only the phone's height changes.
        assertEquals(0.2, ground.groundY(2.0, 1.3), 1e-9); assertEquals(1.8, ground.phoneHeight(2.0, 1.3), 1e-9)
        assertEquals(0.2, ground.groundY(0.9, 1.3), 1e-9); assertEquals(0.7, ground.phoneHeight(0.9, 1.3), 1e-9)
    }

    @Test fun `the ground is measured from hits, ignoring nonsense, and settles on the median`() {
        val ground = GroundEstimator()
        ground.groundY(1.4, 1.3)
        // A ceiling, the phone's own height, and a hit 5 m down are not ground.
        ground.addSample(2.6, 1.4); ground.addSample(1.3, 1.4); ground.addSample(-3.6, 1.4)
        assertFalse(ground.found)
        listOf(0.02, 0.03, 0.55, 0.01, 0.02, 0.04, -0.01).forEach { ground.addSample(it, 1.4) }   // one stray reading among good ones
        assertTrue(ground.found)
        assertEquals("median ignores the stray 0.55", 0.02, ground.groundY(1.4, 1.3), 0.05)
    }

    @Test fun `a phone held at a different height still puts the road on the same ground`() {
        // Same road, same ground; the phone goes from chest height to head height. The ribbon must stay at ground level both times.
        val frame = Frame(30.0, 0.0, 0.0)
        val world = ArWorld()
        val p = route.pointAt(10.0); val bearing = route.bearingAt(10.0) ?: 0.0
        val e = (p.longitude - origin.longitude) * 111_320.0 * cos(Math.toRadians(origin.latitude)); val n = (p.latitude - origin.latitude) * 111_320.0
        world.align(reading(Hand(e, n, 1.2, bearing, -20.0), frame, 0.0, 0.0, 0), bearing, null, route, 10.0, 0.0)
        val path = (0..8).map { route.pointAt(10.0 + it * 4.0) }
        val ground = GroundEstimator(); repeat(5) { ground.addSample(0.0, 1.2) }
        val low = world.buildScene(path, 30.0, null, ground.groundY(1.2, 1.3))!!
        val high = world.buildScene(path, 30.0, null, ground.groundY(1.9, 1.3))!!
        assertEquals("ribbon height is the same however high the phone is", low.ribbon.vertices[1], high.ribbon.vertices[1], 1e-6f)
        assertEquals(0.02f, low.ribbon.vertices[1], 1e-4f)
    }

    @Test fun `confidence is low on a compass alone, high once the road has confirmed the heading, and low again if the position is poor`() {
        val frame = Frame(10.0, 0.0, 0.0)
        var atStart = 0.0; var afterWalking = 0.0
        walk(30, 0.0, 1.4, frame, compassBias = 20.0, seed = 9) { i, run, _, _, _ ->
            if (i == 10) atStart = run.world.confidence
            if (i == 30 * 20) afterWalking = run.world.confidence
        }
        assertTrue("a compass alone is not trusted: $atStart", atStart in 0.2..0.5)
        assertTrue("after walking along the road it is: $afterWalking", afterWalking > 0.85)
        // Poor GPS (30 m) cuts it down even when the heading is known.
        val poor = ArWorld()
        val r = reading(Hand(0.0, 0.0, 1.4, 0.0, -10.0), frame, 0.0, 0.0, 0)
        poor.aligner.update(r, 0.0, route.pointAt(0.0), 30.0, 0.0, 0.0)
        assertTrue("poor position accuracy: ${poor.confidence}", poor.confidence < 0.3)
        assertEquals("nothing is trusted before alignment", 0.0, ArWorld().confidence, 1e-9)
    }
}
