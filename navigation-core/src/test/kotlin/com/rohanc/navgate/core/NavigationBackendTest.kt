package com.rohanc.navgate.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationBackendTest {
    private class Clock : BackendClock {
        var time=1_000L
        override fun elapsedMillis()=time
        override fun epochMillis()=1_800_000_000_000L+time
    }
    private class Data(private val regions:RegionStore, val pack:RegionPack=fixturePack()) : BackendData {
        var failInitialization=false
        override suspend fun initialize() { check(!failInitialization);regions.activate(pack,"a".repeat(64)) }
        override suspend fun search(query:String,regionId:String):List<CampusPlace> {
            withContext(NonCancellable) { delay(if(query=="old") 1000 else 1) }
            return listOf(pack.places.first().copy(title=query))
        }
        override suspend fun place(regionId:String,id:String)=pack.places.find{it.id==id}
    }
    private class Sessions : SessionStore {
        var checkpoint:SessionCheckpoint?=null
        var fail=false
        val summaries=mutableMapOf<String,SessionSnapshot>()
        override suspend fun loadCheckpoint()=checkpoint
        override suspend fun persist(snapshot:SessionSnapshot,updatedAtEpochMillis:Long) {
            check(!fail)
            summaries[requireNotNull(snapshot.sessionId)]=snapshot
            checkpoint=if(snapshot.status in NavigationSession.ACTIVE) SessionCheckpoint(snapshot) else null
        }
    }

    @Test fun `initialization search plan start stop and recover share one backend`() = runTest {
        val dispatcher=StandardTestDispatcher(testScheduler); val regions=MemoryRegionStore();val clock=Clock();val data=Data(regions);val store=Sessions()
        val backend=NavigationBackend(this,data,regions,clock,store,compute=dispatcher,io=dispatcher,routingProvider=FixedRouting())
        try {
            advanceUntilIdle();assertFalse(backend.state.value.initializing)
            backend.search("target");advanceUntilIdle();assertEquals("target",backend.state.value.places.first().title)
            backend.plan(PlanRequest(5,"test",fixturePack().nodes.first().point,"target",destinationPoint=fixtureRoute().destinationPoint));advanceUntilIdle()
            assertEquals(SessionStatus.Preview,backend.state.value.session.status)
            backend.location(LocationFix(fixturePack().nodes.first().point,clock.time,4.0));advanceUntilIdle()
            backend.start();advanceUntilIdle()
            val id=backend.state.value.session.sessionId
            assertEquals(SessionStatus.Navigating,backend.state.value.session.status)
            assertNotNull(store.checkpoint)
            backend.presentation(Presentation.CameraAssist);advanceUntilIdle()
            assertEquals(id,backend.state.value.session.sessionId)
            backend.stop();advanceUntilIdle()
            assertEquals(SessionStatus.Stopped,backend.state.value.session.status)
            assertNull(store.checkpoint)
            assertEquals(1,store.summaries.size)
        } finally { backend.close() }
    }

    @Test fun `new search cancels old work and result generations prevent overwrite`() = runTest {
        val d=StandardTestDispatcher(testScheduler);val regions=MemoryRegionStore();val backend=NavigationBackend(this,Data(regions),regions,Clock(),compute=d,io=d,routingProvider=FixedRouting())
        try {
            advanceUntilIdle();backend.search("old");runCurrent();backend.search("new");advanceUntilIdle()
            assertEquals("new",backend.state.value.query)
            assertEquals("new",backend.state.value.places.single().title)
        } finally { backend.close() }
    }

    @Test fun `stop invalidates an outstanding plan and cannot be undone by late completion`() = runTest {
        val d=StandardTestDispatcher(testScheduler);val regions=MemoryRegionStore();val backend=NavigationBackend(this,Data(regions),regions,Clock(),compute=d,io=d,routingProvider=FixedRouting())
        try {
            advanceUntilIdle();backend.plan(PlanRequest(1,"test",fixturePack().nodes.first().point,"target",destinationPoint=fixtureRoute().destinationPoint));backend.stop();advanceUntilIdle()
            assertEquals(SessionStatus.Stopped,backend.state.value.session.status)
            assertFalse(backend.state.value.planning)
        } finally { backend.close() }
    }

    @Test fun `persistence failure preserves active guidance and is surfaced`() = runTest {
        val d=StandardTestDispatcher(testScheduler);val regions=MemoryRegionStore();val clock=Clock();val store=Sessions().apply{fail=true}
        val backend=NavigationBackend(this,Data(regions),regions,clock,store,compute=d,io=d,routingProvider=FixedRouting())
        try {
            advanceUntilIdle();backend.plan(PlanRequest(1,"test",fixturePack().nodes.first().point,"target",destinationPoint=fixtureRoute().destinationPoint));advanceUntilIdle()
            backend.location(LocationFix(fixturePack().nodes.first().point,clock.time,4.0));advanceUntilIdle();backend.start();advanceUntilIdle()
            assertEquals(SessionStatus.Navigating,backend.state.value.session.status)
            assertNotNull(backend.state.value.storageError)
        } finally { backend.close() }
    }

    @Test fun `initialization failure can be explicitly retried`() = runTest {
        val d=StandardTestDispatcher(testScheduler);val regions=MemoryRegionStore();val data=Data(regions).apply{failInitialization=true}
        val backend=NavigationBackend(this,data,regions,Clock(),compute=d,io=d,routingProvider=FixedRouting())
        try {
            advanceUntilIdle();assertNotNull(backend.state.value.storageError)
            data.failInitialization=false;backend.retryInitialization();advanceUntilIdle()
            assertNull(backend.state.value.storageError)
            assertFalse(backend.state.value.initializing)
        } finally { backend.close() }
    }
}
