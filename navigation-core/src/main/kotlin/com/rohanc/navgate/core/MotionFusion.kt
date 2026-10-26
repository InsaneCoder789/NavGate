package com.rohanc.navgate.core

import kotlin.math.*

/** One GPS fix. Course and speed are optional because providers omit them when stationary or unsure. */
data class GpsSample(val point: GeoPoint, val accuracyMeters: Double, val speedMps: Double?, val courseDegrees: Double?, val elapsedMillis: Long)

/**
 * One inertial reading already rotated into the world frame (east, north, up), so the phone can sit in any
 * cradle orientation. Acceleration has gravity removed; yaw rate is the turn rate about the vertical axis
 * (counter-clockwise positive). [deviceHeadingDegrees] is the true-north heading of the phone's own forward axis.
 */
data class ImuSample(val elapsedMillis: Long, val eastAccel: Double, val northAccel: Double, val yawRateRadPerSec: Double,
    val deviceHeadingDegrees: Double?, val compassReliable: Boolean)

enum class CalibrationState { Calibrating, Good, CompassNeedsFigureEight }

data class MotionEstimate(
    val point: GeoPoint, val speedMps: Double, val headingDegrees: Double?, val forwardAccel: Double, val lateralAccel: Double,
    val accuracyMeters: Double, val calibration: CalibrationState, val stationary: Boolean, val usingDeadReckoning: Boolean, val elapsedMillis: Long,
)

/**
 * Fuses GPS with accelerometer and gyroscope into a smooth position, speed and heading.
 *
 * - Two independent constant-velocity Kalman filters (east, north). Inertial acceleration drives the prediction between
 *   GPS fixes; GPS position and velocity correct it. During a signal drop it keeps dead-reckoning from inertia alone.
 * - Heading integrates the gyroscope, and is corrected by GPS course while moving.
 * - Recalibration is continuous: while stationary it re-learns the accelerometer bias and gyroscope bias and forces velocity
 *   to zero; while moving it learns the offset between the phone's compass and the vehicle's direction of travel.
 */
class MotionFusion(private val config: Config = Config()) {
    data class Config(
        val accelNoise: Double = 0.7, val maxAccel: Double = 6.0, val accelSmoothing: Double = 0.25,
        val minGpsSigma: Double = 2.5, val velocitySigma: Double = 1.0, val courseMinSpeed: Double = 3.0,
        val stationarySpeed: Double = 0.5, val stationaryHoldMillis: Long = 1_200, val maxPredictMillis: Long = 1_500,
        val resetDistanceMeters: Double = 20_000.0, val maxPlausibleSpeed: Double = 90.0,
    )

    private class Axis { var p = 0.0; var v = 0.0; var p00 = 1e6; var p01 = 0.0; var p11 = 25.0
        fun predict(dt: Double, a: Double, qa: Double) {
            p += v * dt + 0.5 * a * dt * dt; v += a * dt
            val n00 = p00 + dt * (2 * p01 + dt * p11) + qa * dt.pow(4) / 4
            val n01 = p01 + dt * p11 + qa * dt.pow(3) / 2
            val n11 = p11 + qa * dt * dt
            p00 = n00; p01 = n01; p11 = n11
        }
        fun updatePosition(z: Double, r: Double) {
            val s = p00 + r; val k0 = p00 / s; val k1 = p01 / s; val y = z - p
            p += k0 * y; v += k1 * y
            val n00 = (1 - k0) * p00; val n01 = (1 - k0) * p01; val n11 = p11 - k1 * p01
            p00 = n00; p01 = n01; p11 = n11
        }
        fun updateVelocity(z: Double, r: Double) {
            val s = p11 + r; val k0 = p01 / s; val k1 = p11 / s; val y = z - v
            p += k0 * y; v += k1 * y
            val n00 = p00 - k0 * p01; val n01 = (1 - k1) * p01; val n11 = (1 - k1) * p11
            p00 = n00; p01 = n01; p11 = n11
        }
    }

    private val east = Axis(); private val north = Axis()
    private var originLat = 0.0; private var originLon = 0.0; private var hasOrigin = false
    private var lastUpdate = 0L
    private var lastGps: GpsSample? = null
    private var gpsAccuracy = 50.0
    private var aE = 0.0; private var aN = 0.0
    private var biasE = 0.0; private var biasN = 0.0
    private var heading: Double? = null            // radians, compass bearing (clockwise from north)
    private var gyroBias = 0.0
    private var compassOffset: Double? = null     // vehicle heading minus phone compass heading, radians
    private var deviceHeading: Double? = null
    private var compassReliable = true
    private var unreliableSince: Long? = null
    private var stationarySince: Long? = null
    private var stationary = false
    private var startedAt: Long? = null
    private var courseAgreements = 0
    private var lastGpsAt = 0L
    /** Online estimate of how much worse than reported the GPS currently is (1 = as reported). Learned from how far fixes land from the prediction. */
    private var trust = 1.0

    @Synchronized fun reset() {
        hasOrigin = false; east.p = 0.0; east.v = 0.0; north.p = 0.0; north.v = 0.0; east.p00 = 1e6; north.p00 = 1e6
        east.p01 = 0.0; north.p01 = 0.0; east.p11 = 25.0; north.p11 = 25.0
        heading = null; lastGps = null; stationary = false; stationarySince = null; startedAt = null; courseAgreements = 0
        biasE = 0.0; biasN = 0.0; aE = 0.0; aN = 0.0; trust = 1.0
    }

    private fun toLocal(point: GeoPoint) = Pair((point.longitude - originLon) * METERS_PER_DEGREE * cos(Math.toRadians(originLat)), (point.latitude - originLat) * METERS_PER_DEGREE)
    private fun toGeo(e: Double, n: Double) = GeoPoint(originLat + n / METERS_PER_DEGREE, originLon + e / (METERS_PER_DEGREE * cos(Math.toRadians(originLat))))

    @Synchronized fun onGps(sample: GpsSample) {
        if (!sample.point.valid || !sample.accuracyMeters.isFinite() || sample.accuracyMeters <= 0) return
        val now = sample.elapsedMillis
        if (!hasOrigin || hypot(toLocal(sample.point).first, toLocal(sample.point).second) > config.resetDistanceMeters) {
            val keepHeading = heading
            reset(); originLat = sample.point.latitude; originLon = sample.point.longitude; hasOrigin = true; heading = keepHeading
        }
        if (startedAt == null) startedAt = now
        if (now > lastUpdate && lastUpdate != 0L) advance(now, 0.0, 0.0, 0.0)
        lastUpdate = max(lastUpdate, now)
        val (x, y) = toLocal(sample.point)
        val sigma = max(sample.accuracyMeters, config.minGpsSigma)
        // Implausible speed since the last fix (a position teleport) is a glitch, not movement.
        val impliedSpeed = lastGps?.let { prev -> val dtS = (now - lastGpsAt).coerceAtLeast(200L) / 1000.0; prev.point.distanceTo(sample.point) / dtS } ?: 0.0
        val teleport = impliedSpeed > config.maxPlausibleSpeed && east.p00 < 400
        // An outlier (far from the prediction, poor accuracy) is down-weighted instead of dragging the track.
        val gap = hypot(x - east.p, y - north.p)
        // Learned trust: if fixes keep landing farther from the prediction than their stated accuracy allows, believe them less.
        if (east.p00 < 400 && !teleport) {
            val nis = (gap * gap) / (2 * (east.p00 + sigma * sigma))
            trust = (0.85 * trust + 0.15 * max(nis, 0.3)).coerceIn(0.6, 5.0)
        }
        val inflate = (if (teleport) 12.0 else if (east.p00 < 400 && gap > 4 * sqrt(east.p00 + sigma * sigma)) 4.0 else 1.0) * sqrt(trust) * (if (stationary) 2.0 else 1.0)
        val first = lastGps == null
        east.updatePosition(x, (sigma * inflate).pow(2)); north.updatePosition(y, (sigma * inflate).pow(2))
        gpsAccuracy = sample.accuracyMeters
        val speed = sample.speedMps?.takeIf { it.isFinite() && it >= 0 }
        val course = sample.courseDegrees?.takeIf { it.isFinite() }
        if (speed != null) {
            if (speed >= config.courseMinSpeed && course != null) {
                val c = Math.toRadians(course)
                east.updateVelocity(speed * sin(c), config.velocitySigma.pow(2)); north.updateVelocity(speed * cos(c), config.velocitySigma.pow(2))
                correctHeading(c, now)
            } else if (speed < config.stationarySpeed) { east.updateVelocity(0.0, 0.3); north.updateVelocity(0.0, 0.3) }
            else { val h = heading; if (h != null) { east.updateVelocity(speed * sin(h), 4.0); north.updateVelocity(speed * cos(h), 4.0) } }
            trackStationary(speed, now)
        }
        if (first && heading == null && course != null && (speed ?: 0.0) >= 1.0) heading = Math.toRadians(course)
        lastGps = sample; lastGpsAt = now
    }

    @Synchronized fun onImu(sample: ImuSample) {
        if (!hasOrigin) return
        val now = sample.elapsedMillis
        if (now <= lastUpdate) return
        deviceHeading = sample.deviceHeadingDegrees?.takeIf { it.isFinite() }?.let { Math.toRadians(it) }
        compassReliable = sample.compassReliable
        if (!sample.compassReliable) { if (unreliableSince == null) unreliableSince = now } else unreliableSince = null
        // Low-pass the acceleration (road vibration) and remove the bias learned while stationary.
        val rawE = (sample.eastAccel - biasE).coerceIn(-config.maxAccel, config.maxAccel)
        val rawN = (sample.northAccel - biasN).coerceIn(-config.maxAccel, config.maxAccel)
        aE += config.accelSmoothing * (rawE - aE); aN += config.accelSmoothing * (rawN - aN)
        if (stationary) {
            biasE += 0.02 * (sample.eastAccel - biasE); biasN += 0.02 * (sample.northAccel - biasN)
            gyroBias += 0.02 * (sample.yawRateRadPerSec - gyroBias)
            aE = 0.0; aN = 0.0
        }
        val dtMs = now - lastUpdate
        // Integrate the gyroscope: compass bearing grows clockwise, the yaw rate is counter-clockwise.
        val dt = min(dtMs, config.maxPredictMillis) / 1000.0
        heading = heading?.let { wrap(it - (sample.yawRateRadPerSec - gyroBias) * dt) }
        advance(now, if (stationary) 0.0 else aE, if (stationary) 0.0 else aN, 0.0)
        if (stationary) { east.v *= 0.5; north.v *= 0.5 }
        // Before any GPS course exists, fall back to the phone's own compass.
        if (heading == null) deviceHeading?.let { d -> heading = wrap(d + (compassOffset ?: 0.0)) }
        else if (stationary && compassReliable && compassOffset != null) deviceHeading?.let { d -> heading = blend(heading!!, wrap(d + compassOffset!!), 0.03) }
    }

    private fun advance(now: Long, aEast: Double, aNorth: Double, @Suppress("UNUSED_PARAMETER") unused: Double) {
        val dt = (now - lastUpdate).coerceAtLeast(0L).coerceAtMost(config.maxPredictMillis * 4) / 1000.0
        if (dt <= 0.0) return
        val qa = config.accelNoise.pow(2)
        east.predict(dt, aEast, qa); north.predict(dt, aNorth, qa)
        lastUpdate = now
    }

    private fun correctHeading(course: Double, now: Long) {
        val h = heading
        if (h == null) { heading = course; return }
        val error = delta(course - h)
        val dt = ((now - lastGpsAt).coerceIn(200L, 5_000L)) / 1000.0
        // A persistent error means the gyroscope is drifting: learn the bias, then nudge the heading.
        gyroBias += (0.04 * error / dt).coerceIn(-0.02, 0.02)
        heading = wrap(h + 0.35 * error)
        if (abs(error) < Math.toRadians(12.0)) courseAgreements++ else courseAgreements = max(0, courseAgreements - 1)
        deviceHeading?.let { d -> val target = delta(heading!! - d); compassOffset = compassOffset?.let { wrap(it + 0.1 * delta(target - it)) } ?: target }
    }

    private fun trackStationary(speed: Double, now: Long) {
        val moving = speed >= config.stationarySpeed || hypot(east.v, north.v) > 0.9
        if (moving) { stationarySince = null; stationary = false }
        else { if (stationarySince == null) stationarySince = now; stationary = now - (stationarySince ?: now) >= config.stationaryHoldMillis }
    }

    /** Position, speed and heading at [nowElapsedMillis], predicted forward from the last update; null before the first GPS fix. */
    @Synchronized fun estimate(nowElapsedMillis: Long): MotionEstimate? {
        if (!hasOrigin) return null
        val ahead = ((nowElapsedMillis - lastUpdate).coerceIn(0L, config.maxPredictMillis)) / 1000.0
        val e = east.p + east.v * ahead; val n = north.p + north.v * ahead
        val speed = hypot(east.v, north.v)
        val h = heading
        val along = if (h != null) aE * sin(h) + aN * cos(h) else 0.0
        val lateral = if (h != null) aE * cos(h) - aN * sin(h) else 0.0
        val sigma = sqrt((east.p00 + north.p00) / 2 + (ahead * sqrt((east.p11 + north.p11) / 2)).pow(2))
        val calibration = when {
            unreliableSince?.let { nowElapsedMillis - it > 8_000 } == true && compassOffset == null -> CalibrationState.CompassNeedsFigureEight
            courseAgreements >= 3 || (startedAt?.let { nowElapsedMillis - it > 15_000 } == true && compassReliable) -> CalibrationState.Good
            else -> CalibrationState.Calibrating
        }
        val deadReckoning = nowElapsedMillis - lastGpsAt > 2_500
        // With GPS the filter can only improve on the receiver's accuracy; without it the uncertainty must keep growing.
        val accuracy = if (deadReckoning) max(sigma, gpsAccuracy) + (nowElapsedMillis - lastGpsAt) / 1000.0 * 1.5 else min(sigma, gpsAccuracy).coerceAtLeast(config.minGpsSigma)
        return MotionEstimate(toGeo(e, n), speed, h?.let { Math.toDegrees(it) }, along, lateral, accuracy, calibration, stationary, deadReckoning, nowElapsedMillis)
    }

    private fun wrap(r: Double): Double { var v = r % (2 * PI); if (v < 0) v += 2 * PI; return v }
    private fun delta(r: Double): Double { var v = (r + PI) % (2 * PI); if (v < 0) v += 2 * PI; return v - PI }
    private fun blend(from: Double, to: Double, k: Double) = wrap(from + k * delta(to - from))

    private companion object { const val METERS_PER_DEGREE = 111_320.0 }
}
