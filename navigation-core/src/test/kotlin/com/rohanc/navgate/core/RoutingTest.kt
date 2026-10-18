package com.rohanc.navgate.core

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class RoutingTest {
    private val pack = fixturePack()
    private val request = PlanRequest(1, "test", pack.nodes.first().point, "target")

    @Test fun `A star returns shortest legal edge path and real geometry`() = runTest {
        val route = (CampusRoutingProvider(pack).route(request) as RouteResult.Ready).route
        assertEquals(listOf("ab","bc"), route.edgeIds)
        assertEquals(pack.edges.take(2).sumOf { it.geometry.lengthMeters() },route.distanceMeters,0.01)
        assertEquals("local_campus_astar",route.source)
        assertEquals("v1",route.graphVersion)
        assertEquals("entry",route.entranceId)
    }

    @Test fun `interior start only traverses allowed direction with proportional cost`() = runTest {
        val mid = GeoPoint(20.0,85.0005)
        val route = (CampusRoutingProvider(pack).route(request.copy(origin=mid)) as RouteResult.Ready).route
        assertEquals(mid, route.geometry.first())
        assertEquals(mid.distanceTo(pack.nodes[1].point)+pack.nodes[1].point.distanceTo(pack.nodes[2].point),route.distanceMeters,0.1)
        val reverse = pack.copy(entrances=listOf(Entrance("entry","target","a","Test",true)))
        assertEquals(RouteFailure.NoConnection,(CampusRoutingProvider(reverse).route(request.copy(origin=mid)) as RouteResult.Unavailable).reason)
    }

    @Test fun `restricted and unreviewed edges are excluded`() = runTest {
        val blocked = pack.copy(edges=pack.edges.map { it.copy(access=Access.Restricted) })
        assertEquals(RouteFailure.MissingGraph,(CampusRoutingProvider(blocked).route(request) as RouteResult.Unavailable).reason)
        val unreviewed = pack.copy(edges=pack.edges.map { it.copy(reviewed=false) })
        assertEquals(RouteFailure.MissingGraph,(CampusRoutingProvider(unreviewed).route(request) as RouteResult.Unavailable).reason)
    }

    @Test fun `unavailable results distinguish profile coverage entrance and malformed graph`() = runTest {
        val engine=CampusRoutingProvider(pack)
        assertEquals(RouteFailure.UnsupportedProfile,(engine.route(request.copy(profile="driving")) as RouteResult.Unavailable).reason)
        assertEquals(RouteFailure.OutsideCoverage,(engine.route(request.copy(origin=GeoPoint(0.0,0.0))) as RouteResult.Unavailable).reason)
        assertEquals(RouteFailure.InvalidCoordinate,(engine.route(request.copy(origin=GeoPoint(Double.NaN,0.0))) as RouteResult.Unavailable).reason)
        assertEquals(RouteFailure.MissingEntrance,(engine.route(request.copy(destinationPlaceId="unknown")) as RouteResult.Unavailable).reason)
        assertEquals(RouteFailure.InvalidGraph,(CampusRoutingProvider(pack.copy(nodes=pack.nodes+pack.nodes.first())).route(request) as RouteResult.Unavailable).reason)
    }

    @Test fun `disconnected topology never synthesizes a route`() = runTest {
        val disconnected=pack.copy(entrances=listOf(Entrance("entry","target","d","Test",true)))
        assertEquals(RouteFailure.NoConnection,(CampusRoutingProvider(disconnected).route(request) as RouteResult.Unavailable).reason)
    }

    @Test fun `off network and parallel ambiguous starts require confirmed origin`() = runTest {
        assertEquals(RouteFailure.UncertainOrigin,(CampusRoutingProvider(pack).route(request.copy(origin=GeoPoint(20.006,85.006))) as RouteResult.Unavailable).reason)
        val shiftedNodes = pack.nodes.take(2).map { it.copy(id=it.id+"p",point=it.point.copy(latitude=it.point.latitude+0.0001)) }
        val parallel=pack.copy(nodes=pack.nodes+shiftedNodes,edges=pack.edges+GraphEdge("parallel","ap","bp",shiftedNodes.map{it.point},Access.Allowed,true))
        assertEquals(RouteFailure.UncertainOrigin,(CampusRoutingProvider(parallel).route(request.copy(origin=GeoPoint(20.00005,85.0005))) as RouteResult.Unavailable).reason)
    }

    @Test fun `A star costs match independent Dijkstra on seeded directed grids`() = runTest {
        repeat(30) { seed ->
            val random=Random(seed)
            val nodes=(0 until 16).map { GraphNode("n$it",GeoPoint(20.0+(it/4)*0.001,85.0+(it%4)*0.001)) }
            val edges=buildList {
                for (n in nodes.indices) for (next in listOf(n+1,n+4)) {
                    if (next>=nodes.size || (next==n+1 && next/4!=n/4)) continue
                    val geometry=listOf(nodes[n].point,nodes[next].point)
                    add(GraphEdge("$n-$next",nodes[n].id,nodes[next].id,geometry,Access.Allowed,true))
                    if (random.nextBoolean()) add(GraphEdge("$next-$n",nodes[next].id,nodes[n].id,geometry.reversed(),Access.Allowed,true))
                }
            }
            val graph=pack.copy(nodes=nodes,edges=edges,entrances=listOf(Entrance("entry","target",nodes.last().id,"Test",true)))
            val actual=(CampusRoutingProvider(graph).route(request.copy(origin=nodes.first().point)) as RouteResult.Ready).route.distanceMeters
            // O(V^2) independent reference uses no heuristic or production search queue.
            val best=nodes.associate { it.id to Double.POSITIVE_INFINITY }.toMutableMap(); best[nodes.first().id]=0.0
            val remaining=nodes.map { it.id }.toMutableSet()
            while (remaining.isNotEmpty()) {
                val current=remaining.minBy { best.getValue(it) }; remaining.remove(current)
                for (edge in edges.filter { it.from==current }) {
                    best[edge.to]=minOf(best.getValue(edge.to),best.getValue(current)+edge.geometry.lengthMeters())
                }
            }
            assertEquals(best.getValue(nodes.last().id),actual,0.01)
        }
    }

    @Test fun `search cancellation propagates`() = runTest {
        var cancelled=false
        val job=launch {
            cancel()
            try { CampusRoutingProvider(pack).route(request) } catch (_: CancellationException) { cancelled=true }
        }
        job.join(); assertTrue(cancelled)
    }

    @Test fun `segment projection uses path segments not sparse vertices`() {
        val mid=GeoPoint(20.0,85.0005)
        val projection=requireNotNull(projectPath(mid,pack.edges.first().geometry))
        assertEquals(0.0,projection.distanceMeters,0.01)
        assertEquals(pack.edges.first().geometry.lengthMeters()/2,projection.alongMeters,0.01)
    }
}
