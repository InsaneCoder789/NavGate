package com.rohanc.navgate.core

import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import java.util.PriorityQueue
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.*

@Serializable
enum class RouteFailure { MissingRegion, MissingGraph, UnsupportedProfile, InvalidCoordinate, MissingEntrance, OutsideCoverage, UncertainOrigin, NoConnection, InvalidGraph }

sealed interface RouteResult {
    data class Ready(val route: CampusRoute) : RouteResult
    data class Unavailable(val reason: RouteFailure, val detail: String) : RouteResult
}

@Serializable
data class PlanRequest(
    val requestId: Long,
    val regionId: String,
    val origin: GeoPoint,
    val destinationPlaceId: String,
    val entranceId: String? = null,
    val profile: String = "walking",
    val destinationPoint: GeoPoint? = null,
    val destinationTitle: String? = null,
    val routingSource: String? = null,
    /** "fastest" (least time) or "shortest" (least distance). */
    val preference: String = "fastest",
)

@Serializable
data class Maneuver(
    val instruction: String,
    val target: GeoPoint,
    val alongMeters: Double,
    val kind: String,
    /** Roundabouts: which exit to take, and how the route turns overall between the road you enter on and the road you leave on (positive = right). */
    val exitNumber: Int? = null,
    val bearingChange: Double? = null,
)

@Serializable
data class CampusRoute(
    val id: String,
    val requestId: Long,
    val regionId: String,
    val graphVersion: String,
    val destinationPlaceId: String,
    val entranceId: String,
    val geometry: List<GeoPoint>,
    val edgeIds: List<String>,
    val distanceMeters: Double,
    val estimatedSeconds: Double,
    val maneuvers: List<Maneuver>,
    val reviewedAt: String,
    val source: String = "local_campus_astar",
    val profile: String = "walking",
    val provider: String? = null,
    val fetchedAtEpochMillis: Long? = null,
    val destinationPoint: GeoPoint? = null,
    val destinationTitle: String? = null,
    /** Provider cumulative elapsed time at every geometry vertex, in seconds. */
    val shapeElapsedSeconds: List<Double> = emptyList(),
    val warnings: List<String> = emptyList(),
    /** "Fastest", "Shortest" or "Alternative" when several routes were offered. */
    val label: String? = null,
    val fromCache: Boolean = false,
) {
    val external: Boolean get() = source == "valhalla" || source == "brouter"
    fun shapeToRouteMeters(meters: Double): Double =
        if (geometry.lengthMeters() > 0) meters / geometry.lengthMeters() * distanceMeters else 0.0
    fun routeToShapeMeters(meters: Double): Double =
        if (distanceMeters > 0) meters / distanceMeters * geometry.lengthMeters() else 0.0
    fun remainingSecondsAt(progress: Double): Double {
        if (distanceMeters <= 0) return 0.0
        if (shapeElapsedSeconds.size != geometry.size) return estimatedSeconds * (1 - progress / distanceMeters).coerceIn(0.0, 1.0)
        val target = routeToShapeMeters(progress)
        var along = 0.0
        geometry.zipWithNext().forEachIndexed { i, (a, b) ->
            val length = a.distanceTo(b)
            if (target <= along + length || i == geometry.lastIndex - 1) {
                val fraction = if (length > 0) ((target - along) / length).coerceIn(0.0, 1.0) else 1.0
                val elapsed = shapeElapsedSeconds[i] + fraction * (shapeElapsedSeconds[i + 1] - shapeElapsedSeconds[i])
                return (estimatedSeconds - elapsed).coerceAtLeast(0.0)
            }
            along += length
        }
        return 0.0
    }
}

/** Coarse spatial buckets narrow segment projection candidates; long segments are safely indexed as overflow. */
private class EdgeSpatialIndex(private val edges: List<GraphEdge>, private val boundary: List<GeoPoint>) {
    private val south = boundary.minOf { it.latitude }
    private val west = boundary.minOf { it.longitude }
    private val latStep = (boundary.maxOf { it.latitude } - south).coerceAtLeast(0.0001) / 64
    private val lonStep = (boundary.maxOf { it.longitude } - west).coerceAtLeast(0.0001) / 64
    private val cells = mutableMapOf<Pair<Int, Int>, MutableSet<Int>>()
    private val overflow = mutableSetOf<Int>()
    private fun row(lat: Double) = floor((lat - south) / latStep).toInt()
    private fun col(lon: Double) = floor((lon - west) / lonStep).toInt()

    init {
        edges.forEachIndexed { index, edge ->
            edge.geometry.zipWithNext().forEach { (a, b) ->
                val rows = row(min(a.latitude, b.latitude))..row(max(a.latitude, b.latitude))
                val cols = col(min(a.longitude, b.longitude))..col(max(a.longitude, b.longitude))
                if (rows.count().toLong() * cols.count() > 64) overflow += index
                else for (r in rows) for (c in cols) cells.getOrPut(r to c) { mutableSetOf() } += index
            }
        }
    }

    fun near(point: GeoPoint, radius: Double): List<GraphEdge> {
        val dLat = radius / 111_000
        val dLon = dLat / cos(Math.toRadians(point.latitude)).coerceAtLeast(0.15)
        val result = overflow.toMutableSet()
        for (r in row(point.latitude - dLat)..row(point.latitude + dLat)) {
            for (c in col(point.longitude - dLon)..col(point.longitude + dLon)) result += cells[r to c].orEmpty()
        }
        return result.sorted().map(edges::get)
    }
}

class CampusRoutingProvider(pack: RegionPack) {
    // Defensive serialization copy prevents callers mutating the validated graph after construction.
    private val graph = pack.copy(
        coverage = pack.coverage.copy(boundary = pack.coverage.boundary.toList()),
        nodes = pack.nodes.toList(), edges = pack.edges.map { it.copy(geometry = it.geometry.toList()) },
        places = pack.places.map { it.copy(aliases = it.aliases.toList()) }, entrances = pack.entrances.toList(),
    )
    private val validation = PackValidator.validate(graph)
    private val nodes = graph.nodes.associateBy { it.id }
    private val edges = graph.edges.filter { it.reviewed && it.access == Access.Allowed }
    private val adjacency = edges.groupBy { it.from }
    private val spatial = if (validation.valid) EdgeSpatialIndex(edges, graph.coverage.boundary) else null

    suspend fun route(request: PlanRequest): RouteResult {
        fun unavailable(reason: RouteFailure, detail: String) = RouteResult.Unavailable(reason, detail)
        if (!request.origin.valid) return unavailable(RouteFailure.InvalidCoordinate, "Origin must contain finite geographic coordinates")
        if (request.profile != "walking") return unavailable(RouteFailure.UnsupportedProfile, "Installed campus graph supports walking only")
        if (request.regionId != graph.regionId) return unavailable(RouteFailure.MissingRegion, "Requested region is not installed")
        if (!validation.valid) return unavailable(RouteFailure.InvalidGraph, validation.errors.joinToString("; "))
        if (edges.isEmpty()) return unavailable(RouteFailure.MissingGraph, "Places are installed, but no reviewed walking graph is available")
        if (!graph.coverage.contains(request.origin)) return unavailable(RouteFailure.OutsideCoverage, "Origin is outside installed campus coverage")
        val entrances = graph.entrances.filter { it.placeId == request.destinationPlaceId && it.reviewed && (request.entranceId == null || it.id == request.entranceId) }.sortedBy { it.id }
        if (entrances.isEmpty()) return unavailable(RouteFailure.MissingEntrance, "Destination has no reviewed entrance in this graph")
        val projections = requireNotNull(spatial).near(request.origin, SNAP_LIMIT_METERS)
            .mapNotNull { edge -> projectPath(request.origin, edge.geometry)?.let { edge to it } }
            .filter { it.second.distanceMeters <= SNAP_LIMIT_METERS }
            .sortedWith(compareBy({ it.second.distanceMeters }, { it.first.id }))
        val nearest = projections.firstOrNull() ?: return unavailable(RouteFailure.UncertainOrigin, "Choose a reviewed entry or move closer to a mapped path")
        val ambiguous = projections.any { (_, projection) -> projection.distanceMeters <= nearest.second.distanceMeters + 3 && projection.point.distanceTo(nearest.second.point) > 6 }
        if (ambiguous) return unavailable(RouteFailure.UncertainOrigin, "Nearby parallel paths make the start ambiguous; choose a confirmed entry")
        val seeds = projections.filter { it.second.point.distanceTo(nearest.second.point) < 1.0 }
        var best: CampusRoute? = null
        for (entrance in entrances) {
            coroutineContext.ensureActive()
            val candidate = search(request, entrance, seeds) ?: continue
            if (best == null || candidate.distanceMeters < best.distanceMeters) best = candidate
        }
        return best?.let(RouteResult::Ready) ?: unavailable(RouteFailure.NoConnection, "No permitted walking connection to a reviewed entrance")
    }

    private data class QueueEntry(val node: String, val g: Double, val f: Double)
    private data class Previous(val node: String?, val edge: GraphEdge?, val prefix: List<GeoPoint> = emptyList())

    private suspend fun search(request: PlanRequest, entrance: Entrance, seeds: List<Pair<GraphEdge, PathProjection>>): CampusRoute? {
        val goal = nodes.getValue(entrance.nodeId).point
        val queue = PriorityQueue(compareBy<QueueEntry>({ it.f }, { it.node }, { it.g }))
        val costs = mutableMapOf<String, Double>()
        val previous = mutableMapOf<String, Previous>()
        fun offer(node: String, cost: Double, predecessor: Previous) {
            if (cost < (costs[node] ?: Double.POSITIVE_INFINITY)) {
                costs[node] = cost; previous[node] = predecessor
                queue += QueueEntry(node, cost, cost + nodes.getValue(node).point.distanceTo(goal))
            }
        }
        for ((edge, projection) in seeds) {
            // A snapped endpoint is a graph node; an interior point may only travel forward on this directed edge.
            if (projection.point.distanceTo(nodes.getValue(edge.from).point) < 0.1) offer(edge.from, 0.0, Previous(null, null, listOf(nodes.getValue(edge.from).point)))
            if (projection.point.distanceTo(nodes.getValue(edge.to).point) < 0.1) offer(edge.to, 0.0, Previous(null, null, listOf(nodes.getValue(edge.to).point)))
            val suffix = (listOf(projection.point) + edge.geometry.drop(projection.segment + 1)).removeAdjacentDuplicates()
            if (suffix.size >= 2) offer(edge.to, suffix.lengthMeters(), Previous(null, edge, suffix))
        }
        while (queue.isNotEmpty()) {
            coroutineContext.ensureActive()
            val current = queue.remove()
            if (current.g != costs[current.node]) continue
            if (current.node == entrance.nodeId) {
                val reversed = mutableListOf<Previous>()
                var cursor: String? = current.node
                while (cursor != null) {
                    val item = previous.getValue(cursor); reversed += item; cursor = item.node
                }
                val chain = reversed.asReversed()
                val geometry = chain.flatMap { if (it.node == null) it.prefix else requireNotNull(it.edge).geometry }.removeAdjacentDuplicates()
                // A zero-length route at the destination is valid and remains a manual preview until a live fix confirms it.
                val path = if (geometry.size == 1) geometry + geometry else geometry
                return CampusRoute(
                    id = UUID.randomUUID().toString(), requestId = request.requestId, regionId = graph.regionId,
                    graphVersion = graph.version, destinationPlaceId = entrance.placeId, entranceId = entrance.id,
                    geometry = path, edgeIds = chain.mapNotNull { it.edge?.id }, distanceMeters = current.g,
                    estimatedSeconds = current.g / WALKING_SPEED, maneuvers = buildManeuvers(path), reviewedAt = graph.reviewedAt.orEmpty(),
                )
            }
            for (edge in adjacency[current.node].orEmpty()) {
                offer(edge.to, current.g + edge.geometry.lengthMeters(), Previous(current.node, edge))
            }
        }
        return null
    }

    companion object {
        const val SNAP_LIMIT_METERS = 20.0
        const val WALKING_SPEED = 1.35
    }
}

private fun List<GeoPoint>.removeAdjacentDuplicates(): List<GeoPoint> = fold(mutableListOf()) { result, point ->
    if (result.lastOrNull()?.distanceTo(point)?.let { it > 0.001 } != false) result += point
    result
}

private fun buildManeuvers(path: List<GeoPoint>): List<Maneuver> {
    if (path.isEmpty()) return emptyList()
    val steps = mutableListOf<Maneuver>()
    var along = 0.0
    for (i in 1 until path.lastIndex) {
        along += path[i - 1].distanceTo(path[i])
        val delta = ((path[i].bearingTo(path[i + 1]) - path[i - 1].bearingTo(path[i]) + 540) % 360) - 180
        if (abs(delta) >= 30) {
            val kind = when { abs(delta) >= 150 -> "UTurn"; delta > 0 -> "Right"; else -> "Left" }
            steps += Maneuver(if (kind == "UTurn") "Turn around on the mapped path" else "Turn ${kind.lowercase()} on the mapped path", path[i], along, kind)
        }
    }
    if (steps.isEmpty()) steps += Maneuver("Continue on the mapped walking path", path.last(), path.lengthMeters(), "Straight")
    steps += Maneuver("Arrive at the selected entrance", path.last(), path.lengthMeters(), "Arrive")
    return steps
}

interface RoutingProvider {
    suspend fun plan(request: PlanRequest, pinnedVersion: String? = null): RouteResult

    /** Other good ways to the same destination (and a fresh best route), used as ready-made backups if the driver leaves the planned road. */
    suspend fun alternatives(request: PlanRequest): List<CampusRoute> = emptyList()
}

class RoutePlanner(private val regions: RegionStore) : RoutingProvider {
    // RegionStore versions are immutable. Retain at most two spatial indexes: current and pinned trip.
    private val cacheLock = kotlinx.coroutines.sync.Mutex()
    private val providers = LinkedHashMap<Pair<String,String>,CampusRoutingProvider>(2,0.75f,true)
    private suspend fun provider(pack: RegionPack): CampusRoutingProvider {
        cacheLock.lock()
        try {
            val key=pack.regionId to pack.version
            return providers[key] ?: CampusRoutingProvider(pack).also {
                providers[key]=it
                while(providers.size>2) providers.remove(providers.keys.first())
            }
        } finally { cacheLock.unlock() }
    }

    override suspend fun plan(request: PlanRequest, pinnedVersion: String?): RouteResult {
        coroutineContext.ensureActive()
        val pack = if (pinnedVersion == null) regions.active(request.regionId) else regions.version(request.regionId, pinnedVersion)
        return if (pack == null) RouteResult.Unavailable(RouteFailure.MissingRegion, "Install a reviewed region pack before routing")
        else provider(pack).route(request)
    }
}
