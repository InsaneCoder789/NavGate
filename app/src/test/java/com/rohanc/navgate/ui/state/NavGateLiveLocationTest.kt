package com.rohanc.navgate.ui.state

import com.rohanc.navgate.data.InMemoryUserPlacesStore
import com.rohanc.navgate.data.NavigationRepository
import com.rohanc.navgate.model.Coordinate
import com.rohanc.navgate.model.PlaceSearchResult
import com.rohanc.navgate.model.PlaceType
import com.rohanc.navgate.model.RouteRequest
import com.rohanc.navgate.model.RouteResponse
import com.rohanc.navgate.model.RouteStep
import com.rohanc.navgate.model.ManeuverType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavGateLiveLocationTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `location samples update shared user position while navigating`() = runTest {
        val repository = TrackingRepository()
        val viewModel = NavGateViewModel(repository = repository, userPlacesStore = InMemoryUserPlacesStore())
        advanceUntilIdle()

        viewModel.selectOrigin(repository.origin)
        viewModel.selectDestination(repository.destination)
        advanceUntilIdle()
        viewModel.startNavigation()
        viewModel.onLocationSample(repository.midpoint, 42.0, 1500)

        val snapshot = viewModel.uiState.value.snapshot
        assertEquals(repository.midpoint, snapshot.userLocation)
        assertEquals(1, snapshot.currentStepIndex)
    }

    @Test
    fun `off route movement fetches a replacement route`() = runTest {
        val repository = TrackingRepository()
        val viewModel = NavGateViewModel(repository = repository, userPlacesStore = InMemoryUserPlacesStore())
        advanceUntilIdle()

        viewModel.selectOrigin(repository.origin)
        viewModel.selectDestination(repository.destination)
        advanceUntilIdle()
        viewModel.startNavigation()
        viewModel.onLocationSample(Coordinate(20.360000, 85.820000), 80.0, 1200)
        advanceUntilIdle()

        assertTrue(repository.routeRequests >= 2)
        assertEquals(Coordinate(20.360000, 85.820000), viewModel.uiState.value.snapshot.origin)
    }

    @Test
    fun `arriving through live location marks route complete`() = runTest {
        val repository = TrackingRepository()
        val viewModel = NavGateViewModel(repository = repository, userPlacesStore = InMemoryUserPlacesStore())
        advanceUntilIdle()

        viewModel.selectOrigin(repository.origin)
        viewModel.selectDestination(repository.destination)
        advanceUntilIdle()
        viewModel.startNavigation()
        viewModel.onLocationSample(repository.midpoint, 42.0, 1500)
        viewModel.onLocationSample(repository.destination.coordinate, 42.0, 1500)

        val snapshot = viewModel.uiState.value.snapshot
        assertTrue(snapshot.isArrived)
        assertEquals(0.0, snapshot.etaSeconds, 0.0)
    }
}

private class TrackingRepository : NavigationRepository {
    val origin = place("origin", "North Gate", Coordinate(20.349884, 85.807529))
    val midpoint = Coordinate(20.350080, 85.808021)
    val destination = place("destination", "Sports Complex", Coordinate(20.350412, 85.808665))
    var routeRequests = 0

    override suspend fun searchPlaces(query: String): List<PlaceSearchResult> = listOf(origin, destination)

    override suspend fun fetchRoute(request: RouteRequest): RouteResponse {
        routeRequests += 1
        val nextMidpoint = Coordinate((request.origin.latitude + request.destination.latitude) / 2.0, (request.origin.longitude + request.destination.longitude) / 2.0)
        return RouteResponse(
            distanceMeters = 120.0,
            durationSeconds = 90.0,
            pathCoordinates = listOf(request.origin, nextMidpoint, request.destination),
            steps = listOf(
                RouteStep(
                    instruction = "Head toward the center path",
                    distanceMeters = 60.0,
                    targetLatitude = nextMidpoint.latitude,
                    targetLongitude = nextMidpoint.longitude,
                    maneuverType = ManeuverType.Straight,
                ),
                RouteStep(
                    instruction = "Continue to destination",
                    distanceMeters = 60.0,
                    targetLatitude = request.destination.latitude,
                    targetLongitude = request.destination.longitude,
                    maneuverType = ManeuverType.Arrive,
                ),
            ),
        )
    }

    private fun place(id: String, title: String, coordinate: Coordinate) =
        PlaceSearchResult(
            id = id,
            title = title,
            subtitle = title,
            latitude = coordinate.latitude,
            longitude = coordinate.longitude,
            type = PlaceType.Academic,
        )
}
