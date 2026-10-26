package com.rohanc.navgate.ui.ar

import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.core.RouteGeometry
import com.rohanc.navgate.core.offset
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** What is drawn on the road this frame, all in ARCore world coordinates. */
class ArScene(val ribbon: ArMeshData, val arrow: ArMeshData?, val arrowOutline: ArMeshData?, val turnPoint: GroundPoint?, val turnIsArrival: Boolean, val roadEnd: Float, val groundY: Float, val ahead: GroundPoint? = null, val track: ArMeshData? = null)

/**
 * The AR engine without any graphics: it ties ARCore's free-floating world to the real one and decides what to draw. The GL renderer
 * only feeds it camera readings and draws what it returns, which is also what lets the whole thing be simulated and tested on a computer.
 *
 * The road line is fixed in ARCore's world. Moving, waving or turning the phone moves the camera through that world, never the line;
 * only the slow, deliberate corrections from the route-locked position and the road's heading adjust it.
 */
class ArWorld(val aligner: ArGeoAligner = ArGeoAligner()) {
    private class Sample(val timeMillis: Long, val along: Double, val x: Double, val z: Double)
    private val alongHistory = ArrayDeque<Sample>()
    private var alongEstimate = Double.NaN
    private val netWindow = ArrayDeque<Triple<Long, Double, Double>>()
    private var alongAtMillis = 0L
    private var lastArPosition: DoubleArray? = null
    private var lateralMeters = 0.0
    private var lateralAtMillis = 0L

    /** Feeds one ARCore camera reading together with where the vehicle is on the route (the road's own position and direction). */
    fun align(reading: ArCameraReading, compass: Double?, compassUp: Double?, geometry: RouteGeometry?, alongNowMeters: Double, speedMps: Double, rawGps: GeoPoint? = null, rawAccuracy: Double = 30.0) {
        val g = geometry ?: return
        // How far along the road they are: ARCore measures each step to the centimetre, a position fix is only good to a few metres and a reported speed can be low.
        // So progress follows ARCore's own movement and the fixes only correct it, slowly, in either direction.
        val dtAlong = ((reading.timeMillis - alongAtMillis).coerceIn(1L, 500L)) / 1000.0
        val step = lastArPosition?.let { hypot(reading.position[0] - it[0], reading.position[2] - it[1]) } ?: 0.0
        lastArPosition = doubleArrayOf(reading.position[0], reading.position[2])
        alongAtMillis = reading.timeMillis
        val fixAlong = alongNowMeters.coerceIn(0.0, g.length)
        // Where the raw position falls on the road: noisy by a few metres but not biased by a low reported speed, unlike the tracked progress.
        val rawAlong = rawGps?.let { com.rohanc.navgate.core.projectPath(it, g.points, minAlong = max(0.0, fixAlong - 40.0), maxAlong = fixAlong + 40.0)?.alongMeters }
        val reference = (rawAlong ?: fixAlong).coerceIn(0.0, g.length)
        // Steps count only while really walking (net movement over the last two seconds): phone sway while standing must not look like progress.
        netWindow.addLast(Triple(reading.timeMillis, reading.position[0], reading.position[2]))
        while (netWindow.size > 1 && reading.timeMillis - netWindow.first().first > 2000) netWindow.removeFirst()
        val net = netWindow.first().let { f -> val span = (reading.timeMillis - f.first) / 1000.0; if (span > 0.5) hypot(reading.position[0] - f.second, reading.position[2] - f.third) / span else 0.0 }
        alongEstimate = if (alongEstimate.isNaN()) reference else alongEstimate + (if (step in 0.003..1.0 && net > 0.4) step else 0.0)
        alongEstimate += (reference - alongEstimate) * (1.0 - Math.exp(-dtAlong / ALONG_PULL_SECONDS))
        // Never trust the dead reckoning far from the position fixes: it cannot run more than 30 m ahead of or behind them.
        alongEstimate = alongEstimate.coerceIn(reference - 30.0, reference + 30.0)
        val along = alongEstimate.coerceIn(0.0, g.length)
        // The heading the aligner compares ARCore's travel with must cover the same stretch of road: the chord of the last few seconds, not
        // the bearing at this instant, which differs on a bend and would make a steady walker look like a changing heading.
        alongHistory.addLast(Sample(reading.timeMillis, along, reading.position[0], reading.position[2]))
        while (alongHistory.size > 1 && reading.timeMillis - alongHistory.first().timeMillis > aligner.windowMillis) alongHistory.removeFirst()
        val first = alongHistory.first()
        // How far the phone really moved over that window is known precisely from ARCore; the position along the road is only as good as GPS.
        // So the stretch of road compared is the one that distance long, ending where the road position says the user is now.
        val walked = hypot(reading.position[0] - first.x, reading.position[2] - first.z)
        val then = (along - walked).coerceAtLeast(0.0)
        val chordNow = if (walked > 3.0 && along - then > 3.0) g.pointAt(then).bearingTo(g.pointAt(along)) else g.bearingAt(along)
        // On a bend the road's direction changes while the walker's path is smoothed by sway and the position fix is a few metres off, so a chord measured there
        // is a poor reference. The road's heading is learned from straight stretches only; on a bend the compass and the existing alignment carry on.
        val bendDegrees = g.bearingAt(then)?.let { a -> g.bearingAt(along)?.let { b -> Math.abs(((b - a + 540.0) % 360.0) - 180.0) } } ?: 0.0
        val chord = chordNow; val straight = bendDegrees <= 18.0
        // Where the user stands against the road: the pavement is a few metres from its centre line, so the line is drawn on the road, not under their feet.
        // Learned slowly from the real position (smoothed over about 25 s, clamped, and only part-trusted: a GPS fix is good to a few metres).
        val road = g.pointAt(along); val bearing = g.bearingAt(along)
        if (rawGps != null && bearing != null && rawAccuracy <= 15.0 && speedMps > 0.4) {
            val (e, n) = rawGps.offsetFrom(road); val r = Math.toRadians(bearing)
            val across = (e * cos(r) - n * sin(r)).coerceIn(-8.0, 8.0)            // positive: to the right of the way they are going
            val dt = ((reading.timeMillis - lateralAtMillis).coerceIn(1L, 500L)) / 1000.0
            lateralMeters += (across - lateralMeters) * (1.0 - Math.exp(-dt / 25.0))
        }
        lateralAtMillis = reading.timeMillis
        val offset = (lateralMeters * 0.7).coerceIn(-4.0, 4.0)
        val anchor = if (bearing != null && kotlin.math.abs(offset) > 0.3) road.offset(offset, bearing + 90.0) else road
        aligner.update(reading, compass, anchor, 3.0, chord, speedMps, compassUp, learnHeading = straight)
    }

    /** How far to trust the alignment right now (0 to 1). */
    val confidence: Double get() = aligner.confidence

    /** ARCore lost tracking and came back: its world may have shifted, so start the alignment again. */
    fun relocalized() { aligner.reset(); alongEstimate = Double.NaN; lastArPosition = null; netWindow.clear(); alongHistory.clear() }

    /** The ribbon, turn arrow and destination ring for the road ahead, or null until the world is aligned. */
    fun buildScene(path: List<GeoPoint>, roadEndMeters: Double, turnKind: String?, groundY: Double, camera: GroundPoint? = null): ArScene? {
        if (path.size < 2 || !aligner.aligned) return null
        val mapped = path.mapNotNull { aligner.toGround(it) }
        if (mapped.size < 2) return null
        // The line always starts under the phone, however far the alignment is off, and eases back onto the road ahead.
        val ground = camera?.let { anchorToCamera(mapped, it) } ?: mapped
        val y = groundY + 0.02
        val ribbon = ArMesh.ribbon(smoothGround(ground), y, 1.0)
        // Both: the road line sits on the road, and a slimmer track runs from under the feet along the same way, so it feels like walking in a lane.
        val track = camera?.let { ArMesh.ribbon(smoothGround(anchorToCamera(mapped, it, blendMeters = 12.0, hold = 1.0)), y - 0.004, 0.30) }
        var turn: GroundPoint? = null; var arrow: ArMeshData? = null; var outline: ArMeshData? = null
        val arrival = turnKind == "Arrive"
        // Walk the path to the end of this road and read the direction in and out of it.
        var travelled = 0.0; var index = ground.lastIndex
        for (i in 1 until ground.size) { travelled += hypot(ground[i].x - ground[i - 1].x, ground[i].z - ground[i - 1].z); if (travelled >= roadEndMeters) { index = i; break } }
        if (turnKind != null && index >= 2) {
            turn = ground[index]
            fun unit(a: GroundPoint, b: GroundPoint): GroundPoint { val dx = b.x - a.x; val dz = b.z - a.z; val n = hypot(dx, dz).takeIf { it > 1e-6 } ?: 1.0; return GroundPoint(dx / n, dz / n) }
            val incoming = unit(ground[max(0, index - 3)], ground[index])
            val outgoing = if (index < ground.lastIndex) unit(ground[index], ground[min(ground.lastIndex, index + 3)]) else incoming
            if (arrival) { arrow = ArMesh.ring(turn, y + 0.03, 2.2, 0.55); outline = ArMesh.ring(turn, y + 0.025, 2.2, 1.0) }
            else { arrow = ArMesh.turnArrow(turn, incoming, outgoing, y + 0.03); outline = ArMesh.turnArrow(turn, incoming, outgoing, y + 0.025, 1.6) }
        }
        // A point about 8 m down the line, which says which way the road lies from where the phone is pointing.
        var walked = 0.0; var aheadPoint: GroundPoint? = null
        for (i in 1 until ground.size) { walked += hypot(ground[i].x - ground[i - 1].x, ground[i].z - ground[i - 1].z); if (walked >= 8.0) { aheadPoint = ground[i]; break } }
        return ArScene(ribbon, arrow, outline, turn, arrival, roadEndMeters.toFloat(), y.toFloat(), aheadPoint ?: ground.last(), track)
    }
}

/**
 * The angle, in degrees, from where the phone points to [target] on the ground: positive when the target lies to the right.
 * [forwardX]/[forwardZ] is the camera's horizontal heading in ground space (east-ish x, south-ish z), so "right" is (-z, x).
 */
fun bearingToTarget(camera: GroundPoint, forwardX: Double, forwardZ: Double, target: GroundPoint): Double {
    val n = hypot(forwardX, forwardZ); if (n < 1e-6) return 0.0
    val fx = forwardX / n; val fz = forwardZ / n
    val dx = target.x - camera.x; val dz = target.z - camera.z
    return Math.toDegrees(kotlin.math.atan2(dx * -fz + dz * fx, dx * fx + dz * fz))
}

/**
 * Rounds off the corners of [path] (corner cutting, two passes) so a ribbon built from it never folds over itself where the line bends
 * sharply, for instance where the start is pulled under the phone. The ends stay where they are.
 */
fun smoothGround(path: List<GroundPoint>, passes: Int = 2): List<GroundPoint> {
    var p = path
    repeat(passes) {
        if (p.size < 3) return p
        val out = ArrayList<GroundPoint>(p.size * 2); out += p.first()
        for (i in 0 until p.lastIndex) {
            val a = p[i]; val b = p[i + 1]
            out += GroundPoint(a.x * 0.75 + b.x * 0.25, a.z * 0.75 + b.z * 0.25); out += GroundPoint(a.x * 0.25 + b.x * 0.75, a.z * 0.25 + b.z * 0.75)
        }
        out += p.last(); p = out
    }
    return p
}

/**
 * Re-bases [path] so it begins exactly at [camera] (the point on the ground under the phone). Vertices behind the phone are dropped, and
 * the sideways error between the phone and the nearest point of the road is blended out over [blendMeters] so the line settles on the road.
 */
/** [hold] is the part of the sideways error kept at the start of the line: 1 starts it under the phone, 0 leaves the line on the road itself. It is mostly on the road now, so it reads as placed on the road. */
fun anchorToCamera(path: List<GroundPoint>, camera: GroundPoint, blendMeters: Double = 8.0, maxOffset: Double = 8.0, hold: Double = 0.2): List<GroundPoint> {
    if (path.size < 2) return path
    var best = Double.MAX_VALUE; var bi = 0; var bt = 0.0
    for (i in 0 until path.lastIndex) {
        val ax = path[i].x; val az = path[i].z; val dx = path[i + 1].x - ax; val dz = path[i + 1].z - az
        val len2 = dx * dx + dz * dz; val t = if (len2 < 1e-9) 0.0 else (((camera.x - ax) * dx + (camera.z - az) * dz) / len2).coerceIn(0.0, 1.0)
        val d = hypot(camera.x - (ax + dx * t), camera.z - (az + dz * t))
        if (d < best) { best = d; bi = i; bt = t }
    }
    val nx = path[bi].x + (path[bi + 1].x - path[bi].x) * bt; val nz = path[bi].z + (path[bi + 1].z - path[bi].z) * bt
    var ox = (camera.x - nx) * hold; var oz = (camera.z - nz) * hold
    val mag = hypot(ox, oz); if (mag > maxOffset) { ox *= maxOffset / mag; oz *= maxOffset / mag }
    val out = ArrayList<GroundPoint>(path.size - bi + 1)
    out.add(GroundPoint(nx + ox, nz + oz))
    var along = 0.0; var px = nx; var pz = nz
    for (k in bi + 1 until path.size) {
        along += hypot(path[k].x - px, path[k].z - pz); px = path[k].x; pz = path[k].z
        val w = (1.0 - along / blendMeters).coerceAtLeast(0.0)
        out.add(GroundPoint(px + ox * w, pz + oz * w))
    }
    return out
}

/** Carries the camera pose forward between ARCore frames at the speed it was last moving, so the line can be redrawn at screen rate. */
object PosePredictor {
    /** Position and rotation [fraction] of a frame interval after [latest]. [fraction] is clamped to 0..1.6 so a stall never flings the view away. */
    fun predict(previousPos: DoubleArray, previousRot: DoubleArray, latestPos: DoubleArray, latestRot: DoubleArray, fraction: Double): Pair<DoubleArray, DoubleArray> {
        val k = fraction.coerceIn(0.0, 1.6)
        val position = DoubleArray(3) { latestPos[it] + (latestPos[it] - previousPos[it]) * k }
        return position to ArMath.extrapolate(previousRot, latestRot, 1.0 + k)
    }
}

/**
 * Resamples a camera image (YUV 4:2:0, in the sensor's orientation) into an upright RGB picture the shape of the screen, so a detector
 * sees what the user sees and its rectangles come out in screen coordinates. [forward] is ARCore's image -> screen mapping of three
 * corner points (0,0), (1,0), (0,1), as six floats.
 */
object YuvResampler {
    fun toScreenArgb(y: ByteArray, u: ByteArray, v: ByteArray, width: Int, height: Int, yRow: Int, uvRow: Int, uvPixel: Int, forward: FloatArray, outW: Int, outH: Int): IntArray? {
        val cx = forward[0]; val cy = forward[1]
        val ax = forward[2] - cx; val ay = forward[3] - cy
        val bx = forward[4] - cx; val by = forward[5] - cy
        val det = ax * by - bx * ay
        if (kotlin.math.abs(det) < 1e-6f) return null
        val pixels = IntArray(outW * outH)
        for (oy in 0 until outH) {
            val vy = (oy + 0.5f) / outH - cy
            for (ox in 0 until outW) {
                val vx = (ox + 0.5f) / outW - cx
                val ix = (vx * by - bx * vy) / det; val iy = (ax * vy - vx * ay) / det
                if (ix < 0f || iy < 0f || ix >= 1f || iy >= 1f) continue
                val px = (ix * width).toInt(); val py = (iy * height).toInt()
                val yy = (y[py * yRow + px].toInt() and 0xFF)
                val uvIndex = (py shr 1) * uvRow + (px shr 1) * uvPixel
                val uu = (u[uvIndex].toInt() and 0xFF) - 128; val vv = (v[uvIndex].toInt() and 0xFF) - 128
                val r = (yy + 1.402f * vv).toInt().coerceIn(0, 255); val g = (yy - 0.344f * uu - 0.714f * vv).toInt().coerceIn(0, 255); val b = (yy + 1.772f * uu).toInt().coerceIn(0, 255)
                pixels[oy * outW + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return pixels
    }
}

/**
 * Where the ground is, as a height in ARCore's own world. The ground does not move when the user lifts or lowers the phone, so it is
 * stored as a world height (not as "phone height above ground"): everything drawn on the ground stays on it however the phone moves.
 * Until ARCore has measured the ground it assumes a sensible phone height once, then holds it.
 */
class GroundEstimator {
    private var y: Double? = null
    private val samples = ArrayDeque<Double>()
    var found = false; private set

    /** The ground's world height. Before any measurement the first call fixes it at an assumed phone height below the camera. */
    fun groundY(cameraY: Double, assumedPhoneHeight: Double): Double = y ?: (cameraY - assumedPhoneHeight).also { y = it }

    /** A measured ground height (a plane hit-test or plane position). Implausible readings (not 0.5 to 2.8 m below the phone) are ignored. */
    fun addSample(groundWorldY: Double, cameraY: Double) {
        val below = cameraY - groundWorldY
        if (!below.isFinite() || below !in 0.5..2.8) return
        samples.addLast(groundWorldY); while (samples.size > 9) samples.removeFirst()
        if (samples.size < 3) return
        val median = samples.sorted()[samples.size / 2]
        y = if (!found) median else y!! + (median - y!!) * 0.15
        found = true
    }

    fun phoneHeight(cameraY: Double, assumedPhoneHeight: Double) = cameraY - groundY(cameraY, assumedPhoneHeight)
    fun reset() { y = null; samples.clear(); found = false }
}

/** The "you are here" circle: only when the phone looks down at the feet, small, and on the ground just behind the phone where the body is. */
object FeetMarker {
    class Plan(val x: Double, val z: Double, val radius: Double, val alpha: Float)

    /** [forward]/[up] are the camera's axes in ARCore's world (y up). Null while the phone is held up and looking ahead. */
    fun plan(cameraX: Double, cameraZ: Double, forward: DoubleArray, up: DoubleArray): Plan? {
        val pitch = Math.toDegrees(Math.asin(forward[1].coerceIn(-1.0, 1.0)))     // 0 = level, -90 = straight down
        val t = ((-35.0 - pitch) / 25.0).coerceIn(0.0, 1.0)                        // fades in from 35 to 60 degrees below the horizon
        val alpha = (t * t * (3 - 2 * t)).toFloat()
        if (alpha < 0.02f) return null
        // The body is behind the phone, away from where the lens points; looking straight down, that is opposite the top edge.
        val fx = forward[0]; val fz = forward[2]
        val (dx, dz) = if (hypot(fx, fz) > 0.35) Pair(fx, fz) else Pair(up[0], up[2])
        val n = hypot(dx, dz).takeIf { it > 1e-6 } ?: 1.0
        return Plan(cameraX - dx / n * 0.28, cameraZ - dz / n * 0.28, 0.5, alpha)
    }
}

private const val ALONG_PULL_SECONDS = 25.0
