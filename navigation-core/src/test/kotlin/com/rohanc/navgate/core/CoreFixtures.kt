package com.rohanc.navgate.core

/** Synthetic coordinates and topology for unit tests only. Never bundled as real campus paths. */
internal fun fixturePack(): RegionPack {
    val a = GraphNode("a", GeoPoint(20.0, 85.0))
    val b = GraphNode("b", GeoPoint(20.0, 85.001))
    val c = GraphNode("c", GeoPoint(20.001, 85.001))
    val d = GraphNode("d", GeoPoint(20.001, 85.0))
    return RegionPack(regionId = "test", version = "v1", city = "Synthetic Test City",
        coverage = Coverage(listOf(GeoPoint(19.99,84.99),GeoPoint(19.99,85.01),GeoPoint(20.01,85.01),GeoPoint(20.01,84.99))),
        source = "Synthetic unit-test fixture", attribution = "Test data", license = "CC0-1.0", generatedAt = "2026-10-18", reviewedAt = "test-only",
        nodes = listOf(a,b,c,d), edges = listOf(
            GraphEdge("ab", "a", "b", listOf(a.point,b.point), Access.Allowed, true),
            GraphEdge("bc", "b", "c", listOf(b.point,c.point), Access.Allowed, true),
            GraphEdge("ac-long", "a", "c", listOf(a.point,GeoPoint(20.003,85.0),c.point), Access.Allowed, true),
        ), places = listOf(CampusPlace("target", "Test target", city = "Synthetic Test City", point = c.point)),
        entrances = listOf(Entrance("entry", "target", "c", "Test entry", true)))
}

internal class MemoryRegionStore : RegionStore {
    val versions = mutableMapOf<Pair<String,String>, RegionPack>()
    private val activeVersions = mutableMapOf<String,String>()
    private val digests = mutableMapOf<Pair<String,String>,String>()
    override suspend fun active(regionId: String): RegionPack? = activeVersions[regionId]?.let { versions[regionId to it] }
    override suspend fun version(regionId: String, version: String): RegionPack? = versions[regionId to version]
    override suspend fun activate(pack: RegionPack, sha256: String) {
        val key = pack.regionId to pack.version
        require(digests[key] == null || digests[key] == sha256) { "Versions are immutable" }
        versions[key] = pack; digests[key] = sha256; activeVersions[pack.regionId] = pack.version
    }
}

/** A road-style route a -> b -> c over the fixture coordinates, shaped like a provider response. */
internal fun fixtureRoute(requestId: Long = 1): CampusRoute {
    val points = listOf(GeoPoint(20.0, 85.0), GeoPoint(20.0, 85.001), GeoPoint(20.001, 85.001))
    val first = points[0].distanceTo(points[1]); val total = first + points[1].distanceTo(points[2])
    val seconds = total / 1.35
    return CampusRoute("fixture-route", requestId, "test", "fixture-v1", "target", "", points, emptyList(), total, seconds,
        listOf(Maneuver("Head east", points[0], 0.0, "Start"), Maneuver("Turn left", points[1], first, "Left"), Maneuver("You have arrived", points[2], total, "Arrive")),
        "", source = "valhalla", profile = "walking", provider = "fixture", fetchedAtEpochMillis = 1L, destinationPoint = points.last(), destinationTitle = "Test target",
        shapeElapsedSeconds = listOf(0.0, first / 1.35, seconds))
}

/** Always answers with the fixture route, or with a failure when [failure] is set. */
internal class FixedRouting(private val failure: RouteResult.Unavailable? = null) : RoutingProvider {
    override suspend fun plan(request: PlanRequest, pinnedVersion: String?): RouteResult = failure ?: RouteResult.Ready(fixtureRoute(request.requestId))
}
