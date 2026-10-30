package com.rohanc.navgate.ui.state

import com.rohanc.navgate.model.RouteHistoryEntry
import com.rohanc.navgate.model.TravelProfile
import com.rohanc.navgate.ui.ar.PracticeRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/** Audit regressions use disposable in-memory state, never the attached phone's trip history. */
@OptIn(ExperimentalCoroutinesApi::class)
class UiAuditStateRegressionTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }

    @Test fun practiceDoesNotRetainOrdinaryEndpointsAndExitClearsIt() = runTest {
        val h = BackendTestHarness(dispatcher); val vm = h.viewModel()
        try {
            advanceUntilIdle()
            vm.selectOrigin(h.appPlace(0)); vm.selectDestination(h.appPlace(1)); advanceUntilIdle()
            assertNotNull(vm.uiState.value.selectedDestination)
            vm.previewLocalRoute(PracticeRoute.build(h.a, 0.0, h.epochMillis())); advanceUntilIdle()
            assertNull(vm.uiState.value.selectedOrigin)
            assertNull(vm.uiState.value.selectedDestination)
            assertTrue(vm.uiState.value.modeEstimates.isEmpty())
            assertEquals(PracticeRoute.PLACE_ID, vm.backendState.value.session.route?.destinationPlaceId)
            vm.clearPlan(); advanceUntilIdle()
            assertNull(vm.backendState.value.session.route)
            assertTrue(vm.uiState.value.modeEstimates.isEmpty())
        } finally { h.backend.close() }
    }

    @Test fun arrivalFeedbackIsLoadedAndRapidChangesPersistInOrder() = runTest {
        val h = BackendTestHarness(dispatcher)
        h.users.recordRouteHistory(RouteHistoryEntry(destinationId="b",destinationTitle="Test Destination",travelMode=TravelProfile.Walking,recordedAtEpochMillis=h.epochMillis(),etaSeconds=80.0,tripId="qa-trip"))
        h.users.saveTripFeedback("qa-trip", setOf("Well lit"))
        val vm = h.viewModel()
        try {
            advanceUntilIdle()
            assertEquals(setOf("Well lit"), vm.uiState.value.tripFeedback["qa-trip"])
            vm.saveTripFeedback("qa-trip", setOf("Well lit", "Easy to follow"))
            vm.saveTripFeedback("qa-trip", setOf("Easy to follow"))
            assertEquals(setOf("Easy to follow"), vm.uiState.value.tripFeedback["qa-trip"])
            advanceUntilIdle()
            assertEquals(setOf("Easy to follow"), h.users.tripFeedback("qa-trip"))
            vm.saveTripFeedback("qa-trip", emptySet()); advanceUntilIdle()
            assertTrue(h.users.tripFeedback("qa-trip").isEmpty())
        } finally { h.backend.close() }
    }

    @Test fun practiceCannotReplaceAnActiveTripOrClearItsEndpoints() = runTest {
        val h=BackendTestHarness(dispatcher); val vm=h.viewModel()
        try {
            advanceUntilIdle(); vm.selectOrigin(h.appPlace(0));vm.selectDestination(h.appPlace(1));advanceUntilIdle()
            vm.onLocationFix(com.rohanc.navgate.core.LocationFix(h.a,h.time,4.0));advanceUntilIdle()
            vm.startNavigation();advanceUntilIdle()
            assertTrue(vm.backendState.value.session.status in com.rohanc.navgate.core.NavigationSession.ACTIVE)
            val route=vm.backendState.value.session.route
            val destination=vm.uiState.value.selectedDestination
            vm.previewLocalRoute(PracticeRoute.build(h.a,0.0,h.epochMillis()));advanceUntilIdle()
            assertEquals(route,vm.backendState.value.session.route)
            assertEquals(destination,vm.uiState.value.selectedDestination)
        } finally { h.backend.close() }
    }
}
