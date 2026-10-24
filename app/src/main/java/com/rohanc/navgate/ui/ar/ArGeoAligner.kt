package com.rohanc.navgate.ui.ar

import com.rohanc.navgate.core.GeoPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** One ARCore camera reading in ARCore's own world frame (metres, y up, arbitrary yaw). */
class ArCameraReading(val position: DoubleArray, val forward: DoubleArray, val right: DoubleArray, val up: DoubleArray, val timeMillis: Long)

/** The camera expressed in the real world. */
class GeoCamera(val position: GeoPoint, val basis: CameraBasis)

/**
 * Ties ARCore's smooth but free-floating tracking to the real world.
 *  - Yaw: the compass supplies the rotation between ARCore's world and true north; a slow circular filter averages it,
 *    and while driving or walking the GPS course (compared with ARCore's own travel direction) refines it.
 *  - Position: ARCore motion moves the camera between GPS fixes; the fused GPS position pulls it back gently, so the
 *    drawn line does not jitter yet cannot drift away from the road.
 * Call [reset] when ARCore loses tracking, because its world frame restarts.
 */
class ArGeoAligner {
    private var theta: Double? = null // radians, AR azimuth -> true azimuth
    private var anchorAr = DoubleArray(2)
    private var anchorEast = 0.0
    private var anchorNorth = 0.0
    private var reference: GeoPoint? = null
    private val trail = ArrayDeque<Triple<Long, Double, Double>>()
    private var lastTimeMs = 0L
    private var lastForward: DoubleArray? = null
    private var compassSeconds = 0.0
    private var roadHeadingSeconds = 0.0
    private var lastGpsAccuracy = 50.0

    /**
     * How far to trust the alignment, 0 to 1. A compass alone is a guess (about 0.35); the road's own heading, seen while actually walking
     * along it, raises that to full confidence within a few seconds. Poor position accuracy lowers it. The renderer fades the line with
     * this, and the screen asks the user to walk a few steps instead of pretending to be exact.
     */
    val confidence: Double get() {
        if (!aligned) return 0.0
        val accuracy = (1.0 - ((lastGpsAccuracy - 8.0) / 17.0).coerceIn(0.0, 1.0) * 0.4)
        return (0.35 + 0.65 * (roadHeadingSeconds / 6.0).coerceAtMost(1.0)) * accuracy
    }
    val yawDegrees: Double? get() = theta?.let { Math.toDegrees(it) }
    private var camGeo: GeoPoint? = null
    private var camX = 0.0
    private var camZ = 0.0
    /** True once the compass and the road have agreed on which way ARCore's world points. */
    val aligned: Boolean get() = theta != null && reference != null

    /** Where a real-world point is in ARCore's world, on the ground plane; null until [update] has produced a camera position. */
    fun toGround(point: GeoPoint): GroundPoint? {
        val t = theta ?: return null; val c = camGeo ?: return null
        val (e, n) = point.offsetFrom(c)
        val e0 = e * cos(t) - n * sin(t); val n0 = e * sin(t) + n * cos(t)
        return GroundPoint(camX + e0, camZ - n0)
    }

    fun reset() { theta = null; reference = null; trail.clear(); camGeo = null; lastForward = null; compassSeconds = 0.0; roadHeadingSeconds = 0.0 }

    /**
     * [compass] is the camera's true azimuth from the rotation sensor (null when unreliable, e.g. looking straight up/down),
     * [gps]/[gpsAccuracy] the fused position, [courseDegrees]/[speedMps] the fused direction of travel.
     */
    fun update(reading: ArCameraReading, compass: Double?, gps: GeoPoint?, gpsAccuracy: Double, courseDegrees: Double?, speedMps: Double, compassUp: Double? = null): GeoCamera? {
        val fx = reading.forward[0]; val fz = reading.forward[2]
        val horizontal = hypot(fx, fz)
        val frameDt = ((reading.timeMillis - lastTimeMs).coerceIn(1L, 500L)) / 1000.0
        // Walking pace (about 1.4 m/s) counts as moving: the road's own heading is the best compass there is.
        val travelling = courseDegrees != null && speedMps > 0.6
        // The compass is only trusted while the phone is steady; a quick swing makes its reading lag the camera.
        val swing = lastForward?.let { Math.toDegrees(Math.acos((it[0] * fx + it[1] * reading.forward[1] + it[2] * fz).coerceIn(-1.0, 1.0))) / frameDt } ?: 0.0
        lastForward = doubleArrayOf(fx, reading.forward[1], fz)
        val steady = swing < 45.0
        // Looking at the ground (a flat phone, a downward mount) the top edge of the phone gives the heading instead of the lens.
        val ux = reading.up[0]; val uz = reading.up[2]
        val useUp = horizontal <= 0.35 && compassUp != null && hypot(ux, uz) > 0.35
        if (steady && ((horizontal > 0.35 && compass != null) || useUp)) {
            val sample = if (useUp) Math.toRadians(compassUp!!) - atan2(ux, -uz) else Math.toRadians(compass!!) - atan2(fx, -fz)
            // The compass is noisy and magnetically disturbed near cars, so it carries less weight once the road itself gives a heading.
            compassSeconds += frameDt
            // Quick to settle at the start, then very slow: standing still, the compass is all there is and it must not shake the line.
            val tau = if (travelling) 40.0 else if (compassSeconds < 4.0) 0.8 else 15.0
            theta = theta?.let { blend(it, sample, 1.0 - Math.exp(-frameDt / tau)) } ?: sample
        }
        // Refine yaw from travel: ARCore's displacement over the last seconds against the GPS course.
        trail.addLast(Triple(reading.timeMillis, reading.position[0], reading.position[2]))
        while (trail.size > 1 && reading.timeMillis - trail.first().first > 3000) trail.removeFirst()
        if (travelling && trail.size > 2) {
            val dx = reading.position[0] - trail.first().second; val dz = reading.position[2] - trail.first().third
            if (hypot(dx, dz) > 2.5) { roadHeadingSeconds = minOf(8.0, roadHeadingSeconds + frameDt) }
            if (hypot(dx, dz) > 2.5) theta = theta?.let { blend(it, Math.toRadians(courseDegrees!!) - atan2(dx, -dz), 1.0 - Math.exp(-frameDt / 1.2)) } ?: (Math.toRadians(courseDegrees) - atan2(dx, -dz))
        }
        if (!(travelling && trail.size > 2)) roadHeadingSeconds = maxOf(0.0, roadHeadingSeconds - frameDt * 0.05)
        lastGpsAccuracy = gpsAccuracy
        val t = theta ?: return null
        val ref = reference ?: gps?.also { reference = it; anchorAr = doubleArrayOf(reading.position[0], reading.position[2]); anchorEast = 0.0; anchorNorth = 0.0 } ?: return null
        val moved = rotate(reading.position[0] - anchorAr[0], -(reading.position[2] - anchorAr[1]), t)
        var east = anchorEast + moved.first; var north = anchorNorth + moved.second
        if (gps != null && gpsAccuracy < 30) {
            val (ge, gn) = gps.offsetFrom(ref)
            // Time-based, so the pull is the same at 30, 60 or 120 frames a second: ARCore's smooth motion carries the line between corrections.
            val dtSec = ((reading.timeMillis - lastTimeMs).coerceIn(1L, 500L)) / 1000.0
            // The line is fixed in ARCore's world, so the position fix may only nudge it, and the two directions are treated differently:
            //  - across the road the user is, by definition, on the road line: that is held firmly, so the line stays under their feet;
            //  - along the road a fix is only good to a few metres and a few metres either way cannot be seen, so it is followed very slowly,
            //    and hardly at all when standing still. This is what stops the line sliding about on GPS wobble.
            val course = Math.toRadians(courseDegrees ?: 0.0)
            val errE = ge - east; val errN = gn - north
            val along = errE * sin(course) + errN * cos(course); val across = errE * cos(course) - errN * sin(course)
            val slow = if (gpsAccuracy < 8) 1.0 else 2.0
            val tauAlong = (if (speedMps < 0.3) 90.0 else 25.0) * slow
            val tauAcross = 3.0 * slow
            val moveAlong = along * (1.0 - Math.exp(-dtSec / tauAlong)); val moveAcross = across * (1.0 - Math.exp(-dtSec / tauAcross))
            val dE = moveAlong * sin(course) + moveAcross * cos(course); val dN = moveAlong * cos(course) - moveAcross * sin(course)
            anchorEast += dE; anchorNorth += dN
            east += dE; north += dN
        }
        val metersPerDegree = 111_320.0
        val position = GeoPoint(ref.latitude + north / metersPerDegree, ref.longitude + east / (metersPerDegree * cos(Math.toRadians(ref.latitude))))
        fun toWorld(v: DoubleArray): DoubleArray { val (e, n) = rotate(v[0], -v[2], t); return doubleArrayOf(e, n, v[1]) }
        camGeo = position; camX = reading.position[0]; camZ = reading.position[2]; lastTimeMs = reading.timeMillis
        return GeoCamera(position, CameraBasis(toWorld(reading.forward), toWorld(reading.right), toWorld(reading.up)))
    }

    private fun rotate(east: Double, north: Double, t: Double) = (east * cos(t) + north * sin(t)) to (-east * sin(t) + north * cos(t))

    private fun blend(current: Double, sample: Double, gain: Double): Double {
        val diff = atan2(sin(sample - current), cos(sample - current))
        return current + diff * gain
    }
}
