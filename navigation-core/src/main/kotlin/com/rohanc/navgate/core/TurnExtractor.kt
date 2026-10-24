package com.rohanc.navgate.core

import kotlin.math.abs

/**
 * Builds turn-by-turn maneuvers from a bare road line, for routers that return only shape (the offline engine).
 * A bend that spreads over several vertices is one turn, measured from the road 15 m before it to the road 15 m after it.
 */
object TurnExtractor {
    private const val SPAN = 15.0
    private const val MERGE_GAP = 30.0

    /** [names] gives the street name at each vertex when known; it may be shorter than [shape] or contain nulls. */
    fun extract(shape: List<GeoPoint>, profile: String, names: List<String?> = emptyList(), destinationLabel: String? = null): List<Maneuver> {
        if (shape.size < 2) return emptyList()
        val geometry = RouteGeometry(shape); val length = geometry.length
        val threshold = if (profile == "driving") 25.0 else 30.0
        val vertexS = geometry.cumulative
        fun nameAt(s: Double): String? {
            var i = vertexS.indexOfLast { it <= s }.coerceAtLeast(0)
            while (i < names.size && names[i].isNullOrBlank() && i < vertexS.lastIndex && vertexS[i] - s < 1.0) i++
            return names.getOrNull(i.coerceIn(0, names.lastIndex.coerceAtLeast(0)))?.takeIf { it.isNotBlank() }
        }
        val out = ArrayList<Maneuver>()
        val startName = nameAt(2.0)
        val heading = shape.first().bearingTo(geometry.pointAt(minOf(length, 20.0)))
        out += Maneuver("Head ${compass(heading)}" + (startName?.let { " on $it" } ?: ""), shape.first(), 0.0, ManeuverKinds.START)

        // Candidate vertices: where the road bends sharply enough to matter, grouped when they follow each other closely.
        val groups = ArrayList<Pair<Double, Double>>()
        for (i in 1 until shape.lastIndex) {
            val angle = ManeuverKinds.turnAngle(shape, i) ?: continue
            if (abs(angle) < threshold * 0.5) continue
            val s = vertexS[i]
            val last = groups.lastOrNull()
            if (last != null && s - last.second <= MERGE_GAP) groups[groups.lastIndex] = last.first to s else groups += s to s
        }
        for ((first, last) in groups) {
            if (first < 5.0 || last > length - 5.0) continue
            val inBearing = geometry.pointAt(maxOf(0.0, first - SPAN)).bearingTo(geometry.pointAt(maxOf(0.0, first - 1.0)))
            val outBearing = geometry.pointAt(minOf(length, last + 1.0)).bearingTo(geometry.pointAt(minOf(length, last + SPAN)))
            val net = ((outBearing - inBearing + 540.0) % 360.0) - 180.0
            if (abs(net) < threshold) continue
            val kind = ManeuverKinds.fromAngle(net)
            if (kind == ManeuverKinds.STRAIGHT) continue
            val at = (first + last) / 2
            val onto = nameAt(minOf(length, last + 3.0))
            out += Maneuver(phrase(kind) + (onto?.let { " onto $it" } ?: ""), geometry.pointAt(at), at, kind, bearingChange = net)
        }
        out += Maneuver("You have arrived" + (destinationLabel?.let { " at $it" } ?: " at your destination") + ".", shape.last(), length, ManeuverKinds.ARRIVE)
        return out
    }

    private fun phrase(kind: String) = when (kind) {
        ManeuverKinds.SLIGHT_LEFT -> "Bear left"; ManeuverKinds.SLIGHT_RIGHT -> "Bear right"
        ManeuverKinds.LEFT -> "Turn left"; ManeuverKinds.RIGHT -> "Turn right"
        ManeuverKinds.SHARP_LEFT -> "Turn sharp left"; ManeuverKinds.SHARP_RIGHT -> "Turn sharp right"
        ManeuverKinds.UTURN -> "Make a U-turn"; else -> "Continue"
    }

    private fun compass(bearing: Double): String = listOf("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest")[(((bearing + 22.5) % 360) / 45).toInt()]
}
