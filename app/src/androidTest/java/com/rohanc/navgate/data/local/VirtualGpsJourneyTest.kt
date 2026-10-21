package com.rohanc.navgate.data.local

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rohanc.navgate.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in emulator test. Synthetic graph lives only in a unique test DB, never in APK region assets. */
@RunWith(AndroidJUnit4::class)
class VirtualGpsJourneyTest {
    private fun emit(message:String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2,Bundle().apply{putString("stream","NAVGATE_VIRTUAL_$message\n")})
    }

    @Test fun emulatorGpsDrivesTwoLocationTripAndPersistsArrival()=runBlocking {
        assumeTrue("Use scripts/run_virtual_navigation.py for coordinated emulator GPS injection",
            InstrumentationRegistry.getArguments().getString("virtualGpsJourney")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        // All graph/session writes below use a unique disposable database, including on the existing app.
        // Production-package execution additionally requires an explicit opt-in on an emulator.
        val isolatedDatabaseOptIn=InstrumentationRegistry.getArguments().getString("isolatedDatabaseOnExistingEmulator")=="true"
        val emulator=android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk_gphone")
        assertTrue("Use the isolated installation or explicit emulator-only disposable database mode",
            context.packageName.endsWith(".virtualtest") || (isolatedDatabaseOptIn && emulator))
        val databaseName="virtual-gps-${UUID.randomUUID()}.db"
        var database=Room.databaseBuilder(context,NavGateDatabase::class.java,databaseName).build()
        val regions=RoomRegionStore(database)
        val origin=GeoPoint(20.0,85.0)
        val destination=GeoPoint(20.0,85.001)
        val pack=RegionPack(regionId="virtual-test",version="synthetic-gps-v1",city="Synthetic Test City",
            coverage=Coverage(listOf(GeoPoint(19.99,84.99),GeoPoint(19.99,85.01),GeoPoint(20.01,85.01),GeoPoint(20.01,84.99))),
            source="SYNTHETIC EMULATOR TEST ONLY: not a KIIT walking path",attribution="NavGate test fixture",license="CC0-1.0",
            generatedAt="2026-10-19",reviewedAt="synthetic-test-only",
            nodes=listOf(GraphNode("a",origin),GraphNode("b",destination)),
            edges=listOf(GraphEdge("ab","a","b",listOf(origin,destination),Access.Allowed,true,"Synthetic test segment")),
            places=listOf(CampusPlace("a","Virtual location A",city="Synthetic Test City",point=origin),
                CampusPlace("b","Virtual location B",city="Synthetic Test City",point=destination)),
            entrances=listOf(Entrance("b-entry","b","b","Synthetic endpoint B",true)))
        assertTrue(RegionPackInstaller(regions).installBundled(Json.encodeToString(pack).toByteArray()) is InstallResult.Installed)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val users=RoomUserPlacesStore(context,database)
        val repository=LocalNavigationRepository(context,regions,users)
        val backend=NavigationBackend(scope,repository,regions,AndroidBackendClock,RoomSessionStore(database,regions))
        val manager=context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val acceptGps=AtomicBoolean(true)
        val listener=object:LocationListener {
            override fun onLocationChanged(location:Location) {
                if(!acceptGps.get()) return
                val fix=LocationFix(GeoPoint(location.latitude,location.longitude),location.elapsedRealtimeNanos/1_000_000,
                    if(location.hasAccuracy()) location.accuracy.toDouble() else Double.POSITIVE_INFINITY)
                scope.launch{backend.location(fix)}
            }
            override fun onProviderEnabled(provider:String)=Unit
            override fun onProviderDisabled(provider:String)=Unit
        }
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)
        try {
            assertTrue("Emulator GPS provider must be enabled",manager.isProviderEnabled(LocationManager.GPS_PROVIDER))
            withTimeout(15_000){backend.state.first{!it.initializing}}
            @Suppress("MissingPermission")
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,500L,1f,listener,Looper.getMainLooper())
            emit("READY")
            val acquired=withTimeout(30_000){backend.state.first{it.session.location?.distanceTo(origin)?.let{distance -> distance<3}==true}}
            assertTrue(acquired.session.accuracyMeters?.let{it>0 && it<=25}==true)
            backend.search("Virtual location","virtual-test")
            withTimeout(10_000){backend.state.first{it.places.size==2 && !it.searching}}
            backend.plan(PlanRequest(1,"virtual-test",origin,"b"))
            val preview=withTimeout(10_000){backend.state.first{it.session.status==SessionStatus.Preview}}
            assertTrue(requireNotNull(preview.session.route).distanceMeters in 100.0..110.0)
            assertEquals(listOf("ab"),preview.session.route?.edgeIds)
            backend.start()
            assertEquals(SessionStatus.Navigating,backend.state.value.session.status)
            val tripId=requireNotNull(backend.state.value.session.sessionId)
            emit("WALKING")
            withTimeout(30_000){backend.state.first{it.session.alongMeters>=25}}
            acceptGps.set(false)
            // Inject a controlled delivery outage while preserving real monotonic clock timing.
            emit("DROPOUT")
            delay(9_500)
            backend.tick()
            // A short outage no longer interrupts guidance: the trip keeps moving along the route and says so.
            val following=withTimeout(5_000){backend.state.first{it.session.signalReason?.contains("No GPS")==true}}
            assertEquals(SessionStatus.Navigating,following.session.status)
            // Only a long outage is reported as lost signal.
            delay(17_000)
            backend.tick()
            val limited=withTimeout(5_000){backend.state.first{it.session.status==SessionStatus.SignalLimited}}
            assertTrue(limited.session.remainingMeters>50)
            assertNotEquals(SessionStatus.Arrived,limited.session.status)
            assertEquals(tripId,limited.session.sessionId)
            acceptGps.set(true)
            emit("RESUME")
            val recovered=withTimeout(20_000){backend.state.first{it.session.status==SessionStatus.Navigating}}
            assertEquals(tripId,recovered.session.sessionId)
            val arrived=withTimeout(60_000){backend.state.first{it.session.status==SessionStatus.Arrived}}
            assertEquals(tripId,arrived.session.sessionId)
            assertEquals(0.0,arrived.session.remainingMeters,0.01)
            assertTrue(arrived.session.location?.distanceTo(destination)?.let{it<=10}==true)
            withTimeout(10_000){while(database.user().checkpoint()!=null || database.user().trips(10).isEmpty()) delay(50)}
            val trip=database.user().trips(10).single()
            val summary=Json.decodeFromString<com.rohanc.navgate.model.RouteHistoryEntry>(trip.payload)
            assertEquals("Arrived",summary.status)
            assertNotNull(summary.finishedAtEpochMillis)
            backend.close();scope.cancel()
            database.close()
            database=Room.databaseBuilder(context,NavGateDatabase::class.java,databaseName).build()
            assertNull(database.user().checkpoint())
            assertEquals(trip.id,database.user().trips(10).single().id)
            emit("PASSED distance=${preview.session.route?.distanceMeters} remaining=${arrived.session.remainingMeters} tripPersisted=true dropoutRecovered=true")
        } finally {
            manager.removeUpdates(listener)
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            backend.close();scope.cancel();database.close();context.deleteDatabase(databaseName)
        }
    }
}
