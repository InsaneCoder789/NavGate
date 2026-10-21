package com.rohanc.navgate.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.rohanc.navgate.core.*
import com.rohanc.navgate.data.search.IndiaPlaceIndex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Records the actual coverage gap; these assertions must change when a city router is integrated. */
@RunWith(AndroidJUnit4::class)
class MumbaiRoutingCapabilityTest {
    @Test fun realMumbaiPlacesExistButInstalledDataCannotProvideWalkingOrDrivingDirections()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,NavGateDatabase::class.java).build()
        try {
            val regions=RoomRegionStore(db)
            LocalNavigationRepository(context,regions,RoomUserPlacesStore(context,db)).initialize()
            val index=IndiaPlaceIndex(context)
            val gateway=index.search("Gateway of India").first{it.point?.latitude?.let{lat->lat in 18.9..19.0}==true}
            val marine=index.search("Marine Drive").first{it.point?.latitude?.let{lat->lat in 18.9..19.0}==true}
            assertNotNull(gateway.point);assertNotNull(marine.point)
            for(profile in listOf("walking","driving")) {
                val result=RoutePlanner(regions).plan(PlanRequest(1,"mumbai",gateway.point!!,marine.id,profile=profile))
                assertTrue("Must not synthesize $profile directions",result is RouteResult.Unavailable)
                assertEquals(RouteFailure.MissingRegion,(result as RouteResult.Unavailable).reason)
            }
        } finally {db.close()}
    }
}
