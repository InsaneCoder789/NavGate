package com.rohanc.navgate.data.routing

import com.rohanc.navgate.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicitly enabled live HTTP contract test using public Mumbai road coordinates. */
class LiveValhallaRoutingTest {
    @Test fun `live Mumbai pedestrian and car requests drive real provider metrics and arrival`()=runBlocking {
        assumeTrue("Set NAVGATE_LIVE_ROUTES=true for live public HTTP",System.getenv("NAVGATE_LIVE_ROUTES")=="true")
        val regions=object:RegionStore {
            override suspend fun active(regionId:String):RegionPack?=null
            override suspend fun version(regionId:String,version:String):RegionPack?=null
            override suspend fun activate(pack:RegionPack,sha256:String)=Unit
        }
        val data=object:BackendData {
            override suspend fun initialize()=Unit
            override suspend fun search(query:String,regionId:String)=emptyList<CampusPlace>()
            override suspend fun place(regionId:String,id:String):CampusPlace?=null
        }
        val provider=HybridRoutingProvider(regions,ValhallaRoutingProvider())
        for(mode in listOf("walking","driving")) {
            var now=1000L
            val clock=object:BackendClock {override fun elapsedMillis()=now;override fun epochMillis()=System.currentTimeMillis()}
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val backend=NavigationBackend(scope,data,regions,clock,routingProvider=provider)
            try {
                withTimeout(10_000){backend.state.first{!it.initializing}}
                backend.plan(PlanRequest(1,"mumbai",GeoPoint(18.92297,72.83329),"marine",profile=mode,destinationPoint=GeoPoint(18.9341,72.82365),destinationTitle="Marine Drive"))
                val preview=withTimeout(35_000){backend.state.first{!it.planning && (it.session.route!=null || it.routeFailure!=null)}}
                assertNull("$mode: ${preview.routeFailure}",preview.routeFailure)
                val route=preview.session.route!!
                assertEquals(mode,route.profile);assertTrue(route.distanceMeters in 1000.0..5000.0);assertTrue(route.estimatedSeconds>0)
                backend.location(LocationFix(route.geometry.first(),now,4.0));backend.start()
                assertEquals(SessionStatus.Navigating,backend.state.value.session.status)
                for((a,b) in route.geometry.zipWithNext()) {
                    val pieces=kotlin.math.ceil(a.distanceTo(b)/8.0).toInt().coerceAtLeast(1)
                    for(piece in 1..pieces) {
                        if(backend.state.value.session.status==SessionStatus.Arrived)break
                        val f=piece.toDouble()/pieces
                        val point=GeoPoint(a.latitude+(b.latitude-a.latitude)*f,a.longitude+(b.longitude-a.longitude)*f)
                        val last=backend.state.value.session.location ?: route.geometry.first()
                        now+=(last.distanceTo(point)/(if(mode=="driving")12.0 else 1.35)*1000).toLong().coerceAtLeast(1000)
                        backend.location(LocationFix(point,now,4.0));backend.start() // actor barrier; does not restart an active session
                        assertTrue("$mode ${backend.state.value.session.signalReason}",backend.state.value.session.status in setOf(SessionStatus.Navigating,SessionStatus.Arrived))
                    }
                }
                repeat(3){now+=1000;backend.location(LocationFix(route.geometry.last(),now,4.0));backend.start()}
                assertEquals(SessionStatus.Arrived,backend.state.value.session.status)
                assertEquals(0.0,backend.state.value.session.remainingSeconds,0.0)
                println("LIVE_MUMBAI mode=$mode metres=${route.distanceMeters} seconds=${route.estimatedSeconds} vertices=${route.geometry.size} arrived=true")
            }finally{backend.close();scope.cancel()}
        }
    }
}
