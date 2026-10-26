package com.rohanc.navgate.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NavigationSessionTest {
    private fun route(): CampusRoute = fixtureRoute()
    private fun fix(point:GeoPoint,time:Long,accuracy:Double=4.0)=LocationFix(point,time,accuracy)

    @Test fun `a trip starts from where you are on the route, not only from its first point`() = runTest {
        val session=NavigationSession(); val route=route()
        session.preview(route)
        val middle=route.geometry[route.geometry.size/2]
        session.location(fix(middle,100),100)
        val started=session.start(100).snapshot
        assertEquals(SessionStatus.Navigating,started.status); assertTrue(started.alongMeters>0.0); assertTrue(started.remainingMeters<route.distanceMeters)
    }

    @Test fun `a trip starts with no location at all`() = runTest {
        val session=NavigationSession(); val route=route()
        session.preview(route)
        val started=session.start(100).snapshot
        assertEquals(SessionStatus.Navigating,started.status); assertEquals(0.0,started.alongMeters,0.0)
    }

    @Test fun `weak or old GPS does not stop a trip from starting`() = runTest {
        val session=NavigationSession(); val route=route()
        session.preview(route)
        session.location(fix(route.geometry.first(),100,100.0),100)
        assertEquals(SessionStatus.Navigating,session.start(100).snapshot.status)
        val old=NavigationSession(); old.preview(route)
        old.location(fix(route.geometry.first(),100),100)
        assertEquals(SessionStatus.Navigating,old.start(60_000).snapshot.status)
    }

    @Test fun `standing far from the route does not stop a trip from starting`() = runTest {
        val session=NavigationSession(); val route=route()
        session.preview(route)
        session.location(fix(route.geometry.first().offset(5_000.0,0.0),100),100)
        val started=session.start(100).snapshot
        assertEquals(SessionStatus.Navigating,started.status); assertNotNull(started.route)
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
        // A teleport right after a good fix is ignored quietly: guidance carries on and never arrives early.
        assertNotEquals(SessionStatus.Arrived,session.location(fix(route.geometry.last(),200),200).snapshot.status)
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
        assertNull(request.pinnedVersion)
        assertEquals("valhalla",request.request.routingSource)
        assertNull(session.location(fix(off,14_000),14_000).reroute)
        session.stop()
        assertEquals(SessionStatus.Stopped,session.replace(request,RouteResult.Ready(route.copy(requestId=request.requestId))).snapshot.status)
    }

    @Test fun `an online trip can be rerouted by the offline router when the network drops`() = runTest {
        val session=NavigationSession(); val route=route()
        session.preview(route);session.location(fix(route.geometry.first(),1_000),1_000);session.start(1_000)
        val off=GeoPoint(19.9997,85.0)
        session.location(fix(off,11_000),11_000);session.location(fix(off,12_000),12_000)
        val request=requireNotNull(session.location(fix(off,13_000),13_000).reroute)
        val end=route.geometry.last(); val length=off.distanceTo(end); val seconds=length/1.35
        val offlineRoute=route.copy(requestId=request.requestId,source="brouter",provider="brouter-offline",id="offline-detour",geometry=listOf(off,end),distanceMeters=length,estimatedSeconds=seconds,
            maneuvers=listOf(Maneuver("Head north",off,0.0,"Start"),Maneuver("You have arrived",end,length,"Arrive")),shapeElapsedSeconds=listOf(0.0,seconds))
        val after=session.replace(request,RouteResult.Ready(offlineRoute)).snapshot
        assertNotEquals("unavailable: ${after.signalReason}",SessionStatus.GuidanceUnavailable,after.status)
        assertEquals("brouter",after.route?.source)
    }
}
