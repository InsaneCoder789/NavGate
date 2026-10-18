package com.rohanc.navgate.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RoutePlannerVersionTest {
    @Test fun `active graph changes never replace pinned trip topology`() = runTest {
        val store=MemoryRegionStore();val first=fixturePack();store.activate(first,"v1")
        val planner=RoutePlanner(store)
        val request=PlanRequest(1,"test",first.nodes.first().point,"target")
        val old=(planner.plan(request) as RouteResult.Ready).route
        val second=first.copy(version="v2",edges=first.edges.filter{it.id!="ab"})
        store.activate(second,"v2")
        val current=(planner.plan(request) as RouteResult.Ready).route
        val pinned=(planner.plan(request,"v1") as RouteResult.Ready).route
        assertEquals("v2",current.graphVersion)
        assertTrue(current.distanceMeters>old.distanceMeters)
        assertEquals(old.geometry,pinned.geometry)
        assertEquals("v1",pinned.graphVersion)
        // Exercise eviction and reload without losing graph-version isolation.
        store.activate(first.copy(version="v3"),"v3");planner.plan(request)
        store.activate(first.copy(version="v4"),"v4");planner.plan(request)
        assertEquals(old.geometry,(planner.plan(request,"v1") as RouteResult.Ready).route.geometry)
        assertEquals(RouteFailure.MissingRegion,(planner.plan(request,"missing") as RouteResult.Unavailable).reason)
    }
}
