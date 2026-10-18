package com.rohanc.navgate.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NavigationSessionTest {
    private suspend fun route(): CampusRoute = (CampusRoutingProvider(fixturePack()).route(PlanRequest(1,"test",fixturePack().nodes.first().point,"target")) as RouteResult.Ready).route
    private fun fix(point:GeoPoint,time:Long,accuracy:Double=4.0)=LocationFix(point,time,accuracy)

    @Test fun `manual preview cannot start without fresh accurate location at planned start`() = runTest {
        val session=NavigationSession(); val route=route()
        session.preview(route)
        assertEquals(SessionStatus.Preview,session.start(100).snapshot.status)
        session.location(fix(route.geometry.first(),100,100.0),100)
        assertEquals(SessionStatus.Preview,session.start(100).snapshot.status)
        session.location(fix(route.geometry.last(),200),200)
        assertEquals(SessionStatus.Preview,session.start(200).snapshot.status)
        session.location(fix(route.geometry.first(),300),300)
        assertEquals(SessionStatus.Navigating,session.start(300).snapshot.status)
    }

    @Test fun `mode changes preserve trip and sparse segments advance gradually`() = runTest {
        val session=NavigationSession(); val route=route()
        session.preview(route); session.location(fix(route.geometry.first(),1_000),1_000)
        val start=session.start(1_000).snapshot
        session.presentation(Presentation.CameraAssist)
        val current=session.location(fix(GeoPoint(20.0,85.0002),11_000),11_000).snapshot
        assertEquals(start.sessionId,current.sessionId)
        assertEquals(Presentation.CameraAssist,current.presentation)
        assertTrue(current.alongMeters>15)
        assertFalse(current.offRoute)
    }

    @Test fun `stale inaccurate duplicate and teleport fixes cannot cause arrival`() = runTest {
        val session=NavigationSession(); val route=route()
        session.preview(route); session.location(fix(route.geometry.first(),100),100); session.start(100)
        assertEquals(SessionStatus.SignalLimited,session.location(fix(route.geometry.last(),200),200).snapshot.status)
        assertEquals(SessionStatus.SignalLimited,session.tick(10_000).snapshot.status)
        session.location(fix(route.geometry.last(),11_000,200.0),11_000)
        assertNotEquals(SessionStatus.Arrived,session.state.value.status)
        assertEquals(0.0,session.state.value.alongMeters,0.0)
    }

    @Test fun `arrival requires plausible route progress and repeated distinct good fixes`() = runTest {
        val session=NavigationSession(); val route=route()
        session.preview(route); session.location(fix(route.geometry.first(),100),100); session.start(100)
        var time=100L
        for (i in 1..10) {
            time+=5_000
            session.location(fix(GeoPoint(20.0,85.0+0.001*i/10),time),time)
        }
        for (i in 1..10) {
            time+=5_000
            session.location(fix(GeoPoint(20.0+0.001*i/10,85.001),time),time)
        }
        assertNotEquals(SessionStatus.Arrived,session.state.value.status)
        val duplicate=session.location(fix(route.geometry.last(),time),time)
        assertNotEquals(SessionStatus.Arrived,duplicate.snapshot.status)
        time+=1_000
        assertEquals(SessionStatus.Arrived,session.location(fix(route.geometry.last(),time),time).snapshot.status)
        assertEquals(0.0,session.state.value.remainingMeters,0.0)
    }

    @Test fun `checkpoint restore never silently resumes and requires pinned graph`() = runTest {
        val session=NavigationSession(); val route=route()
        session.preview(route);session.location(fix(route.geometry.first(),100),100);session.start(100)
        val checkpoint=SessionCheckpoint(session.state.value)
        val restored=NavigationSession()
        assertEquals(SessionStatus.ResumeAvailable,restored.restore(checkpoint,true).snapshot.status)
        assertNull(restored.state.value.location)
        assertEquals(SessionStatus.ResumeAvailable,restored.start(1_000).snapshot.status)
        assertEquals(SessionStatus.Idle,restored.restore(checkpoint,false).snapshot.status)
    }

    @Test fun `reroute is single flight and result cannot restart a stopped trip`() = runTest {
        val session=NavigationSession(); val route=route()
        session.preview(route);session.location(fix(route.geometry.first(),1_000),1_000);session.start(1_000)
        val off=GeoPoint(19.9997,85.0)
        assertNull(session.location(fix(off,11_000),11_000).reroute)
        assertNull(session.location(fix(off,12_000),12_000).reroute)
        val request=requireNotNull(session.location(fix(off,13_000),13_000).reroute)
        assertEquals("v1",request.pinnedVersion)
        assertNull(session.location(fix(off,14_000),14_000).reroute)
        session.stop()
        assertEquals(SessionStatus.Stopped,session.replace(request,RouteResult.Ready(route.copy(requestId=request.requestId))).snapshot.status)
    }
}
