package com.rohanc.navgate.core

/** Helpers for presenting several routes to one destination. */
object RouteChoices {
    /** True when two routes follow (almost) the same road: most points of one lie within 20 m of the other. */
    fun sameRoute(a: CampusRoute, b: CampusRoute): Boolean {
        if (kotlin.math.abs(a.distanceMeters - b.distanceMeters) > maxOf(60.0, a.distanceMeters * 0.03)) return false
        val sample = 12
        val close = (0 until sample).count { i ->
            val p = a.geometry[(a.geometry.size - 1) * i / (sample - 1)]
            projectPath(p, b.geometry)?.let { it.distanceMeters < 20 } == true
        }
        return close >= sample - 1
    }

    /** Labels each route: the quickest is "Fastest", the least distance is "Shortest" (if different), the rest "Alternative". */
    fun labelled(routes: List<CampusRoute>): List<CampusRoute> {
        if (routes.isEmpty()) return routes
        val fastest = routes.minBy { it.estimatedSeconds }
        val shortest = routes.minBy { it.distanceMeters }
        return routes.map { r -> r.copy(label = when { r.id == fastest.id -> "Fastest"; r.id == shortest.id -> "Shortest"; else -> "Alternative" }) }
    }
}
