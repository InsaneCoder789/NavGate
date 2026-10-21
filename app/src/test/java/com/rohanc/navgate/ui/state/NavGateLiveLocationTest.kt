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
            vm.onLocationFix(LocationFix(point,h.time,4.0));advanceUntilIdle()
            assertEquals(point,vm.backendState.value.session.location)
            assertTrue(vm.backendState.value.session.alongMeters>15)
        }finally{h.backend.close()}
    }
    @Test fun `a teleport does not trigger fake reroute or arrival`()=runTest {
        val h=BackendTestHarness(dispatcher);val vm=h.viewModel()
        try {
            start(h,vm);h.time+=100;vm.onLocationFix(LocationFix(h.b,h.time,4.0));advanceUntilIdle()
            assertEquals(SessionStatus.SignalLimited,vm.backendState.value.session.status)
            assertFalse(vm.uiState.value.snapshot.isArrived)
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
    @Test fun `start explains a weak GPS signal instead of silently doing nothing`()=runTest {
        val h=BackendTestHarness(dispatcher);val vm=h.viewModel()
        try {
            advanceUntilIdle();vm.selectOrigin(h.appPlace(0));vm.selectDestination(h.appPlace(1));advanceUntilIdle()
            vm.onLocationFix(LocationFix(h.a,h.time,60.0));advanceUntilIdle();vm.startNavigation();advanceUntilIdle()
            assertEquals(SessionStatus.Preview,vm.backendState.value.session.status)
            val notice=vm.uiState.value.startNotice
            assertTrue("notice=$notice",notice!=null && notice.contains("60 m"))
            // A good fix clears the notice and begins guidance.
            h.time+=1_000;vm.onLocationFix(LocationFix(h.a,h.time,4.0));advanceUntilIdle();vm.startNavigation();advanceUntilIdle()
            assertEquals(SessionStatus.Navigating,vm.backendState.value.session.status)
            assertNull(vm.uiState.value.startNotice)
        }finally{h.backend.close()}
    }
}
