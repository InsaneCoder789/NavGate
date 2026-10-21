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
    val yawDegrees: Double? get() = theta?.let { Math.toDegrees(it) }

    fun reset() { theta = null; reference = null; trail.clear() }

    /**
     * [compass] is the camera's true azimuth from the rotation sensor (null when unreliable, e.g. looking straight up/down),
     * [gps]/[gpsAccuracy] the fused position, [courseDegrees]/[speedMps] the fused direction of travel.
     */
    fun update(reading: ArCameraReading, compass: Double?, gps: GeoPoint?, gpsAccuracy: Double, courseDegrees: Double?, speedMps: Double): GeoCamera? {
        val fx = reading.forward[0]; val fz = reading.forward[2]
        val horizontal = hypot(fx, fz)
        if (horizontal > 0.35 && compass != null) {
            val sample = Math.toRadians(compass) - atan2(fx, -fz)
            theta = theta?.let { blend(it, sample, 0.02) } ?: sample
        }
        // Refine yaw from travel: ARCore's displacement over the last seconds against the GPS course.
        trail.addLast(Triple(reading.timeMillis, reading.position[0], reading.position[2]))
        while (trail.size > 1 && reading.timeMillis - trail.first().first > 3000) trail.removeFirst()
        if (courseDegrees != null && speedMps > 1.5 && trail.size > 2) {
            val dx = reading.position[0] - trail.first().second; val dz = reading.position[2] - trail.first().third
            if (hypot(dx, dz) > 4.0) theta = theta?.let { blend(it, Math.toRadians(courseDegrees) - atan2(dx, -dz), 0.05) } ?: (Math.toRadians(courseDegrees) - atan2(dx, -dz))
        }
        val t = theta ?: return null
        val ref = reference ?: gps?.also { reference = it; anchorAr = doubleArrayOf(reading.position[0], reading.position[2]); anchorEast = 0.0; anchorNorth = 0.0 } ?: return null
        val moved = rotate(reading.position[0] - anchorAr[0], -(reading.position[2] - anchorAr[1]), t)
        var east = anchorEast + moved.first; var north = anchorNorth + moved.second
        if (gps != null && gpsAccuracy < 30) {
            val (ge, gn) = gps.offsetFrom(ref)
            val gain = if (gpsAccuracy < 8) 0.03 else 0.008
            anchorEast += (ge - east) * gain; anchorNorth += (gn - north) * gain
            east += (ge - east) * gain; north += (gn - north) * gain
        }
        val metersPerDegree = 111_320.0
        val position = GeoPoint(ref.latitude + north / metersPerDegree, ref.longitude + east / (metersPerDegree * cos(Math.toRadians(ref.latitude))))
        fun toWorld(v: DoubleArray): DoubleArray { val (e, n) = rotate(v[0], -v[2], t); return doubleArrayOf(e, n, v[1]) }
        return GeoCamera(position, CameraBasis(toWorld(reading.forward), toWorld(reading.right), toWorld(reading.up)))
    }

    private fun rotate(east: Double, north: Double, t: Double) = (east * cos(t) + north * sin(t)) to (-east * sin(t) + north * cos(t))

    private fun blend(current: Double, sample: Double, gain: Double): Double {
        val diff = atan2(sin(sample - current), cos(sample - current))
        return current + diff * gain
    }
}
