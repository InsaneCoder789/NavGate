package com.rohanc.navgate.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos

/** Synthetic provider results exercise the production coordinator boundary, without network calls. */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationRouteChoicesTest {
    private val origin = GeoPoint(19.0, 72.85)
    private fun at(east: Double, north: Double) = GeoPoint(origin.latitude + north / 111_320.0,
        origin.longitude + east / (111_320.0 * cos(Math.toRadians(origin.latitude))))
    private val destination = at(0.0, 1_000.0)
    private val request = PlanRequest(1, "world", origin, "target", profile = "driving", destinationPoint = destination,
        destinationTitle = "Chosen destination", routingSource = "valhalla")

    private fun route(id: String, points: List<GeoPoint>, requestId: Long = 0): CampusRoute {
        val distance = points.lengthMeters()
        val seconds = 200.0
        val times = points.indices.map { i -> if (i == 0) 0.0 else seconds * points.take(i + 1).lengthMeters() / distance }
        return CampusRoute(id, requestId, "world", "provider-v1", "target", "", points, emptyList(), distance, seconds,
            listOf(Maneuver("Start", points.first(), 0.0, "Start"), Maneuver("Arrive", points.last(), distance, "Arrive")), "",
            source = "valhalla", profile = "driving", provider = "test-provider", fetchedAtEpochMillis = 1_000,
            destinationPoint = destination, destinationTitle = "Chosen destination", shapeElapsedSeconds = times)
    }
    private val primary get() = route("primary", listOf(origin, destination))
    private val alternative get() = route("alternative", listOf(origin, at(500.0, 500.0), destination))
    private val regions = object : RegionStore {
        override suspend fun active(regionId: String): RegionPack? = null
        override suspend fun version(regionId: String, version: String): RegionPack? = null
        override suspend fun activate(pack: RegionPack, sha256: String) = Unit
    }
    private val data = object : BackendData {
        override suspend fun initialize() = Unit
        override suspend fun search(query: String, regionId: String) = emptyList<CampusPlace>()
        override suspend fun place(regionId: String, id: String): CampusPlace? = null
    }
    private val clock = object : BackendClock {
        override fun elapsedMillis() = 1_000L
        override fun epochMillis() = 1_800_000_001_000L
    }
    private fun TestScope.backend(alternatives: suspend () -> List<CampusRoute>): NavigationBackend {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val router = object : RoutingProvider {
            override suspend fun plan(request: PlanRequest, pinnedVersion: String?) = RouteResult.Ready(primary.copy(requestId = request.requestId))
            override suspend fun alternatives(request: PlanRequest) = alternatives()
        }
        return NavigationBackend(this, data, regions, clock, compute = dispatcher, io = dispatcher, routingProvider = router)
    }

    @Test fun `provider alternatives cannot change the selected destination or travel mode`() = runTest {
        val candidates = listOf(
            alternative.copy(id = "wrong-destination", destinationPlaceId = "another-place"),
            alternative.copy(id = "wrong-point", destinationPoint = at(30.0, 1_000.0)),
            alternative.copy(id = "wrong-mode", profile = "walking"),
            alternative.copy(id = "wrong-region", regionId = "another-region"),
            alternative.copy(id = "unknown-source", source = "unknown"),
        )
        val backend = backend { candidates }
        try {
            backend.plan(request); advanceUntilIdle()
            assertEquals(listOf("primary"), backend.state.value.routeChoices.map { it.id })
            for (candidate in candidates) backend.chooseRoute(candidate.id)
            assertEquals("primary", backend.state.value.session.route?.id)
            assertEquals("target", backend.state.value.session.route?.destinationPlaceId)
            assertEquals("driving", backend.state.value.session.route?.profile)
        } finally { backend.close() }
    }

    @Test fun `invalid alternatives are discarded before deduplication and valid choices survive`() = runTest {
        val malformed = alternative.copy(id = "empty-shape", geometry = emptyList(), distanceMeters = primary.distanceMeters)
        val backend = backend { listOf(malformed, alternative) }
        try {
            backend.plan(request); advanceUntilIdle()
            assertEquals(listOf("primary", "alternative"), backend.state.value.routeChoices.map { it.id })
            backend.chooseRoute("alternative")
            assertEquals("alternative", backend.state.value.session.route?.id)
            assertNull(backend.state.value.storageError)
        } finally { backend.close() }
    }

    @Test fun `nearby alternate start snaps are accepted but distant origins are rejected`() = runTest {
        val nearby = route("nearby", listOf(at(90.0, 0.0), at(500.0, 500.0), destination))
        val remote = route("remote", listOf(at(500.0, 0.0), at(500.0, 500.0), destination))
        val backend = backend { listOf(nearby, remote) }
        try {
            backend.plan(request); advanceUntilIdle()
            assertEquals(listOf("primary", "nearby"), backend.state.value.routeChoices.map { it.id })
            backend.chooseRoute("nearby")
            assertEquals("nearby", backend.state.value.session.route?.id)
        } finally { backend.close() }
    }

    @Test fun `clearing a preview ignores later route choices`() = runTest {
        val response = CompletableDeferred<List<CampusRoute>>()
        val backend = backend { response.await() }
        try {
            backend.plan(request); runCurrent()
            assertEquals(SessionStatus.Preview, backend.state.value.session.status)
            backend.clear()
            response.complete(listOf(alternative)); advanceUntilIdle()
            assertEquals(SessionStatus.Idle, backend.state.value.session.status)
            assertTrue(backend.state.value.routeChoices.isEmpty())
            assertNull(backend.state.value.session.route)
        } finally { backend.close() }
    }
}
