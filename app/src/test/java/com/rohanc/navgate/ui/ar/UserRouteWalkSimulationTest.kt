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

/**
 * A vector simulation of the walk in the field recording: north along Lokhandwala Marg, round the roundabout, then 210 m east along Akurli Road
 * (the route the app planned, 65 points, 328 m). The walker keeps to the pavement a few metres off the road's centre line, weaves, swings the phone
 * about like a person looking around, the compass is wrong by a different amount each run, GPS wanders and ARCore drifts. Each frame asks: how far is
 * the drawn line from the true road, and from the direction the phone must follow to stay on it?
 */
class UserRouteWalkSimulationTest {
    private val route = RouteGeometry(ROUTE_POINTS.map { GeoPoint(it.first, it.second) })

    private val sim = ArWalkSimulator(route)
    private fun simulate(compassBiasDeg: Double, seed: Long, reportedSpeed: (Double, Random) -> Double = { v, r -> v + r.nextGaussian() * 0.25 }, compassNoise: Double = 6.0,
        gpsSigma: Double = 4.0, seconds: Double = 200.0, pavementOffset: Double = 3.0, standSeconds: Double = 0.0, gpsBias: Double = 0.0, compassBiasAt: ((Double) -> Double)? = null,
        relocalizeAt: Double? = null, swingDeg: Double = 1.0) = sim.simulate(compassBiasDeg, seed, reportedSpeed, ArGeoAligner(), compassNoise, gpsSigma, seconds, pavementOffset, standSeconds, gpsBias, compassBiasAt, relocalizeAt, swingDeg)
    private fun angle(a: Double, b: Double) = sim.angle(a, b)

    private fun row(name: String, vararg rs: ArWalkSimulator.Result) = System.err.println("SCN %-34s settle %s | early yaw p90 %s | yaw p90 %s | dir p50 %s p90 %s | lateral p90 %s".format(name,
        rs.joinToString("/") { "%.1f".format(it.settleSeconds) }, rs.joinToString("/") { "%.0f".format(it.pct(it.early, .9)) }, rs.joinToString("/") { "%.0f".format(it.pct(it.yawError, .9)) },
        rs.joinToString("/") { "%.0f".format(it.pct(it.lineAngle, .5)) }, rs.joinToString("/") { "%.0f".format(it.pct(it.lineAngle, .9)) }, rs.joinToString("/") { "%.1f".format(it.pct(it.lateral, .9)) }))


    private fun limits(name: String, runs: List<ArWalkSimulator.Result>, yaw: Double = 12.0, dir: Double = 30.0, lateral: Double = 7.0, settleAfter: Double = 8.0, stand: Double = 0.0) {
        row(name, *runs.toTypedArray())
        for ((k, r) in runs.withIndex()) {
            assertTrue("$name #$k settles in ${r.settleSeconds} s", r.settleSeconds <= stand + settleAfter + 2.0)
            assertTrue("$name #$k yaw p90 ${r.pct(r.yawError, .9)}", r.pct(r.yawError, .9) < yaw)
            assertTrue("$name #$k line direction p90 ${r.pct(r.lineAngle, .9)}", r.pct(r.lineAngle, .9) < dir)
            assertTrue("$name #$k line off the road by p90 ${r.pct(r.lateral, .9)} m", r.pct(r.lateral, .9) < lateral)
        }
    }

    @Test fun scenarios() {
        val seeds = 1L..3L
        limits("baseline (bias 50)", seeds.map { simulate(50.0, it) }, yaw = 6.0)
        limits("stand 8 s then walk (bias 70)", seeds.map { simulate(70.0, it, standSeconds = 8.0) }, yaw = 6.0, stand = 8.0)
        limits("stand 20 s then walk (bias -80)", seeds.map { simulate(-80.0, it, standSeconds = 20.0) }, yaw = 6.0, stand = 20.0)
        limits("gps bias 6 m (bias 40)", seeds.map { simulate(40.0, it, gpsBias = 6.0) }, yaw = 6.0)
        limits("noisy gps 8 m (bias 40)", seeds.map { simulate(40.0, it, gpsSigma = 8.0) }, yaw = 8.0, dir = 35.0, lateral = 8.0)
        limits("compass wanders 60 to -30", seeds.map { simulate(0.0, it, compassBiasAt = { t -> if (t < 40) 60.0 else if (t < 90) -30.0 else 25.0 }) }, yaw = 8.0)
        limits("big phone swings x1.8", seeds.map { simulate(50.0, it, swingDeg = 1.8) }, yaw = 6.0)
        limits("tracking lost at 60 s", seeds.map { simulate(50.0, it, relocalizeAt = 60.0) }, yaw = 8.0)
        limits("slow reported speed", seeds.map { simulate(60.0, it, reportedSpeed = { _, r -> 0.35 + r.nextDouble() * 0.2 }) }, yaw = 12.0, dir = 15.0)
        limits("compass noise 15 deg", seeds.map { simulate(50.0, it, compassNoise = 15.0) }, yaw = 6.0)
    }

    @Test fun `the line locks onto the road within seconds whatever the compass says`() {
        for (bias in listOf(0.0, 25.0, 50.0, 90.0, 150.0, -60.0)) for (seed in 1L..4L) {
            val r = simulate(bias, seed)
            assertTrue("bias $bias seed $seed: ${r.summary("")}", r.settleSeconds <= 8.0 && r.pct(r.yawError, .9) < 10.0)
        }
    }

    @Test fun `the line follows the road, not a path through the crowd`() {
        for (seed in 1L..4L) { val r = simulate(50.0, seed); assertTrue("seed $seed: ${r.summary("")}", r.pct(r.lineAngle, .5) < 8.0 && r.pct(r.lateral, .9) < 5.0) }
    }

    @Test fun `a walking speed reported below 0 6 metres a second still lines up with the road`() {
        for (seed in 1L..3L) {
            val r = simulate(60.0, seed, reportedSpeed = { _, rng -> 0.35 + rng.nextDouble() * 0.2 })
            assertTrue("seed $seed: ${r.summary("")}", r.settleSeconds <= 8.0 && r.pct(r.yawError, .9) < 12.0 && r.pct(r.lineAngle, .5) < 8.0)
        }
    }

    @Test fun `a wanderer on the pavement sees the line on the road not under their feet`() {
        // The walker keeps 3 m to the right of the road's centre line: the line should end up between about 1.5 and 4.5 m to their left, never at their feet.
        val near = simulate(40.0, 5, pavementOffset = 3.0); val far = simulate(40.0, 5, pavementOffset = 0.0)
        assertTrue("pavement ${near.summary("")} | on the road ${far.summary("")}", near.pct(near.lateral, .5) < 4.5 && far.pct(far.lateral, .5) < 4.5)
    }

    companion object {
        private val ROUTE_POINTS = listOf(
        19.196486 to 72.872392,
        19.196934 to 72.872403,
        19.196966 to 72.872419,
        19.196979 to 72.872427,
        19.196986 to 72.872432,
        19.196988 to 72.872437,
        19.196987 to 72.872441,
        19.196986 to 72.872445,
        19.196985 to 72.872449,
        19.196984 to 72.872454,
        19.196984 to 72.872458,
        19.196983 to 72.872462,
        19.196983 to 72.872467,
        19.196983 to 72.872471,
        19.196984 to 72.872475,
        19.196984 to 72.872479,
        19.196985 to 72.872484,
        19.196986 to 72.872488,
        19.196987 to 72.872492,
        19.196988 to 72.872496,
        19.196990 to 72.872500,
        19.196992 to 72.872504,
        19.196994 to 72.872508,
        19.196996 to 72.872511,
        19.196998 to 72.872515,
        19.197000 to 72.872518,
        19.197003 to 72.872522,
        19.197006 to 72.872525,
        19.197009 to 72.872528,
        19.197012 to 72.872531,
        19.197015 to 72.872533,
        19.197018 to 72.872536,
        19.197022 to 72.872538,
        19.197025 to 72.872540,
        19.197029 to 72.872542,
        19.197033 to 72.872544,
        19.197037 to 72.872545,
        19.197041 to 72.872546,
        19.197044 to 72.872547,
        19.197048 to 72.872548,
        19.197052 to 72.872549,
        19.197057 to 72.872549,
        19.197061 to 72.872549,
        19.197065 to 72.872549,
        19.197069 to 72.872549,
        19.197073 to 72.872548,
        19.197077 to 72.872548,
        19.197081 to 72.872547,
        19.197085 to 72.872545,
        19.197088 to 72.872544,
        19.197092 to 72.872542,
        19.197097 to 72.872545,
        19.197103 to 72.872553,
        19.197112 to 72.872569,
        19.197131 to 72.872606,
        19.197139 to 72.872666,
        19.197144 to 72.872758,
        19.197152 to 72.873121,
        19.197158 to 72.873418,
        19.197163 to 72.873681,
        19.197172 to 72.874150,
        19.197180 to 72.874554,
        19.197401 to 72.874561,
        19.197448 to 72.874562,
        19.197448 to 72.874640
        )
    }
}
