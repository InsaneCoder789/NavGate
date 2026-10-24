package com.rohanc.navgate.data.local

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rohanc.navgate.core.*
import com.rohanc.navgate.data.SharedPrefsUserPlacesStore
import com.rohanc.navgate.model.PlaceSearchResult
import com.rohanc.navgate.model.PlaceType
import com.rohanc.navgate.ui.state.CityMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalBackendPersistenceTest {
    private lateinit var context:ScopedContext
    private lateinit var database:NavGateDatabase
    private lateinit var regions:RoomRegionStore
    private lateinit var databaseName:String

    @Before fun setUp() {
        val base=ApplicationProvider.getApplicationContext<Context>()
        val identity="navgate-test-${UUID.randomUUID()}"
        context=ScopedContext(base,identity)
        databaseName="$identity.db"
        open()
    }
    private fun open(){database=Room.databaseBuilder(context,NavGateDatabase::class.java,databaseName).build();regions=RoomRegionStore(database)}
    @After fun tearDown(){database.close();context.deleteDatabase(databaseName);context.clearTestPreferences()}

    private fun pack(version:String="test-v1"):RegionPack {
        val a=GeoPoint(20.0,85.0);val b=GeoPoint(20.0,85.001)
        return RegionPack(regionId="test",version=version,city="Synthetic",
            coverage=Coverage(listOf(GeoPoint(19.99,84.99),GeoPoint(19.99,85.01),GeoPoint(20.01,85.01),GeoPoint(20.01,84.99))),
            source="Instrumentation-only synthetic data",attribution="Test fixture",license="CC0-1.0",generatedAt="2026-10-18",reviewedAt="2026-10-18",
            nodes=listOf(GraphNode("a",a),GraphNode("b",b)),edges=listOf(GraphEdge("ab","a","b",listOf(a,b),Access.Allowed,true)),
            places=listOf(CampusPlace("target","Synthetic Destination",city="Synthetic",point=b),CampusPlace("unlocated","Coordinate Missing",city="Synthetic")),
            entrances=listOf(Entrance("entry","target","b","Test entry",true)))
    }
    private suspend fun install(pack:RegionPack)=RegionPackInstaller(regions).installBundled(Json.encodeToString(pack).toByteArray())

    @Test fun graphActivationIsAtomicAndVersionsAreImmutable()=runBlocking {
        val first=pack();assertTrue(install(first) is InstallResult.Installed)
        assertTrue(install(pack("test-v2").copy(edges=listOf(first.edges.first().copy(from="missing")))) is InstallResult.Rejected)
        assertEquals("test-v1",regions.active("test")?.version)
        assertTrue(install(first.copy(source="Changed same-version content")) is InstallResult.Rejected)
        assertEquals(first,regions.active("test"))
        assertTrue(install(pack("test-v2")) is InstallResult.Installed)
        assertEquals(first,regions.version("test","test-v1"))
        val cursor=database.openHelper.readableDatabase.query("PRAGMA foreign_key_check")
        cursor.use { assertFalse(it.moveToFirst()) }
    }

    @Test fun localSearchPreservesMissingCoordinatesAndTreatsWildcardsLiterally()=runBlocking {
        install(pack())
        assertEquals(2,regions.search("",regionId="test").size)
        assertNull(regions.search("missing",regionId="test").single().point)
        assertTrue(regions.search("%",regionId="test").isEmpty())
        assertTrue(regions.search("",regionId="another-region").isEmpty())
    }

    @Test fun legacyBookmarksMigrateOnceAndSurviveDatabaseReopen()=runBlocking {
        val legacy=SharedPrefsUserPlacesStore(context)
        val place=PlaceSearchResult("legacy","Legacy bookmark","Preserved",20.0,85.0,PlaceType.Academic)
        legacy.toggleSaved(place);legacy.recordRecent(place)
        val users=RoomUserPlacesStore(context,database)
        assertEquals(listOf(place),users.savedPlaces())
        users.toggleSaved(place)
        assertTrue(users.savedPlaces().isEmpty())
        database.close();open()
        assertTrue(RoomUserPlacesStore(context,database).savedPlaces().isEmpty())
        assertEquals(listOf(place),RoomUserPlacesStore(context,database).recentPlaces())
        assertEquals(listOf(place),legacy.savedPlaces()) // original legacy values remain as a non-destructive migration source
    }

    @Test fun datastorePreferencesMigrateAndDoNotResetTheChosenRegion()=runBlocking {
        context.getSharedPreferences("navgate_app_preferences",Context.MODE_PRIVATE).edit()
            .putString("city_mode",CityMode.Mumbai.name).putBoolean("onboarding_seen",true).commit()
        val prefs=DataStoreAppPreferencesStore(context)
        assertEquals(CityMode.Mumbai,prefs.cityMode());assertTrue(prefs.isOnboardingSeen())
        prefs.setCityMode(CityMode.KiitBeta)
        assertEquals(CityMode.KiitBeta,DataStoreAppPreferencesStore(context).cityMode())
    }

    @Test fun checkpointAndTripSurviveReopenThenStopClearsOnlyCheckpoint()=runBlocking {
        val pack=pack();install(pack)
        val route=(com.rohanc.navgate.testing.FixtureRouting().plan(PlanRequest(1,"test",pack.nodes.first().point,"target",destinationPoint=pack.nodes.last().point,destinationTitle="Target")) as RouteResult.Ready).route
        val session=NavigationSession();session.preview(route);session.location(LocationFix(pack.nodes.first().point,1000,4.0),1000);session.start(1000)
        RoomSessionStore(database,regions).persist(session.state.value,1_800_000_000_000)
        database.close();open()
        val store=RoomSessionStore(database,regions)
        val saved=requireNotNull(store.loadCheckpoint())
        val restored=NavigationSession();restored.restore(saved,true)
        assertEquals(SessionStatus.ResumeAvailable,restored.state.value.status)
        assertEquals(SessionStatus.ResumeAvailable,restored.start(2000).snapshot.status)
        restored.stop();store.persist(restored.state.value,1_800_000_001_000)
        assertNull(store.loadCheckpoint());assertEquals(1,database.user().trips(10).size)
    }

    @Test fun bookmarkLimitAndRecentRetentionNeverSilentlyDropSavedPlaces()=runBlocking {
        val users=RoomUserPlacesStore(context,database)
        fun place(id:Int)=PlaceSearchResult("place-$id","Place $id","Test",null,null,PlaceType.Academic)
        repeat(40){users.toggleSaved(place(it))}
        try { users.toggleSaved(place(40));fail("Expected explicit saved-place limit") }
        catch(_:IllegalStateException) { }
        assertEquals(40,database.user().count("saved"))
        assertTrue(users.savedPlaces().any{it.id=="place-0"})
        repeat(45){users.recordRecent(place(it))}
        assertEquals(30,database.user().count("recent"))
        users.clearRecents();assertTrue(users.recentPlaces().isEmpty())
        assertEquals(40,users.savedPlaces().size)
    }

    @Test fun terminalPersistenceKeepsTripStartAndFinishAndClearHistoryRemovesBothSummaries()=runBlocking {
        val pack=pack();install(pack)
        val route=(com.rohanc.navgate.testing.FixtureRouting().plan(PlanRequest(1,"test",pack.nodes.first().point,"target",destinationPoint=pack.nodes.last().point,destinationTitle="Target")) as RouteResult.Ready).route
        val store=RoomSessionStore(database,regions)
        val active=SessionSnapshot(sessionId="stable-trip",route=route,status=SessionStatus.Navigating,
            remainingMeters=route.distanceMeters,remainingSeconds=route.estimatedSeconds)
        store.persist(active,1000)
        store.persist(active,2000)
        val stopped=active.copy(status=SessionStatus.Stopped)
        store.persist(stopped,3000);store.persist(stopped,4000)
        val summary=Json.decodeFromString<com.rohanc.navgate.model.RouteHistoryEntry>(database.user().trips(10).single().payload)
        assertEquals(1000L,summary.recordedAtEpochMillis)
        assertEquals(3000L,summary.finishedAtEpochMillis)
        val users=RoomUserPlacesStore(context,database)
        assertEquals(1,users.routeHistory().size)
        users.clearHistory()
        assertTrue(users.routeHistory().isEmpty());assertTrue(database.user().trips(10).isEmpty())
        assertNull(store.loadCheckpoint())
    }

    @Test fun productionCatalogueSearchListsTestPlaces()=runBlocking {
        val users=RoomUserPlacesStore(context,database)
        val repository=LocalNavigationRepository(context,regions,users)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val backend=NavigationBackend(scope,repository,regions,AndroidBackendClock,RoomSessionStore(database,regions),routingProvider=com.rohanc.navgate.testing.FixtureRouting())
        try {
            withTimeout(15_000){backend.state.first{!it.initializing}}
            backend.search("","kiit")
            val searched=withTimeout(15_000){backend.state.first{!it.searching && it.places.size==40}}
            val installed=requireNotNull(regions.active("kiit"))
            assertEquals(175,installed.places.size)
            assertEquals(84,installed.places.count{it.point==null})
            assertEquals(RegionReadiness.Catalogue,backend.regionStatus("kiit").readiness)
            assertNull(RoomSessionStore(database,regions).loadCheckpoint())
            assertTrue(database.user().trips(10).isEmpty())
        } finally {backend.close();scope.cancel()}
    }

    /** Isolate legacy prefs and DataStore files; tests never touch the app's real user stores. */
    private class ScopedContext(base:Context,private val prefix:String):ContextWrapper(base){
        private val preferences=mutableSetOf<String>()
        override fun getApplicationContext():Context=this
        override fun getFilesDir():File=File(super.getFilesDir(),prefix).apply{mkdirs()}
        override fun getSharedPreferences(name:String,mode:Int):SharedPreferences {
            val scoped="$prefix-$name";preferences+=scoped;return super.getSharedPreferences(scoped,mode)
        }
        fun clearTestPreferences(){preferences.forEach{super.deleteSharedPreferences(it)}}
    }
}
