package com.rohanc.navgate.core

import kotlin.math.max
import kotlin.math.min

/**
 * Time still to go, without live traffic. The route says how long the rest of the road takes at its usual speed; this learns how the trip is really
 * going from the stretches where the vehicle was moving, and scales the rest of the route by it (between 0.6x and 1.8x). A stop changes nothing: it
 * is not a speed, so the answer stays what it was and never becomes "unknown" or infinite, and the arrival clock simply moves later as time passes.
 */
class EtaEstimator {
    private var movingSeconds = 0.0
    private var movingMeters = 0.0
    private var lastAlong = Double.NaN
    private var lastAtMillis = 0L
    private var ratio = 1.0

    /** [routeRemainingSeconds] is what the route itself says is left; the result is the figure to show. */
    fun update(nowMillis: Long, alongMeters: Double, remainingMeters: Double, routeRemainingSeconds: Double, speedMps: Double): Double {
        if (routeRemainingSeconds <= 0.0 || remainingMeters <= 0.0) return max(0.0, routeRemainingSeconds)
        if (!lastAlong.isNaN() && nowMillis > lastAtMillis) {
            val dt = (nowMillis - lastAtMillis) / 1000.0
            val moved = alongMeters - lastAlong
            // Only stretches that were really driven count: not a stop, not a jump, not time with no fix at all.
            if (dt in 0.05..5.0 && speedMps > 1.5 && moved in 0.0..(dt * 90.0)) {
                // Recent driving matters most: older samples fade over about five minutes.
                val keep = Math.exp(-dt / 300.0)
                movingSeconds = movingSeconds * keep + dt; movingMeters = movingMeters * keep + moved
            }
        }
        lastAlong = alongMeters; lastAtMillis = nowMillis
        if (movingSeconds < 20.0 || movingMeters < 100.0) return routeRemainingSeconds
        val observed = movingMeters / movingSeconds
        val planned = remainingMeters / routeRemainingSeconds
        val target = (planned / observed).coerceIn(0.6, 1.8)
        // The more driving has been seen, the more it is believed; it moves gently so the figure does not jitter.
        val weight = min(1.0, movingSeconds / 120.0)
        ratio += (1.0 + (target - 1.0) * weight - ratio) * 0.05
        return routeRemainingSeconds * ratio
    }
}
