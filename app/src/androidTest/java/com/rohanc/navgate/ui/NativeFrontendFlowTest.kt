package com.rohanc.navgate.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rohanc.navgate.core.*
import com.rohanc.navgate.data.*
import com.rohanc.navgate.data.local.toAppPlace
import com.rohanc.navgate.ui.state.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Synthetic topology and preferences are confined to this test, with no real user writes. */
@RunWith(AndroidJUnit4::class)
class NativeFrontendFlowTest {
    @get:Rule val compose=createComposeRule()
    private val store=ViewModelStore()
    private lateinit var vm:NavGateViewModel
    private lateinit var fixture:Fixture
    @Before fun setup(){
        fixture=Fixture()
        compose.runOnUiThread{
            vm=NavGateViewModel({scope->NavigationBackend(scope,fixture,fixture,fixture)},fixture.users,InMemoryAppPreferencesStore(storedCityMode=CityMode.KiitBeta),fixture)
            store.put("test-vm",vm)
        }
        compose.setContent{NavGateFrontend(injectedViewModel=vm,renderMap=false,trackLocation=false)}
        compose.waitUntil(10_000){vm.uiState.value.preferencesLoaded&&!vm.backendState.value.initializing}
    }
    @After fun teardown(){compose.runOnUiThread{store.clear()}}

    @Test fun actualButtonsOpenOnboardingUtilitiesPersistBookmarkAndPlanSelectedEndpoints(){
        compose.onNodeWithText("Worldwide",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Offline & region data").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Continue to map").performScrollTo().performClick()
        compose.onNodeWithText("Search anywhere").performClick()
        compose.waitUntil(5_000){vm.uiState.value.places.size==3}
        compose.onNodeWithText("Destination Place").performScrollTo().performClick()
        compose.onNodeWithText("Save place").performScrollTo().performClick()
        compose.waitUntil(5_000){vm.uiState.value.savedPlaces.any{it.id=="b"}}
        assertEquals("b",runBlocking{fixture.users.savedPlaces()}.single().id)
        compose.onNodeWithText("Directions",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("From: Choose start").performScrollTo().performClick()
        compose.waitUntil(5_000){vm.uiState.value.places.size==3}
        compose.onNodeWithText("Origin Place").performScrollTo().performClick()
        compose.waitUntil(5_000){vm.backendState.value.session.status==SessionStatus.Preview}
        assertEquals("a",vm.uiState.value.selectedOrigin?.id)
        assertEquals("b",vm.uiState.value.selectedDestination?.id)
        compose.onNodeWithText("All steps").performScrollTo().performClick()
        compose.onNodeWithText("Return to map").performScrollTo().performClick()
        compose.onNodeWithText("Your walking route").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Directions",substring=false).assertIsDisplayed()
    }

    @Test fun floatingDockNavigatesAllDestinationsAndRetainsSavedSearchCaller(){
        compose.onNodeWithText("Continue to map").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Expand panel").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Collapse panel").performScrollTo().performClick()
        compose.onNodeWithText("Go",substring=false).performClick()
        compose.onNodeWithText("From: Choose start").assertExists()
        compose.onNodeWithText("Saved",substring=false).performClick()
        compose.onNodeWithText("Search saved places",substring=false).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Search saved places",substring=false).assertExists()
        compose.onNodeWithText("Recents",substring=false).performClick()
        compose.onNodeWithText("Explore",substring=false).performClick()
        compose.onNodeWithText("Search anywhere").assertExists()
    }

    @Test fun missingStartCoordinateDoesNotSilentlyLeaveSelection(){
        compose.onNodeWithText("Continue to map").performScrollTo().performClick()
        compose.onNodeWithText("Go",substring=false).performClick()
        compose.onNodeWithText("From: Choose start").performScrollTo().performClick()
        compose.waitUntil(5_000){vm.uiState.value.places.size==3}
        compose.onNodeWithText("Unlocated Place").performScrollTo().performClick()
        compose.onNodeWithText("This place has no reviewed starting coordinate. Choose another start.").assertIsDisplayed()
        compose.onNodeWithText("OK",substring=false).performClick()
        assertNull(vm.uiState.value.selectedOrigin)
        compose.onNodeWithText("Choose start",substring=false).assertExists()
    }

    @Test fun activeTripKeepsUtilitiesAndWarningThenExplicitStopEndsSharedSession(){
        compose.onNodeWithText("Continue to map").performScrollTo().performClick()
        compose.runOnUiThread{vm.selectOrigin(fixture.places[0].toAppPlace());vm.selectDestination(fixture.places[1].toAppPlace());vm.onLocationFix(LocationFix(fixture.a,fixture.time,4.0))}
        compose.waitUntil(5_000){vm.backendState.value.session.status==SessionStatus.Preview&&vm.uiState.value.currentLocationUsable}
        compose.runOnUiThread{vm.startNavigation()}
        compose.waitUntil(5_000){vm.backendState.value.session.status==SessionStatus.Navigating}
        val trip=vm.backendState.value.session.sessionId
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Appearance").performScrollTo().assertIsDisplayed()
        compose.runOnUiThread{fixture.time+=9000;vm.checkSignal()}
        compose.waitUntil(5_000){vm.backendState.value.session.status==SessionStatus.SignalLimited}
        compose.onNodeWithText("Appearance").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Location needs attention").assertIsDisplayed()
        compose.onNodeWithText("Steps",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("Return to map").performScrollTo().performClick()
        compose.onNodeWithText("Location needs attention").assertIsDisplayed()
        assertEquals(trip,vm.backendState.value.session.sessionId)
        compose.onNodeWithText("Stop navigation").performScrollTo().performClick()
        compose.waitUntil(5_000){vm.backendState.value.session.status==SessionStatus.Stopped}
        compose.onNodeWithText("Where would you like to go?").assertIsDisplayed()
    }

    private class Fixture:BackendClock,RegionStore,BackendData {
        var time=1000L
        override fun elapsedMillis()=time
        override fun epochMillis()=1_800_000_000_000+time
        val a=GeoPoint(20.0,85.0);val b=GeoPoint(20.0,85.001)
        val places=listOf(CampusPlace("a","Origin Place",city="Bhubaneswar",point=a),CampusPlace("b","Destination Place",city="Bhubaneswar",point=b),CampusPlace("unknown","Unlocated Place",city="Bhubaneswar"))
        val users=InMemoryUserPlacesStore()
        val pack=RegionPack(regionId="kiit",version="UI-TEST-ONLY",city="Bhubaneswar",coverage=Coverage(listOf(GeoPoint(19.99,84.99),GeoPoint(19.99,85.01),GeoPoint(20.01,85.01),GeoPoint(20.01,84.99))),source="Synthetic Compose UI test",attribution="Test only",license="CC0-1.0",generatedAt="2026-10-19",reviewedAt="test-only",nodes=listOf(GraphNode("a",a),GraphNode("b",b)),edges=listOf(GraphEdge("ab","a","b",listOf(a,b),Access.Allowed,true)),places=places,entrances=listOf(Entrance("entry","b","b","Reviewed test entrance",true)))
        override suspend fun initialize()=Unit
        override suspend fun active(regionId:String)=pack.takeIf{it.regionId==regionId}
        override suspend fun version(regionId:String,version:String)=active(regionId)?.takeIf{it.version==version}
        override suspend fun activate(pack:RegionPack,sha256:String)=error("Read-only fixture")
        override suspend fun search(query:String,regionId:String)=places.filter{it.title.contains(query,true)}
        override suspend fun place(regionId:String,id:String)=places.find{it.id==id}
    }
}
