package com.rohanc.navgate.ui.state

import com.rohanc.navgate.data.FakeCampusRepository
import com.rohanc.navgate.navigation.PresentationMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.advanceUntilIdle
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
        val viewModel = NavGateViewModel(repository = repository)
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
        val viewModel = NavGateViewModel(repository = repository)
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
    }

    @Test
    fun `mode switching updates the shared presentation mode`() = runTest {
        val repository = FakeCampusRepository()
        val viewModel = NavGateViewModel(repository = repository)
        advanceUntilIdle()

        viewModel.switchMode(PresentationMode.AR_ASSIST)

        assertEquals(PresentationMode.AR_ASSIST, viewModel.uiState.value.snapshot.presentationMode)
    }
}
