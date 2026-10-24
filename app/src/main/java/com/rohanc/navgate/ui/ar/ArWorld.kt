package com.rohanc.navgate.ui.ar

import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.core.RouteGeometry
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** What is drawn on the road this frame, all in ARCore world coordinates. */
class ArScene(val ribbon: ArMeshData, val arrow: ArMeshData?, val arrowOutline: ArMeshData?, val turnPoint: GroundPoint?, val turnIsArrival: Boolean, val roadEnd: Float, val groundY: Float)

/**
 * The AR engine without any graphics: it ties ARCore's free-floating world to the real one and decides what to draw. The GL renderer
 * only feeds it camera readings and draws what it returns, which is also what lets the whole thing be simulated and tested on a computer.
 *
 * The road line is fixed in ARCore's world. Moving, waving or turning the phone moves the camera through that world, never the line;
 * only the slow, deliberate corrections from the route-locked position and the road's heading adjust it.
 */
class ArWorld {
    val aligner = ArGeoAligner()

    /** Feeds one ARCore camera reading together with where the vehicle is on the route (the road's own position and direction). */
    fun align(reading: ArCameraReading, compass: Double?, compassUp: Double?, geometry: RouteGeometry?, alongNowMeters: Double, speedMps: Double) {
        val g = geometry ?: return
        val along = alongNowMeters.coerceIn(0.0, g.length)
        aligner.update(reading, compass, g.pointAt(along), 3.0, g.bearingAt(along), speedMps, compassUp)
    }

    /** How far to trust the alignment right now (0 to 1). */
    val confidence: Double get() = aligner.confidence

    /** ARCore lost tracking and came back: its world may have shifted, so start the alignment again. */
    fun relocalized() = aligner.reset()

    /** The ribbon, turn arrow and destination ring for the road ahead, or null until the world is aligned. */
    fun buildScene(path: List<GeoPoint>, roadEndMeters: Double, turnKind: String?, groundY: Double): ArScene? {
        if (path.size < 2 || !aligner.aligned) return null
        val ground = path.mapNotNull { aligner.toGround(it) }
        if (ground.size < 2) return null
        val y = groundY + 0.02
        val ribbon = ArMesh.ribbon(ground, y, 0.8)
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
        return ArScene(ribbon, arrow, outline, turn, arrival, roadEndMeters.toFloat(), y.toFloat())
    }
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
