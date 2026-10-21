package com.rohanc.navgate.data.local

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rohanc.navgate.core.*
import com.rohanc.navgate.data.routing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in live public requests and production core/Room, synthetic clock-driven fix stream. */
@RunWith(AndroidJUnit4::class)
class RoadRoutingLiveTest {
    @Test fun realMumbaiWalkingAndCarDirectionsDriveModeCorrectProgressAndArrival()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveRoadRoutes")=="true")
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,NavGateDatabase::class.java).build()
        try {
            val regions=RoomRegionStore(db)
            val provider=HybridRoutingProvider(regions,ValhallaRoutingProvider(cache=RoomRoadRouteCache(db)))
            val data=object:BackendData {override suspend fun initialize()=Unit;override suspend fun search(query:String,regionId:String)=emptyList<CampusPlace>();override suspend fun place(regionId:String,id:String):CampusPlace?=null}
            for(mode in listOf("walking","driving")) {
                var time=1000L
                val clock=object:BackendClock {override fun elapsedMillis()=time;override fun epochMillis()=System.currentTimeMillis()}
                val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
                val backend=NavigationBackend(scope,data,regions,clock,RoomSessionStore(db,regions),routingProvider=provider)
                try {
                    withTimeout(15_000){backend.state.first{!it.initializing}}
                    backend.plan(PlanRequest(1,"mumbai",GeoPoint(18.92297,72.83329),"marine",profile=mode,destinationPoint=GeoPoint(18.9341,72.82365),destinationTitle="Marine Drive"))
                    val state=withTimeout(35_000){backend.state.first{!it.planning && (it.session.route!=null || it.routeFailure!=null)}}
                    assertNull("$mode ${state.routeFailure}",state.routeFailure)
                    val route=state.session.route!!
                    assertEquals(mode,route.profile);assertTrue(route.distanceMeters>1000);assertTrue(route.estimatedSeconds>0)
                    backend.location(LocationFix(route.geometry.first(),time,4.0));backend.start()
                    assertEquals(SessionStatus.Navigating,backend.state.value.session.status)
                    // Walk/driving fixes traverse real provider geometry with plausible monotonic times.
                    for((a,b) in route.geometry.zipWithNext()) {
                        val pieces=kotlin.math.ceil(a.distanceTo(b)/8.0).toInt().coerceAtLeast(1)
                        for(piece in 1..pieces) {
                            if(backend.state.value.session.status==SessionStatus.Arrived)break
                            val f=piece.toDouble()/pieces
                            val point=GeoPoint(a.latitude+(b.latitude-a.latitude)*f,a.longitude+(b.longitude-a.longitude)*f)
                            val previous=backend.state.value.session.location ?: route.geometry.first()
                            time+=(previous.distanceTo(point)/(if(mode=="driving")12.0 else 1.35)*1000).toLong().coerceAtLeast(1000)
                            backend.location(LocationFix(point,time,4.0))
                            withTimeout(10_000){backend.state.first{it.session.location==point || it.session.status==SessionStatus.Arrived}}
                        }
                    }
                    repeat(3){time+=1000;backend.location(LocationFix(route.geometry.last(),time,4.0));backend.start()}
                    assertEquals(SessionStatus.Arrived,backend.state.value.session.status)
                    assertEquals(0.0,backend.state.value.session.remainingMeters,0.01)
                    InstrumentationRegistry.getInstrumentation().sendStatus(2,Bundle().apply{putString("stream","LIVE_MUMBAI_$mode distance=${route.distanceMeters} seconds=${route.estimatedSeconds} arrived=true\n")})
                }finally{backend.close();scope.cancel()}
            }
        }finally{db.close()}
    }
}
