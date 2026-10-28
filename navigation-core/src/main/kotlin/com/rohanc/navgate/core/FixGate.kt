package com.rohanc.navgate.core

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The first check every raw position goes through. A phone's GPS now and then reports a place that is simply wrong (a reflection off a building,
 * a cold fix from the network provider), sometimes with a small accuracy figure, so the figure cannot be believed. What can be believed is physics:
 * a vehicle cannot be somewhere it had no time to reach.
 *
 * A fix is accepted when it lies within a gate around where the vehicle should be by now (the last accepted position carried on at its own velocity),
 * the gate being three times the fix's own uncertainty plus what the vehicle could have accelerated by. A fix outside the gate is held back. If the next fix
 * continues from it at a plausible speed, the vehicle really did go there (a tunnel exit, a signal that came back) and it is accepted; otherwise it was an
 * outlier and is dropped without anyone downstream ever seeing it.
 */
class FixGate(private val driving: Boolean = true) {
    private var last: LocationFix? = null
    private var velocityE = 0.0
    private var velocityN = 0.0
    private val chain = ArrayList<LocationFix>()

    private val maxSpeed get() = if (driving) 90.0 else 6.0
    private val maxAccel get() = if (driving) 4.0 else 1.5

    /** How many fixes were thrown away as impossible since this gate was made (or last reset). */
    @Volatile var rejectedCount = 0; private set

    /** True when [fix] is believable and should be used; false when it is an outlier. */
    @Synchronized fun accept(fix: LocationFix): Boolean = acceptImpl(fix).also { if (!it) rejectedCount++ }

    private fun acceptImpl(fix: LocationFix): Boolean {
        if (!fix.point.valid || !fix.accuracyMeters.isFinite() || fix.accuracyMeters <= 0.0) return false
        val a = last ?: return takeImpl(fix, null, false)
        val dt = (fix.elapsedMillis - a.elapsedMillis) / 1000.0
        if (dt <= 0.0) return false
        val (e, n) = enuFrom(fix.point, a.point)
        val ahead = min(dt, 6.0)
        // Both positions are noisy, and the velocity carried over from them is too.
        val sigma = hypot(max(fix.accuracyMeters, 4.0), max(a.accuracyMeters, 4.0))
        val gate = 3.0 * sigma * 1.25 + 0.5 * maxAccel * dt * dt + 6.0
        val miss = hypot(e - velocityE * ahead, n - velocityN * ahead)
        val speed = hypot(e, n) / dt
        if (speed <= maxSpeed && (miss <= min(gate, 60.0 + 20.0 * dt) || dt > 15.0)) return takeImpl(fix, a, false)
        // Outside the gate. Held back until three fixes in a row agree with one another: that is a place the vehicle really is, not a glitch.
        val tail = chain.lastOrNull()
        if (tail != null && fix.elapsedMillis > tail.elapsedMillis) {
            val cdt = (fix.elapsedMillis - tail.elapsedMillis) / 1000.0
            val (ce, cn) = enuFrom(fix.point, tail.point)
            if (hypot(ce, cn) / cdt <= min(maxSpeed, 25.0 + 3.0 * (hypot(max(fix.accuracyMeters, 4.0), max(tail.accuracyMeters, 4.0)))) ) {
                chain += fix
                if (chain.size >= 3) { val from = chain[chain.size - 2]; chain.clear(); return takeImpl(fix, from, true) }
                return false
            }
        }
        chain.clear(); chain += fix
        return false
    }

    fun reset() { last = null; chain.clear(); velocityE = 0.0; velocityN = 0.0; rejectedCount = 0 }

    private fun takeImpl(fix: LocationFix, from: LocationFix?, relocated: Boolean): Boolean {
        val reference = from
        if (reference != null) {
            val dt = (fix.elapsedMillis - reference.elapsedMillis) / 1000.0
            if (dt in 0.2..6.0) {
                val (e, n) = enuFrom(fix.point, reference.point)
                // Velocity follows the accepted positions, smoothed so one noisy fix cannot swing it.
                val k = if (relocated) 1.0 else 0.35
                velocityE += (e / dt - velocityE) * k; velocityN += (n / dt - velocityN) * k
                val s = hypot(velocityE, velocityN); if (s > maxSpeed) { velocityE *= maxSpeed / s; velocityN *= maxSpeed / s }
            } else if (relocated) { velocityE = 0.0; velocityN = 0.0 }
        }
        last = fix; chain.clear()
        return true
    }

    /** East and north metres of [p] from [origin]. */
    private fun enuFrom(p: GeoPoint, origin: GeoPoint): Pair<Double, Double> {
        val n = (p.latitude - origin.latitude) * 111_320.0
        val e = (p.longitude - origin.longitude) * 111_320.0 * kotlin.math.cos(Math.toRadians(origin.latitude))
        return e to n
    }
}
