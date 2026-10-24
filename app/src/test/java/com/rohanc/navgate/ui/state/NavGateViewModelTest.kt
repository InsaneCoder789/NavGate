package com.rohanc.navgate.ui.state

import com.rohanc.navgate.core.*
import com.rohanc.navgate.navigation.PresentationMode
import com.rohanc.navgate.model.TravelProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class NavGateViewModelTest {
    private val dispatcher=StandardTestDispatcher()
    @Before fun setUp(){Dispatchers.setMain(dispatcher)}
    @After fun tearDown(){Dispatchers.resetMain()}

    @Test fun `local preview exposes real route provenance to the example shell`()=runTest {
        val h=BackendTestHarness(dispatcher);val vm=h.viewModel()
        try {
            advanceUntilIdle();vm.selectOrigin(h.appPlace(0));vm.selectDestination(h.appPlace(1));advanceUntilIdle()
            assertTrue(vm.uiState.value.isPreviewVisible)
            assertEquals("valhalla",vm.uiState.value.snapshot.route?.routeSource)
            assertEquals("Test Destination",vm.uiState.value.preview?.destinationTitle)
        } finally{h.backend.close()}
    }
    @Test fun `preview cannot start from a manual origin without fresh real position`()=runTest {
        val h=BackendTestHarness(dispatcher);val vm=h.viewModel()
        try {
            advanceUntilIdle();vm.selectOrigin(h.appPlace(0));vm.selectDestination(h.appPlace(1));advanceUntilIdle();vm.startNavigation();advanceUntilIdle()
            assertFalse(vm.uiState.value.snapshot.isNavigating)
            assertNotNull(vm.uiState.value.statusMessage)
            vm.onLocationFix(LocationFix(h.a,h.time,4.0));advanceUntilIdle();vm.startNavigation();advanceUntilIdle()
            assertTrue(vm.uiState.value.snapshot.isNavigating)
            assertFalse(vm.uiState.value.isPreviewVisible)
        } finally{h.backend.close()}
    }
    @Test fun `unknown coordinates remain nullable and cannot become an origin`()=runTest {
        val h=BackendTestHarness(dispatcher);val vm=h.viewModel()
        try {
            advanceUntilIdle();assertNull(h.appPlace(2).coordinate);vm.selectOrigin(h.appPlace(2));advanceUntilIdle()
            assertNull(vm.uiState.value.selectedOrigin);assertNotNull(vm.uiState.value.statusMessage)
        } finally{h.backend.close()}
    }
    @Test fun `saved and recent records still use the injected local store`()=runTest {
        val h=BackendTestHarness(dispatcher);val vm=h.viewModel()
        try {
            advanceUntilIdle();vm.toggleSaved(h.appPlace(1));vm.selectDestination(h.appPlace(1));advanceUntilIdle()
            assertEquals("b",vm.uiState.value.savedPlaces.single().id)
            assertEquals("b",vm.uiState.value.recentPlaces.first().id)
        } finally{h.backend.close()}
    }
    @Test fun `presentation changes preserve the shared route`()=runTest {
        val h=BackendTestHarness(dispatcher);val vm=h.viewModel()
        try {
            advanceUntilIdle();vm.selectOrigin(h.appPlace(0));vm.selectDestination(h.appPlace(1));advanceUntilIdle()
            val id=vm.backendState.value.session.route?.id
            vm.switchMode(PresentationMode.AR_ASSIST);advanceUntilIdle()
            assertEquals(id,vm.backendState.value.session.route?.id)
            assertEquals(PresentationMode.AR_ASSIST,vm.uiState.value.snapshot.presentationMode)
        } finally{h.backend.close()}
    }
    @Test fun `region change cancels preview and unsupported region has no fake places`()=runTest {
        val h=BackendTestHarness(dispatcher);val vm=h.viewModel()
        try {
            advanceUntilIdle();vm.selectOrigin(h.appPlace(0));vm.selectDestination(h.appPlace(1));advanceUntilIdle()
            vm.setCityMode(CityMode.Mumbai);advanceUntilIdle()
            assertNull(vm.uiState.value.snapshot.route);assertTrue(vm.uiState.value.places.isEmpty())
        } finally{h.backend.close()}
    }
    @Test fun `clearing recents preserves saved places`()=runTest {
        val h=BackendTestHarness(dispatcher);val vm=h.viewModel()
        try {
            advanceUntilIdle();vm.toggleSaved(h.appPlace(1));vm.selectDestination(h.appPlace(1));advanceUntilIdle()
            vm.clearRecents();advanceUntilIdle()
            assertTrue(vm.uiState.value.recentPlaces.isEmpty())
            assertEquals("b",vm.uiState.value.savedPlaces.single().id)
        } finally { h.backend.close() }
    }
    @Test fun `frontend preferences and region readiness reach the native state`()=runTest {
        val h=BackendTestHarness(dispatcher);val vm=h.viewModel()
        try {
            advanceUntilIdle()
            val preferences=com.rohanc.navgate.data.FrontendPreferences(appearance="light",reduceTransparency=true,voiceEnabled=false)
            vm.updateFrontendPreferences(preferences);vm.refreshRegionStatus();advanceUntilIdle()
            assertEquals(preferences,vm.uiState.value.frontendPreferences)
            assertNotNull(vm.uiState.value.regionStatus)
        } finally { h.backend.close() }
    }
}
