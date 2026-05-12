package com.rohanc.navgate.ui.state

import com.rohanc.navgate.data.FakeCampusRepository
import com.rohanc.navgate.data.InMemoryUserPlacesStore
import com.rohanc.navgate.model.TravelProfile
import com.rohanc.navgate.navigation.PresentationMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavGateViewModelTest {
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
    fun `route preview appears when origin and destination are selected`() = runTest {
        val repository = FakeCampusRepository()
        val viewModel = NavGateViewModel(repository = repository, userPlacesStore = InMemoryUserPlacesStore())
        advanceUntilIdle()

        val places = repository.allPlaces()
        viewModel.selectOrigin(places[0])
        viewModel.selectDestination(places[1])
        advanceUntilIdle()

        val preview = viewModel.uiState.value.preview
        assertNotNull(preview)
        assertTrue(viewModel.uiState.value.isPreviewVisible)
        assertEquals("North Gate", preview?.originTitle)
        assertEquals("Central Library", preview?.destinationTitle)
    }

    @Test
    fun `starting navigation hides preview and keeps shared route state`() = runTest {
        val repository = FakeCampusRepository()
        val viewModel = NavGateViewModel(repository = repository, userPlacesStore = InMemoryUserPlacesStore())
        advanceUntilIdle()

        val places = repository.allPlaces()
        viewModel.selectOrigin(places[0])
        viewModel.selectDestination(places[2])
        advanceUntilIdle()
        viewModel.startNavigation()

        val state = viewModel.uiState.value
        assertFalse(state.isPreviewVisible)
        assertTrue(state.snapshot.isNavigating)
        assertNotNull(state.snapshot.route)
        assertEquals(AppTab.Go, state.activeTab)
    }

    @Test
    fun `mode switching updates the shared presentation mode`() = runTest {
        val repository = FakeCampusRepository()
        val viewModel = NavGateViewModel(repository = repository, userPlacesStore = InMemoryUserPlacesStore())
        advanceUntilIdle()

        viewModel.switchMode(PresentationMode.AR_ASSIST)

        assertEquals(PresentationMode.AR_ASSIST, viewModel.uiState.value.snapshot.presentationMode)
    }

    @Test
    fun `saved and recent places update through store`() = runTest {
        val repository = FakeCampusRepository()
        val viewModel = NavGateViewModel(repository = repository, userPlacesStore = InMemoryUserPlacesStore())
        advanceUntilIdle()
        val place = repository.allPlaces().first()

        viewModel.toggleSaved(place)
        viewModel.selectDestination(place)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.savedPlaces.any { it.id == place.id })
        assertTrue(viewModel.uiState.value.recentPlaces.any { it.id == place.id })
    }

    @Test
    fun `travel profile changes and recomputes route preview`() = runTest {
        val repository = FakeCampusRepository()
        val viewModel = NavGateViewModel(repository = repository, userPlacesStore = InMemoryUserPlacesStore())
        advanceUntilIdle()
        val places = repository.allPlaces()

        viewModel.selectOrigin(places[0])
        viewModel.selectDestination(places[1])
        advanceUntilIdle()
        viewModel.setTravelProfile(TravelProfile.Driving)
        advanceUntilIdle()

        assertEquals(TravelProfile.Driving, viewModel.uiState.value.travelProfile)
        assertNotNull(viewModel.uiState.value.preview)
    }

    @Test
    fun `starting navigation records route history`() = runTest {
        val repository = FakeCampusRepository()
        val store = InMemoryUserPlacesStore()
        val viewModel = NavGateViewModel(repository = repository, userPlacesStore = store)
        advanceUntilIdle()
        val places = repository.allPlaces()

        viewModel.selectOrigin(places[0])
        viewModel.selectDestination(places[1])
        advanceUntilIdle()
        viewModel.startNavigation()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.routeHistory.any { it.destinationId == places[1].id })
    }
}
