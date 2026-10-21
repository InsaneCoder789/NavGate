package com.rohanc.navgate.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rohanc.navgate.core.*
import com.rohanc.navgate.data.routing.ValhallaRoutingProvider
import com.rohanc.navgate.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoadRoutePersistenceTest {
    @Test fun realProviderRouteCacheCheckpointAndCarSummarySurviveRoomReopenWithoutCampusPack()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="road-route-${UUID.randomUUID()}.db"
        var db=Room.databaseBuilder(context,NavGateDatabase::class.java,name).build()
        try {
            val request=PlanRequest(1,"mumbai",GeoPoint(18.92297,72.83329),"marine-drive",profile="driving",destinationPoint=GeoPoint(18.9341,72.82365),destinationTitle="Marine Drive")
            val payload=InstrumentationRegistry.getInstrumentation().context.assets.open("routing/mumbai-driving.json").bufferedReader().use{it.readText()}
            val route=(ValhallaRoutingProvider.parse(payload,request,"valhalla1.openstreetmap.de",System.currentTimeMillis()) as RouteResult.Ready).route
            RoomRoadRouteCache(db).write("route-key",route)
            val snapshot=SessionSnapshot(sessionId="trip",route=route,status=SessionStatus.Navigating,remainingMeters=route.distanceMeters,remainingSeconds=route.estimatedSeconds,instruction=route.maneuvers.first().instruction)
            RoomSessionStore(db,RoomRegionStore(db)).persist(snapshot,System.currentTimeMillis())
            db.close();db=Room.databaseBuilder(context,NavGateDatabase::class.java,name).build()
            assertEquals(route,RoomRoadRouteCache(db).read("route-key"))
            val sessionStore=RoomSessionStore(db,RoomRegionStore(db))
            val checkpoint=sessionStore.loadCheckpoint()!!
            assertTrue(RouteIntegrity.externalCheckpointErrors(checkpoint).isEmpty())
            assertEquals("driving",checkpoint.snapshot.route?.profile)
            val summary=Json.decodeFromString<RouteHistoryEntry>(db.user().trips(10).single().payload)
            assertEquals(TravelProfile.Driving,summary.travelMode);assertEquals("Marine Drive",summary.destinationTitle)
            assertNull(RoomRegionStore(db).active("mumbai"))
            sessionStore.persist(checkpoint.snapshot.copy(status=SessionStatus.Arrived,alongMeters=route.distanceMeters,remainingMeters=0.0,remainingSeconds=0.0),System.currentTimeMillis())
            assertNull(sessionStore.loadCheckpoint())
            assertEquals("Arrived",Json.decodeFromString<RouteHistoryEntry>(db.user().trips(10).single().payload).status)
        }finally{db.close();context.deleteDatabase(name)}
    }
}
