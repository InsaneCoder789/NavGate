package com.rohanc.navgate.ui.state

import com.rohanc.navgate.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class NavGateLiveLocationTest {
    private val dispatcher=StandardTestDispatcher()
    @Before fun setUp(){Dispatchers.setMain(dispatcher)}
    @After fun tearDown(){Dispatchers.resetMain()}
    private suspend fun TestScope.start(h:BackendTestHarness,vm:NavGateViewModel){
        advanceUntilIdle();vm.selectOrigin(h.appPlace(0));vm.selectDestination(h.appPlace(1));advanceUntilIdle()
        vm.onLocationFix(LocationFix(h.a,h.time,4.0));advanceUntilIdle();vm.startNavigation();advanceUntilIdle()
    }
    @Test fun `live samples update shared position and total route progress`()=runTest {
        val h=BackendTestHarness(dispatcher);val vm=h.viewModel()
        try {
            start(h,vm);h.time+=10_000;val point=GeoPoint(20.0,85.0002)
            vm.onRawFix(LocationFix(point,h.time,4.0));advanceUntilIdle()
            assertEquals(point,vm.backendState.value.session.location)
            assertTrue(vm.backendState.value.session.alongMeters>15)
        }finally{h.backend.close()}
    }
    @Test fun `a teleport does not trigger fake reroute or arrival`()=runTest {
        val h=BackendTestHarness(dispatcher);val vm=h.viewModel()
        try {
            start(h,vm);h.time+=100;vm.onRawFix(LocationFix(h.b,h.time,4.0));advanceUntilIdle()
            // The impossible jump is ignored quietly: no arrival, no reroute, progress unchanged.
            assertNotEquals(SessionStatus.Arrived,vm.backendState.value.session.status)
            assertFalse(vm.uiState.value.snapshot.isArrived)
            assertFalse(vm.backendState.value.session.offRoute)
        }finally{h.backend.close()}
    }
    @Test fun `stale location suspends progress until signal recovers`()=runTest {
        val h=BackendTestHarness(dispatcher);val vm=h.viewModel()
        try {
            start(h,vm);h.time+=10_000;vm.checkSignal();advanceUntilIdle()
            assertEquals(SessionStatus.SignalLimited,vm.backendState.value.session.status)
            assertEquals(0.0,vm.backendState.value.session.alongMeters,0.0)
        }finally{h.backend.close()}
    }
    @Test fun `start goes ahead on a weak GPS signal without a warning`()=runTest {
        val h=BackendTestHarness(dispatcher);val vm=h.viewModel()
        try {
            advanceUntilIdle();vm.selectOrigin(h.appPlace(0));vm.selectDestination(h.appPlace(1));advanceUntilIdle()
            vm.onLocationFix(LocationFix(h.a,h.time,60.0));advanceUntilIdle();vm.startNavigation();advanceUntilIdle()
            assertEquals(SessionStatus.Navigating,vm.backendState.value.session.status)
            assertNull(vm.uiState.value.startNotice)
        }finally{h.backend.close()}
    }
}
