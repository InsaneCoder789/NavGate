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
            vm=NavGateViewModel({scope->NavigationBackend(scope,fixture,fixture,fixture,routingProvider=com.rohanc.navgate.testing.FixtureRouting())},fixture.users,InMemoryAppPreferencesStore(storedCityMode=CityMode.KiitBeta),fixture)
            store.put("test-vm",vm)
        }
        compose.setContent{NavGateFrontend(injectedViewModel=vm,renderMap=false,trackLocation=false)}
        compose.waitUntil(10_000){vm.uiState.value.preferencesLoaded&&!vm.backendState.value.initializing}
    }
    @After fun teardown(){compose.runOnUiThread{store.clear()}}

    /** Brand onboarding retains the existing explicit skip action. */
    private fun dismissOnboarding(){ compose.onNodeWithText("Skip").performClick() }

    private fun typeInSearch(text:String){compose.onNode(hasSetTextAction()).performTextInput(text)}

    @Test fun brandedWelcomeHasThreePagesAndLocationRemainsOptional(){
        fun screenshot(name:String){
            if(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("brandingScreenshots")!="true")return
            compose.waitForIdle()
            val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            val image=instrumentation.uiAutomation.takeScreenshot()
            val file=java.io.File(instrumentation.targetContext.cacheDir,"branding-$name.png")
            file.outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            image.recycle()
        }
        compose.onNodeWithText("Find your place").assertIsDisplayed()
        screenshot("discover")
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Choose your route").assertIsDisplayed()
        screenshot("routes")
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText("Find your place").assertIsDisplayed()
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Navigate with confidence").assertIsDisplayed()
        screenshot("navigate")
        compose.onNodeWithText("Explore first").performClick()
        compose.onNodeWithText("Search here").assertExists()
        assertFalse(vm.uiState.value.showOnboarding)
        assertNull(vm.uiState.value.selectedDestination)
        assertNull(vm.backendState.value.session.route)
    }

    @Test fun searchSavePlaceAndPlanDirectionsUsingTheMapsStyleScreens(){
        dismissOnboarding()
        compose.onNodeWithText("Search here").performClick()
        typeInSearch("Place")
        compose.waitUntil(5_000){vm.uiState.value.places.size==3}
        compose.onNodeWithText("Destination Place").performClick()
        compose.onNodeWithContentDescription("Save place").performClick()
        compose.waitUntil(5_000){vm.uiState.value.savedPlaces.any{it.id=="b"}}
        assertEquals("b",runBlocking{fixture.users.savedPlaces()}.single().id)
        compose.onNodeWithText("Directions",substring=false).performClick()
        compose.onNodeWithText("Choose start").performClick()
        typeInSearch("Origin")
        compose.waitUntil(5_000){vm.uiState.value.places.any{it.id=="a"}}
        compose.onNodeWithText("Origin Place").performClick()
        compose.waitUntil(5_000){vm.backendState.value.session.status==SessionStatus.Preview}
        assertEquals("a",vm.uiState.value.selectedOrigin?.id)
        assertEquals("b",vm.uiState.value.selectedDestination?.id)
        compose.onNodeWithText("Walk",substring=false).assertExists()
        compose.onNodeWithText("Steps",substring=false).performClick()
        assertTrue(compose.onAllNodesWithText("Origin Place").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Destination Place").assertExists()
    }

    @Test fun bottomBarNavigatesAllDestinations(){
        dismissOnboarding()
        compose.onNodeWithText("Saved",substring=false).performClick()
        compose.onNodeWithContentDescription("Search saved places").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Search saved places").assertExists()
        compose.onNodeWithText("Recents",substring=false).performClick()
        compose.onNodeWithText("Explore",substring=false).performClick()
        compose.onNodeWithText("Search here").assertExists()
        compose.onNodeWithContentDescription("Directions").performClick()
        compose.onNodeWithText("Choose destination").assertExists()
    }

    @Test fun missingStartCoordinateDoesNotSilentlyLeaveSelection(){
        dismissOnboarding()
        compose.onNodeWithContentDescription("Directions").performClick()
        typeInSearch("Destination")
        compose.waitUntil(5_000){vm.uiState.value.places.any{it.id=="b"}}
        compose.onNodeWithText("Destination Place").performClick()
        compose.waitUntil(5_000){vm.uiState.value.selectedDestination?.id=="b"}
        compose.onNodeWithText("Choose start").performClick()
        typeInSearch("Unlocated")
        compose.waitUntil(5_000){vm.uiState.value.places.any{it.id=="unknown"}}
        compose.onNodeWithText("Unlocated Place").performClick()
        compose.onNodeWithText("This place has no reviewed starting coordinate. Choose another start.").assertIsDisplayed()
        compose.onNodeWithText("OK",substring=false).performClick()
        assertNull(vm.uiState.value.selectedOrigin)
    }

    @Test fun activeTripShowsGuidanceWarnsOnWeakSignalThenExplicitStopEndsSharedSession(){
        dismissOnboarding()
        compose.runOnUiThread{vm.selectOrigin(fixture.places[0].toAppPlace());vm.selectDestination(fixture.places[1].toAppPlace());vm.onLocationFix(LocationFix(fixture.a,fixture.time,4.0))}
        compose.waitUntil(5_000){vm.backendState.value.session.status==SessionStatus.Preview&&vm.uiState.value.currentLocationUsable}
        compose.runOnUiThread{vm.startNavigation()}
        compose.waitUntil(5_000){vm.backendState.value.session.status==SessionStatus.Navigating}
        val trip=vm.backendState.value.session.sessionId
        compose.onNodeWithContentDescription("Stop navigation").assertExists()
        compose.runOnUiThread{fixture.time+=9000;vm.checkSignal()}
        compose.waitUntil(5_000){vm.backendState.value.session.status==SessionStatus.SignalLimited}
        compose.onNodeWithText("Location fix is stale",substring=true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Steps").performClick()
        assertTrue(compose.onAllNodesWithText("Steps").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(trip,vm.backendState.value.session.sessionId)
        compose.onNodeWithContentDescription("Stop navigation").performClick()
        compose.onNodeWithText("Stop",substring=false).performClick()
        compose.waitUntil(5_000){vm.backendState.value.session.status==SessionStatus.Stopped}
        compose.onNodeWithText("Search here").assertIsDisplayed()
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
