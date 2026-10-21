package com.rohanc.navgate.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic provider shape: tests guidance semantics, never claims a live road-provider check. */
@OptIn(ExperimentalCoroutinesApi::class)
class RoadNavigationTest {
    private val points = listOf(GeoPoint(19.0,72.0), GeoPoint(19.0,72.001), GeoPoint(19.0,72.002))
    private fun route(profile: String = "driving"): CampusRoute {
        val distance = points.lengthMeters() * 1.02
        return CampusRoute("road-1",1,"world","provider-v1","destination","",points,emptyList(),distance,40.0,
            listOf(Maneuver("Continue",points[1],distance/2,"Straight"),Maneuver("Arrive",points.last(),distance,"Arrive")),
            "",source="valhalla",profile=profile,provider="test-provider",fetchedAtEpochMillis=1_000,
            destinationPoint=points.last(),destinationTitle="Synthetic destination",shapeElapsedSeconds=listOf(0.0,30.0,40.0))
    }
    private suspend fun started(route:CampusRoute):NavigationSession = NavigationSession().also {
        it.preview(route); it.location(LocationFix(points.first(),1_000,4.0),1_000);it.start(1_000)
    }

    @Test fun `driving uses road distance and segment ETA then arrives only with repeated fixes`() = runTest {
        val road=route();val session=started(road)
        val halfway=session.location(LocationFix(points[1],6_000,4.0),6_000).snapshot
        assertEquals(SessionStatus.Navigating,halfway.status)
        assertEquals(road.distanceMeters/2,halfway.alongMeters,0.1)
        assertEquals(10.0,halfway.remainingSeconds,0.1)
        assertEquals(SessionStatus.SignalLimited,session.tick(15_000).snapshot.status)
        val end=session.location(LocationFix(points.last(),16_000,4.0),16_000).snapshot
        assertEquals(SessionStatus.Navigating,end.status)
        assertEquals(SessionStatus.Arrived,session.location(LocationFix(points.last(),17_000,4.0),17_000).snapshot.status)
    }

    @Test fun `walking rejects the same impossible jump that driving accepts`() = runTest {
        val session=started(route("walking"))
        assertEquals(SessionStatus.SignalLimited,session.location(LocationFix(points[1],6_000,4.0),6_000).snapshot.status)
        assertEquals(0.0,session.state.value.alongMeters,0.0)
    }

    @Test fun `walker recovers after a signal outage longer than thirty seconds`() = runTest {
        val session=started(route("walking"))
        assertEquals(SessionStatus.SignalLimited,session.tick(20_000).snapshot.status)
        // 168 m in two minutes is an ordinary walking pace; the old 30 s cap (≈130 m) rejected it forever.
        val back=session.location(LocationFix(GeoPoint(19.0,72.0016),121_000,4.0),121_000).snapshot
        assertEquals(SessionStatus.Navigating,back.status)
        assertTrue(back.alongMeters>150.0)
    }

    @Test fun `outage recovery still rejects a physically impossible walking jump`() = runTest {
        val session=started(route("walking"))
        session.tick(20_000)
        assertEquals(SessionStatus.SignalLimited,session.location(LocationFix(points.last(),31_000,4.0),31_000).snapshot.status)
    }

    @Test fun `driver arrives from fixes a second apart while moving through the destination zone`() = runTest {
        val session=started(route())
        session.location(LocationFix(GeoPoint(19.0,72.0015),11_000,4.0),11_000)
        // About 21 m, then 6 m, before the end at roughly 16 m/s. The old 4 m radius counted neither.
        assertEquals(SessionStatus.Navigating,session.location(LocationFix(GeoPoint(19.0,72.0018),16_000,4.0),16_000).snapshot.status)
        assertEquals(SessionStatus.Arrived,session.location(LocationFix(GeoPoint(19.0,72.00194),17_000,4.0),17_000).snapshot.status)
    }

    @Test fun `walker is not marked arrived at sixteen meters`() = runTest {
        val session=started(route("walking"))
        session.location(LocationFix(GeoPoint(19.0,72.0005),20_000,4.0),20_000)
        session.location(LocationFix(GeoPoint(19.0,72.001),40_000,4.0),40_000)
        session.location(LocationFix(GeoPoint(19.0,72.00185),60_000,4.0),60_000)
        assertEquals(SessionStatus.Navigating,session.location(LocationFix(GeoPoint(19.0,72.00185),61_000,4.0),61_000).snapshot.status)
    }

    @Test fun `external reroute preserves travel mode destination and rejects wrong mode replacement`() = runTest {
        val road=route(); val session=started(road)
        val off=GeoPoint(18.9996,72.0)
        session.location(LocationFix(off,3_000,4.0),3_000)
        session.location(LocationFix(off,4_000,4.0),4_000)
        val request=requireNotNull(session.location(LocationFix(off,5_000,4.0),5_000).reroute)
        assertEquals("driving",request.request.profile)
        assertEquals("valhalla",request.request.routingSource)
        assertEquals(road.destinationPoint,request.request.destinationPoint)
        assertEquals(road.destinationTitle,request.request.destinationTitle)
        assertNull(request.pinnedVersion)
        assertNull(session.location(LocationFix(off,6_000,4.0),6_000).reroute)
        val wrong=road.copy(requestId=request.requestId,profile="walking")
        assertEquals(SessionStatus.GuidanceUnavailable,session.replace(request,RouteResult.Ready(wrong),6_000).snapshot.status)
        assertEquals("driving",session.state.value.route?.profile)
    }

    @Test fun `successful reroute accepts updated provider graph while retaining driving trip`() = runTest {
        val road=route();val session=started(road)
        val off=GeoPoint(18.9996,72.0)
        session.location(LocationFix(off,3_000,4.0),3_000)
        session.location(LocationFix(off,4_000,4.0),4_000)
        val request=requireNotNull(session.location(LocationFix(off,5_000,4.0),5_000).reroute)
        val geometry=listOf(off,points[1],points.last())
        val distance=geometry.lengthMeters()
        val replacement=road.copy(requestId=request.requestId,graphVersion="provider-v2",geometry=geometry,distanceMeters=distance,
            maneuvers=listOf(Maneuver("Continue",points[1],off.distanceTo(points[1]),"Straight"),Maneuver("Arrive",points.last(),distance,"Arrive")))
        val updated=session.replace(request,RouteResult.Ready(replacement),5_000).snapshot
        assertEquals(SessionStatus.Navigating,updated.status)
        assertEquals("provider-v2",updated.route?.graphVersion)
        assertEquals("driving",updated.route?.profile)
        assertEquals(0.0,updated.alongMeters,0.0)
        session.stop()
        assertEquals(SessionStatus.Stopped,session.replace(request,RouteResult.Ready(replacement),5_000).snapshot.status)
    }

    @Test fun `driver moving fifty meters during reroute joins replacement with current progress`() = runTest {
        val road=route();val session=started(road)
        val off=GeoPoint(18.9996,72.0)
        session.location(LocationFix(off,3_000,4.0),3_000)
        session.location(LocationFix(off,4_000,4.0),4_000)
        val request=requireNotNull(session.location(LocationFix(off,5_000,4.0),5_000).reroute)
        val moved=GeoPoint(off.latitude,72.0005)
        session.location(LocationFix(moved,6_000,4.0),6_000)
        val shape=listOf(off,GeoPoint(off.latitude,72.001),points.last())
        val distance=shape.lengthMeters()
        val replacement=road.copy(requestId=request.requestId,geometry=shape,distanceMeters=distance,
            maneuvers=listOf(Maneuver("Continue",shape[1],off.distanceTo(shape[1]),"Straight"),Maneuver("Arrive",shape.last(),distance,"Arrive")))
        val updated=session.replace(request,RouteResult.Ready(replacement),6_000).snapshot
        assertEquals(SessionStatus.Navigating,updated.status)
        assertTrue(updated.alongMeters in 50.0..55.0)
        assertEquals(distance-updated.alongMeters,updated.remainingMeters,0.01)
        assertEquals(replacement.remainingSecondsAt(updated.alongMeters),updated.remainingSeconds,0.01)
        assertEquals(6_000L,request.originFixElapsedMillis+1_000)
        assertNotEquals(SessionStatus.Arrived,updated.status)
    }

    @Test fun `replacement cannot shift requesting origin or revive after excessive latency`() = runTest {
        suspend fun pending():Pair<NavigationSession,RerouteRequest> {
            val session=started(route()); val off=GeoPoint(18.9996,72.0)
            session.location(LocationFix(off,3_000,4.0),3_000)
            session.location(LocationFix(off,4_000,4.0),4_000)
            return session to requireNotNull(session.location(LocationFix(off,5_000,4.0),5_000).reroute)
        }
        val (session,request)=pending()
        // A perfectly valid shape from another origin is still not this pending reroute.
        val elsewhere=GeoPoint(18.998,72.0)
        val shape=listOf(elsewhere,points[1],points.last());val distance=shape.lengthMeters()
        val wrongStart=route().copy(requestId=request.requestId,geometry=shape,distanceMeters=distance,
            maneuvers=listOf(Maneuver("Continue",points[1],elsewhere.distanceTo(points[1]),"Straight"),Maneuver("Arrive",points.last(),distance,"Arrive")))
        assertEquals(SessionStatus.GuidanceUnavailable,session.replace(request,RouteResult.Ready(wrongStart),5_000).snapshot.status)
        val (late,lateRequest)=pending()
        late.location(LocationFix(lateRequest.request.origin,36_000,4.0),36_000)
        val replacementShape=listOf(lateRequest.request.origin,points[1],points.last())
        val replacementDistance=replacementShape.lengthMeters()
        val replacement=route().copy(requestId=lateRequest.requestId,geometry=replacementShape,distanceMeters=replacementDistance,
            maneuvers=listOf(Maneuver("Continue",points[1],replacementShape[0].distanceTo(points[1]),"Straight"),Maneuver("Arrive",points.last(),replacementDistance,"Arrive")))
        assertEquals(SessionStatus.GuidanceUnavailable,late.replace(lateRequest,RouteResult.Ready(replacement),36_000).snapshot.status)
    }

    @Test fun `external checkpoint validates without installed graph and requires fresh resume fix`() = runTest {
        val session=started(route())
        session.location(LocationFix(points[1],6_000,4.0),6_000)
        val checkpoint=SessionCheckpoint(session.state.value)
        assertTrue(RouteIntegrity.externalCheckpointErrors(checkpoint).isEmpty())
        val restored=NavigationSession()
        assertEquals(SessionStatus.ResumeAvailable,restored.restore(checkpoint,true).snapshot.status)
        assertEquals(SessionStatus.ResumeAvailable,restored.start(9_000).snapshot.status)
        restored.location(LocationFix(points[1],9_000,4.0),9_000)
        assertEquals(SessionStatus.Navigating,restored.start(9_000).snapshot.status)
        assertTrue(RouteIntegrity.externalCheckpointErrors(SessionCheckpoint(checkpoint.snapshot.copy(remainingSeconds=999.0))).isNotEmpty())
    }

    @Test fun `backend injects road provider without campus graph and restores external checkpoint`() = runTest {
        val dispatcher=StandardTestDispatcher(testScheduler)
        val data=object:BackendData {
            override suspend fun initialize() = Unit
            override suspend fun search(query:String,regionId:String)=emptyList<CampusPlace>()
            override suspend fun place(regionId:String,id:String):CampusPlace?=null
        }
        val clock=object:BackendClock {
            override fun elapsedMillis()=1_000L
            override fun epochMillis()=2_000L
        }
        var checkpoint:SessionCheckpoint?=null
        val store=object:SessionStore {
            override suspend fun loadCheckpoint()=checkpoint
            override suspend fun persist(snapshot:SessionSnapshot,updatedAtEpochMillis:Long) { checkpoint=SessionCheckpoint(snapshot) }
        }
        val provider=object:RoutingProvider {
            override suspend fun plan(request:PlanRequest,pinnedVersion:String?) = RouteResult.Ready(route().copy(requestId=request.requestId))
        }
        val regions=MemoryRegionStore()
        val backend=NavigationBackend(this,data,regions,clock,store,compute=dispatcher,io=dispatcher,routingProvider=provider)
        try {
            advanceUntilIdle()
            backend.plan(PlanRequest(99,"world",points.first(),"destination",profile="driving",destinationPoint=points.last()))
            advanceUntilIdle()
            assertEquals(SessionStatus.Preview,backend.state.value.session.status)
            backend.location(LocationFix(points.first(),1_000,4.0));advanceUntilIdle()
            backend.start();advanceUntilIdle()
            assertEquals(SessionStatus.Navigating,backend.state.value.session.status)
            assertNotNull(checkpoint)
        } finally { backend.close() }
        val restored=NavigationBackend(this,data,regions,clock,store,compute=dispatcher,io=dispatcher,routingProvider=provider)
        try {
            advanceUntilIdle()
            assertEquals(SessionStatus.ResumeAvailable,restored.state.value.session.status)
            assertNull(restored.state.value.session.location)
        } finally { restored.close() }
    }

    @Test fun `external route rejects missing timing provenance wrong endpoint and inflated metrics`() {
        val road=route()
        assertTrue(RouteIntegrity.shapeErrors(road).isEmpty())
        listOf(road.copy(shapeElapsedSeconds=emptyList()),road.copy(shapeElapsedSeconds=listOf(0.0,41.0,40.0)),
            road.copy(fetchedAtEpochMillis=null),road.copy(destinationPoint=GeoPoint(0.0,0.0)),
            road.copy(distanceMeters=road.distanceMeters*2),road.copy(profile="bicycle"),
            road.copy(maneuvers=road.maneuvers.dropLast(1))).forEach { assertTrue(RouteIntegrity.shapeErrors(it).isNotEmpty()) }
    }
}
