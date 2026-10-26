package com.rohanc.navgate.ui.state

import com.rohanc.navgate.core.*
import com.rohanc.navgate.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class NavGateFrontendIntegrationTest {
    private val dispatcher=StandardTestDispatcher()
    @Before fun setUp(){ Dispatchers.setMain(dispatcher) }
    @After fun tearDown(){ Dispatchers.resetMain() }

    @Test fun `current location selection reports failure without silently changing origin`()=runTest {
        val h=BackendTestHarness(dispatcher);val vm=h.viewModel()
        try {
            advanceUntilIdle()
            assertFalse(vm.useCurrentOrigin())
            assertNull(vm.uiState.value.selectedOrigin)
            assertTrue(vm.uiState.value.statusMessage!!.contains("choose a start manually"))
            vm.onLocationFix(LocationFix(h.a,h.time,4.0));advanceUntilIdle()
            assertTrue(vm.useCurrentOrigin())
            // Selection dispatches a real backend clear when no destination exists. Complete it
            // before closing the backend, matching the ViewModel lifecycle ordering.
            advanceUntilIdle()
            assertEquals(h.a.latitude,vm.uiState.value.selectedOrigin!!.latitude)
            h.time+=700_000 // older than ten minutes is too stale to plan from
            assertFalse(vm.useCurrentOrigin())
        } finally { h.backend.close() }
    }

    @Test fun `idle location usability expires on signal tick without backend publication`()=runTest {
        val h=BackendTestHarness(dispatcher);val vm=h.viewModel()
        try {
            advanceUntilIdle();assertFalse(vm.uiState.value.currentLocationUsable)
            vm.onLocationFix(LocationFix(h.a,h.time,4.0));advanceUntilIdle()
            assertTrue(vm.uiState.value.currentLocationUsable)
            h.time+=9_000;vm.checkSignal();advanceUntilIdle()
            assertEquals(SessionStatus.Idle,vm.backendState.value.session.status)
            assertFalse(vm.uiState.value.currentLocationUsable)
        } finally { h.backend.close() }
    }

    @Test fun `loaded flag prevents onboarding defaults escaping delayed preference read`()=runTest {
        val h=BackendTestHarness(dispatcher)
        val ready=CompletableDeferred<Unit>()
        val base=InMemoryAppPreferencesStore(onboardingSeen=true,storedCityMode=CityMode.Kiit)
        val prefs=object:AppPreferencesStore by base {
            override suspend fun cityMode():CityMode { ready.await();return base.cityMode() }
        }
        val vm=h.viewModel(prefs)
        try {
            runCurrent();assertFalse(vm.uiState.value.preferencesLoaded)
            ready.complete(Unit);advanceUntilIdle()
            assertTrue(vm.uiState.value.preferencesLoaded)
            assertFalse(vm.uiState.value.showOnboarding)
        } finally { h.backend.close() }
    }

    @Test fun `region readiness is read only after delayed backend initialization`()=runTest {
        val h=BackendTestHarness(dispatcher);val ready=CompletableDeferred<Unit>()
        h.initializeData={ready.await()}
        val vm=h.viewModel()
        try {
            runCurrent();assertNull(vm.uiState.value.regionStatus)
            ready.complete(Unit);advanceUntilIdle()
            assertEquals(RegionReadiness.Catalogue,vm.uiState.value.regionStatus?.readiness)
        } finally { h.backend.close() }
    }

    @Test fun `saved search filters persisted saved places without changing catalogue search`()=runTest {
        val h=BackendTestHarness(dispatcher);val vm=h.viewModel()
        try {
            advanceUntilIdle();vm.toggleSaved(h.appPlace(0));vm.toggleSaved(h.appPlace(1));advanceUntilIdle()
            assertEquals(listOf("b"),vm.searchSaved(" DESTINATION ").map{it.id})
            assertTrue(vm.searchSaved("unknown").isEmpty())
            assertEquals(2,vm.searchSaved("").size)
            assertEquals("",vm.uiState.value.searchQuery)
        } finally { h.backend.close() }
    }

    @Test fun `clear route history preserves saved places and recent searches`()=runTest {
        val h=BackendTestHarness(dispatcher);val vm=h.viewModel()
        try {
            advanceUntilIdle();vm.toggleSaved(h.appPlace(1));vm.selectDestination(h.appPlace(1));advanceUntilIdle()
            h.users.recordRouteHistory(com.rohanc.navgate.model.RouteHistoryEntry(
                destinationId="b",destinationTitle="Test Destination",travelMode=com.rohanc.navgate.model.TravelProfile.Walking,
                recordedAtEpochMillis=h.epochMillis(),etaSeconds=80.0))
            vm.clearRouteHistory();advanceUntilIdle()
            assertTrue(h.users.routeHistory().isEmpty())
            assertTrue(vm.uiState.value.routeHistory.isEmpty())
            assertEquals("b",vm.uiState.value.savedPlaces.single().id)
            assertEquals("b",vm.uiState.value.recentPlaces.single().id)
        } finally { h.backend.close() }
    }

    @Test fun `failed settings write reverts optimistic value and exposes failure`()=runTest {
        val h=BackendTestHarness(dispatcher)
        val prefs=object:AppPreferencesStore by InMemoryAppPreferencesStore(storedCityMode=CityMode.Kiit) {
            override suspend fun setFrontendPreferences(value:FrontendPreferences) { error("Disk unavailable") }
        }
        val vm=h.viewModel(prefs)
        try {
            advanceUntilIdle();val previous=vm.uiState.value.frontendPreferences
            vm.updateFrontendPreferences(previous.copy(reduceTransparency=true))
            assertTrue(vm.uiState.value.frontendPreferences.reduceTransparency)
            advanceUntilIdle()
            assertEquals(previous,vm.uiState.value.frontendPreferences)
            assertTrue(vm.uiState.value.statusMessage!!.contains("could not be saved"))
        } finally { h.backend.close() }
    }
    @Test fun `start here clears an old destination and preview before asking for a new destination`()=runTest {
        val h=BackendTestHarness(dispatcher);val vm=h.viewModel()
        try {
            advanceUntilIdle();vm.selectOrigin(h.appPlace(0));vm.selectDestination(h.appPlace(1));advanceUntilIdle()
            assertNotNull(vm.uiState.value.preview)
            vm.startHere(h.appPlace(1));advanceUntilIdle()
            assertEquals("b",vm.uiState.value.selectedOrigin?.id)
            assertNull(vm.uiState.value.selectedDestination)
            assertNull(vm.backendState.value.session.route)
        }finally{h.backend.close()}
    }
}
