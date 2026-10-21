package com.rohanc.navgate.data.routing

import com.rohanc.navgate.core.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class HybridRoutingProviderTest {
    private val pack=RegionPack(regionId="campus",version="v1",city="Test",coverage=Coverage(listOf(GeoPoint(19.0,72.0),GeoPoint(19.0,72.01),GeoPoint(19.01,72.01),GeoPoint(19.01,72.0))),source="test",attribution="test",license="CC0",generatedAt="2026-10-20",entrances=listOf(Entrance("gate","target","node","Gate",true)))
    private val store=object:RegionStore {
        override suspend fun active(regionId:String)=pack.takeIf{it.regionId==regionId}
        override suspend fun version(regionId:String,version:String)=active(regionId)?.takeIf{it.version==version}
        override suspend fun activate(pack:RegionPack,sha256:String)=Unit
    }
    @Test fun `outside campus walking and driving use road router even for campus destination`()=runTest {
        val requests=mutableListOf<PlanRequest>()
        val road=object:RoutingProvider { override suspend fun plan(request:PlanRequest,pinnedVersion:String?):RouteResult {
            requests+=request;return RouteResult.Unavailable(RouteFailure.NoConnection,"road called")
        }}
        val provider=HybridRoutingProvider(store,road)
        val request=PlanRequest(1,"campus",GeoPoint(18.9,72.0),"target",destinationPoint=GeoPoint(19.001,72.001))
        assertEquals("road called",(provider.plan(request) as RouteResult.Unavailable).detail)
        assertEquals("road called",(provider.plan(request.copy(profile="driving")) as RouteResult.Unavailable).detail)
        assertEquals(2,requests.size)
    }
    @Test fun `explicit campus entrance and pinned reroutes do not silently become road routes`()=runTest {
        var calls=0
        val road=object:RoutingProvider {override suspend fun plan(request:PlanRequest,pinnedVersion:String?):RouteResult {calls++;error("must not route online")}}
        val provider=HybridRoutingProvider(store,road)
        val request=PlanRequest(1,"campus",GeoPoint(19.001,72.001),"target",entranceId="gate")
        assertTrue(provider.plan(request) is RouteResult.Unavailable)
        assertTrue(provider.plan(request.copy(entranceId=null),"v1") is RouteResult.Unavailable)
        assertEquals(0,calls)
    }
    @Test fun `external walking reroute remains on road provider after entering campus coverage`()=runTest {
        var calls=0
        val road=object:RoutingProvider { override suspend fun plan(request:PlanRequest,pinnedVersion:String?):RouteResult {
            calls++;assertEquals("valhalla",request.routingSource);return RouteResult.Unavailable(RouteFailure.NoConnection,"road called")
        }}
        val request=PlanRequest(2,"campus",GeoPoint(19.001,72.001),"target",entranceId="",profile="walking",destinationPoint=GeoPoint(19.002,72.002),routingSource="valhalla")
        assertEquals("road called",(HybridRoutingProvider(store,road).plan(request) as RouteResult.Unavailable).detail)
        assertEquals(1,calls)
    }

    @Test fun `road alternatives reach the provider through the production hybrid boundary`()=runTest {
        val requests=mutableListOf<PlanRequest>()
        val origin=GeoPoint(18.9,72.0)
        val destination=GeoPoint(19.001,72.001)
        val distance=origin.distanceTo(destination)
        val alternative=CampusRoute("alternative",1,"campus","external","target","",listOf(origin,destination),emptyList(),distance,600.0,
            listOf(Maneuver("Start",origin,0.0,"Start"),Maneuver("Arrive",destination,distance,"Arrive")),"provider",
            source="valhalla",profile="driving",provider="test",fetchedAtEpochMillis=1_000,destinationPoint=destination,shapeElapsedSeconds=listOf(0.0,600.0))
        val road=object:RoutingProvider {
            override suspend fun plan(request:PlanRequest,pinnedVersion:String?):RouteResult=error("alternatives must not replan")
            override suspend fun alternatives(request:PlanRequest):List<CampusRoute> {
                requests+=request
                return listOf(alternative.copy(profile=request.profile))
            }
        }
        val provider=HybridRoutingProvider(store,road)
        val outside=PlanRequest(1,"campus",origin,"target",destinationPoint=destination)
        val drivingInside=outside.copy(origin=GeoPoint(19.001,72.001),profile="driving")
        val externalInside=outside.copy(origin=GeoPoint(19.001,72.001),routingSource="valhalla",entranceId="gate")
        for(request in listOf(outside,drivingInside,externalInside)) {
            val results=provider.alternatives(request)
            assertEquals("alternative",results.single().id)
            assertEquals(request.profile,results.single().profile)
        }
        assertEquals(listOf(outside,drivingInside,externalInside),requests)
    }

    @Test fun `local and unknown provider alternatives never escape to the road router`()=runTest {
        var calls=0
        val road=object:RoutingProvider {
            override suspend fun plan(request:PlanRequest,pinnedVersion:String?):RouteResult=error("not planning")
            override suspend fun alternatives(request:PlanRequest):List<CampusRoute> {calls++;error("must stay local")}
        }
        val provider=HybridRoutingProvider(store,road)
        val inside=PlanRequest(1,"campus",GeoPoint(19.001,72.001),"target",destinationPoint=GeoPoint(19.002,72.002))
        val outside=inside.copy(origin=GeoPoint(18.9,72.0))
        for(request in listOf(inside,outside.copy(entranceId="gate"),outside.copy(routingSource="local_campus_astar"),outside.copy(routingSource="unknown"))) {
            assertTrue(provider.alternatives(request).isEmpty())
        }
        assertEquals(0,calls)
    }
}
