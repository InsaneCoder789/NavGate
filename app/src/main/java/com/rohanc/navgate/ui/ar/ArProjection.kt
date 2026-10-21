package com.rohanc.navgate.ui.ar

import com.rohanc.navgate.core.GeoPoint
import kotlin.math.cos
import kotlin.math.tan

/** The rear camera's axes in a true-north East/North/Up frame (unit vectors). */
data class CameraBasis(val forward: DoubleArray, val right: DoubleArray, val up: DoubleArray) {
    val headingDegrees: Double get() = (Math.toDegrees(kotlin.math.atan2(forward[0], forward[1])) + 360.0) % 360.0
    val pitchDegrees: Double get() = Math.toDegrees(kotlin.math.asin(forward[2].coerceIn(-1.0, 1.0)))
}

/** Pinhole camera over flat ground: where a point on the road appears in the camera image. */
class ArProjection(val widthPx: Float, val heightPx: Float, val focalYPx: Double, val focalXPx: Double = focalYPx, val eyeHeightMeters: Double = 1.45) {
    constructor(widthPx: Float, heightPx: Float, verticalFovDegrees: Double, eyeHeightMeters: Double = 1.45) :
        this(widthPx, heightPx, (heightPx / 2.0) / tan(Math.toRadians(verticalFovDegrees / 2)), (heightPx / 2.0) / tan(Math.toRadians(verticalFovDegrees / 2)), eyeHeightMeters)


    /** [east], [north] are metres from the camera; the point lies on the ground, [eyeHeightMeters] below it. Null when behind the camera. */
    fun ground(basis: CameraBasis, east: Double, north: Double, groundDrop: Double = eyeHeightMeters, lift: Double = 0.0): Pair<Float, Float>? {
        val up = -groundDrop + lift
        val z = east * basis.forward[0] + north * basis.forward[1] + up * basis.forward[2]
        if (z < 0.4) return null
        val x = east * basis.right[0] + north * basis.right[1] + up * basis.right[2]
        val y = east * basis.up[0] + north * basis.up[1] + up * basis.up[2]
        return (widthPx / 2 + focalXPx * x / z).toFloat() to (heightPx / 2 - focalYPx * y / z).toFloat()
    }
}

/** Metres east and north of [origin]. */
fun GeoPoint.offsetFrom(origin: GeoPoint): Pair<Double, Double> {
    val north = Math.toRadians(latitude - origin.latitude) * 6_371_000.0
    val east = Math.toRadians(longitude - origin.longitude) * 6_371_000.0 * cos(Math.toRadians(origin.latitude))
    return east to north
}

/** Rotation-vector matrix (device -> magnetic ENU, row-major 3x3) to a true-north camera basis for the back camera. */
fun cameraBasis(m: FloatArray, declinationDegrees: Double): CameraBasis {
    val d = Math.toRadians(declinationDegrees)
    fun rot(e: Double, n: Double, u: Double) = doubleArrayOf(e * cos(d) + n * kotlin.math.sin(d), -e * kotlin.math.sin(d) + n * cos(d), u)
    // Device axes in the world: X right, Y up the screen, Z out of the screen; the back camera looks along -Z.
    return CameraBasis(
        forward = rot(-m[2].toDouble(), -m[5].toDouble(), -m[8].toDouble()),
        right = rot(m[0].toDouble(), m[3].toDouble(), m[6].toDouble()),
        up = rot(m[1].toDouble(), m[4].toDouble(), m[7].toDouble()),
    )
}
