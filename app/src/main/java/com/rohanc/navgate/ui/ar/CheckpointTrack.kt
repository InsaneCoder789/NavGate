package com.rohanc.navgate.ui.ar

import com.rohanc.navgate.core.CampusRoute
import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.core.project
import kotlin.math.max
import kotlin.math.min

enum class GateKind { Road, Turn, Finish }

/** One checkpoint on the road. [alongMeters] is measured along the route shape. */
data class Gate(val id: Int, val point: GeoPoint, val alongMeters: Double, val headingDegrees: Double, val kind: GateKind, val turnKind: String? = null, val label: String? = null)

/**
 * Racing-game checkpoints along a route. The route is cut into stages (one road each, ending at a turn). Only the stage
 * you are in, and the next one once you near its end, ever exist in memory; the rest of the route is never touched.
 * Passing a gate activates the next, so the AR layer only has to track and draw a handful of gates instead of a whole line.
 */
class CheckpointTrack(private val route: CampusRoute, private val spacingMeters: Double = 18.0) {
    private val shape = route.geometry
    private val cumulative = DoubleArray(shape.size).also { for (i in 1 until shape.size) it[i] = it[i - 1] + shape[i - 1].distanceTo(shape[i]) }
    val totalMeters: Double get() = cumulative.lastOrNull() ?: 0.0

    // Stage boundaries along the shape: start, every turn, finish.
    private val boundaries: List<Double>
    private val boundaryKinds: List<Pair<String?, String?>>
    init {
        val turns = route.maneuvers.filter { it.kind != "Start" && it.kind != "Arrive" }
            .map { route.routeToShapeMeters(it.alongMeters).coerceIn(0.0, totalMeters) to (it.kind to it.instruction) }
            .filter { it.first in 3.0..(totalMeters - 3.0) }.sortedBy { it.first }
        boundaries = listOf(0.0) + turns.map { it.first } + totalMeters
        boundaryKinds = turns.map { it.second } + (null to null)
    }
    val stageCount: Int get() = boundaries.size - 1

    private val loaded = HashMap<Int, List<Gate>>()
    var stage = 0; private set
    private var next = 0          // index of the next gate to collect inside [stage]
    var gatesCollected = 0; private set
    private var alongHint = 0.0
    private var started = false

    /** Gates of one stage; generated on first use only. */
    private fun gates(stageIndex: Int): List<Gate> = loaded.getOrPut(stageIndex) {
        if (stageIndex !in 0 until stageCount) return emptyList()
        val from = boundaries[stageIndex]; val to = boundaries[stageIndex + 1]
        val out = ArrayList<Gate>()
        var s = from + spacingMeters
        while (s < to - spacingMeters * 0.6) { out.add(gateAt(stageIndex * 10_000 + out.size, s, GateKind.Road, null, null)); s += spacingMeters }
        val (turnKind, label) = boundaryKinds[stageIndex]
        out.add(gateAt(stageIndex * 10_000 + out.size, to, if (stageIndex == stageCount - 1) GateKind.Finish else GateKind.Turn, turnKind, label))
        out
    }

    private fun gateAt(id: Int, s: Double, kind: GateKind, turnKind: String?, label: String?): Gate {
        val here = pointAt(s)
        // Face along the road; a turn gate faces the road you arrive on.
        val heading = here.bearingTo(pointAt(min(totalMeters, s + 3.0)).takeIf { it.distanceTo(here) > 0.5 } ?: pointAt(max(0.0, s - 3.0)).let { GeoPoint(2 * here.latitude - it.latitude, 2 * here.longitude - it.longitude) })
        return Gate(id, here, s, heading, kind, turnKind, label)
    }

    fun pointAt(s: Double): GeoPoint {
        if (shape.isEmpty()) return GeoPoint(0.0, 0.0)
        if (s <= 0) return shape.first()
        if (s >= totalMeters) return shape.last()
        var lo = 0; var hi = cumulative.lastIndex
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (cumulative[mid] <= s) lo = mid else hi = mid }
        val span = cumulative[hi] - cumulative[lo]
        val t = if (span <= 0) 0.0 else (s - cumulative[lo]) / span
        return GeoPoint(shape[lo].latitude + (shape[hi].latitude - shape[lo].latitude) * t, shape[lo].longitude + (shape[hi].longitude - shape[lo].longitude) * t)
    }

    /** Distance along the shape of [position], searched only near the last known spot (a full scan the first time). */
    fun alongOf(position: GeoPoint, firstTime: Boolean): Pair<Double, Double>? {
        if (shape.size < 2) return null
        val lowS = if (firstTime) 0.0 else max(0.0, alongHint - 40.0); val highS = if (firstTime) totalMeters else alongHint + 160.0
        var startIndex = 0
        if (!firstTime) { var lo = 0; var hi = cumulative.lastIndex; while (hi - lo > 1) { val mid = (lo + hi) / 2; if (cumulative[mid] <= lowS) lo = mid else hi = mid }; startIndex = lo }
        var best: Pair<Double, Double>? = null
        var i = startIndex
        while (i < shape.size - 1 && cumulative[i] <= highS) {
            val p = project(position, shape[i], shape[i + 1])
            val along = cumulative[i] + (cumulative[i + 1] - cumulative[i]) * p.fraction
            if (best == null || p.distanceMeters < best.second) best = along to p.distanceMeters
            i++
        }
        return best
    }

    /** Moves the track forward for the current position and returns the gates just collected (usually none or one). */
    fun update(position: GeoPoint): List<Gate> {
        val (along, _) = alongOf(position, firstTime = !started) ?: return emptyList()
        started = true
        alongHint = max(alongHint, along)
        val collected = ArrayList<Gate>()
        while (true) {
            val list = gates(stage)
            val gate = list.getOrNull(next)
            if (gate == null) { if (stage < stageCount - 1) { loaded.remove(stage - 1); stage++; next = 0; continue } else break }
            if (alongHint >= gate.alongMeters - COLLECT_METERS) { collected.add(gate); next++; gatesCollected++ } else break
        }
        return collected
    }

    /** The next few gates ahead of you, up to [maxMeters] away: the only ones the AR layer needs this frame. */
    fun upcoming(maxMeters: Double = 90.0, maxCount: Int = 6): List<Gate> {
        val out = ArrayList<Gate>()
        var s = stage; var i = next
        while (out.size < maxCount && s < stageCount) {
            val list = gates(s)
            while (i < list.size && out.size < maxCount) { val g = list[i]; if (g.alongMeters - alongHint > maxMeters) return out; out.add(g); i++ }
            s++; i = 0
        }
        return out
    }

    /** The road ahead as points every [step] metres, from your spot to the end of this road plus a short stub of the next one. */
    fun pathAhead(stubMeters: Double = 14.0, maxMeters: Double = 220.0, step: Double = 2.0): List<GeoPoint> {
        val end = boundaries.getOrElse(stage + 1) { totalMeters }
        val limit = min(min(end + stubMeters, alongHint + maxMeters), totalMeters)
        val out = ArrayList<GeoPoint>()
        var s = alongHint
        while (s < limit) { out.add(pointAt(s)); s += step }
        out.add(pointAt(limit))
        return out
    }

    /** Metres from you to the end of the road you are on (the next turn, or the finish). */
    val metersToStageEnd: Double get() = (boundaries.getOrElse(stage + 1) { totalMeters } - alongHint).coerceAtLeast(0.0)

    /** The turn (or finish) that ends the current road. */
    val stageEnd: Gate? get() = gates(stage).lastOrNull()

    val loadedStages: Int get() = loaded.size
    val metersToNextGate: Double? get() = gates(stage).getOrNull(next)?.let { it.alongMeters - alongHint }

    private companion object { const val COLLECT_METERS = 3.0 }
}
