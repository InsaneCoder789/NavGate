package com.rohanc.navgate.data.traffic

import com.rohanc.navgate.core.GeoPoint
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.max

enum class TrafficLevel { Clear, Light, Moderate, Heavy }

/** What traffic does to one route: extra minutes, a plain-language level, and where it is worst. */
data class TrafficReport(val delaySeconds: Double, val level: TrafficLevel, val worstAlongMeters: Double?, val sampledPoints: Int) {
    val delayMinutes: Int get() = Math.round(delaySeconds / 60.0).toInt()
    val summary: String get() = when (level) {
        TrafficLevel.Clear -> "Traffic is clear"
        TrafficLevel.Light -> "Light traffic"
        TrafficLevel.Moderate -> "Moderate traffic · +${delayMinutes.coerceAtLeast(1)} min"
        TrafficLevel.Heavy -> "Heavy traffic · +${delayMinutes.coerceAtLeast(1)} min"
    }
}

/** Current and free-flow speed (km/h) on the road nearest a point. */
data class FlowSample(val currentKmh: Double, val freeFlowKmh: Double, val roadClosed: Boolean = false)

/** Source of live road speeds. TomTom today; HERE or crowd data could implement the same thing. */
fun interface TrafficFlowSource { suspend fun flowAt(point: GeoPoint): FlowSample? }

/**
 * Turns live road speeds into a delay for a route. It samples the route every [spacingMeters] (at most [maxSamples], so the
 * free API quota goes a long way), assumes each sample describes the stretch around it, and adds up how much longer
 * that stretch takes at today's speed than at free-flow speed. Routes are planned at free-flow, so this is the extra time.
 */
class TrafficEstimator(private val source: TrafficFlowSource, private val spacingMeters: Double = 1_800.0, private val maxSamples: Int = 8) {
    private data class Cached(val key: String, val at: Long, val report: TrafficReport)
    @Volatile private var cached: Cached? = null

    suspend fun estimate(routeKey: String, geometry: List<GeoPoint>, nowMillis: Long = System.currentTimeMillis()): TrafficReport? {
        cached?.takeIf { it.key == routeKey && nowMillis - it.at < CACHE_MILLIS }?.let { return it.report }
        if (geometry.size < 2) return null
        val cumulative = DoubleArray(geometry.size).also { for (i in 1 until geometry.size) it[i] = it[i - 1] + geometry[i - 1].distanceTo(geometry[i]) }
        val total = cumulative.last()
        if (total < 300) return null
        val count = ((total / spacingMeters).toInt() + 1).coerceIn(1, maxSamples)
        val stretch = total / count
        val centres = (0 until count).map { (it + 0.5) * stretch }
        val points = centres.map { pointAt(geometry, cumulative, it) }
        val gate = Semaphore(4)
        val flows = coroutineScope { points.map { p -> async { gate.withPermit { runCatching { source.flowAt(p) }.getOrNull() } } }.map { it.await() } }
        val usable = flows.withIndex().filter { it.value != null }
        if (usable.isEmpty()) return null
        var delay = 0.0
        var worst: Pair<Double, Double>? = null
        for ((index, flow) in usable) {
            flow!!
            val free = max(flow.freeFlowKmh, 5.0) / 3.6
            val now = max(if (flow.roadClosed) 2.0 else flow.currentKmh, 2.0) / 3.6
            val extra = (stretch / now - stretch / free).coerceAtLeast(0.0)
            // Stretches that failed to load are assumed to behave like the ones that did.
            delay += extra
            if (worst == null || extra > worst.second) worst = centres[index] to extra
        }
        delay *= count.toDouble() / usable.size
        val ratio = delay / (total / (50.0 / 3.6)).coerceAtLeast(1.0) // delay as a share of a ~50 km/h trip
        val level = when { delay < 60 || ratio < 0.06 -> TrafficLevel.Clear; ratio < 0.15 -> TrafficLevel.Light; ratio < 0.35 -> TrafficLevel.Moderate; else -> TrafficLevel.Heavy }
        return TrafficReport(delay, level, worst?.takeIf { it.second > 20 }?.first, usable.size).also { cached = Cached(routeKey, nowMillis, it) }
    }

    private fun pointAt(geometry: List<GeoPoint>, cumulative: DoubleArray, s: Double): GeoPoint {
        var lo = 0; var hi = cumulative.lastIndex
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (cumulative[mid] <= s) lo = mid else hi = mid }
        val span = cumulative[hi] - cumulative[lo]
        val t = if (span <= 0) 0.0 else ((s - cumulative[lo]) / span).coerceIn(0.0, 1.0)
        return GeoPoint(geometry[lo].latitude + (geometry[hi].latitude - geometry[lo].latitude) * t, geometry[lo].longitude + (geometry[hi].longitude - geometry[lo].longitude) * t)
    }

    private companion object { const val CACHE_MILLIS = 5 * 60_000L }
}
