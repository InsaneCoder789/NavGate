package com.rohanc.navgate.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rohanc.navgate.core.*
import com.rohanc.navgate.data.*
import com.rohanc.navgate.data.local.*
import com.rohanc.navgate.data.routing.*
import com.rohanc.navgate.ui.state.*
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoadRouteUiTest {
    // The Start button is disabled without location permission; a fresh phone has not granted it.
    @get:Rule val compose=createComposeRule()
    @org.junit.Before fun grantLocation(){InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
        ApplicationProvider.getApplicationContext<Context>().packageName,android.Manifest.permission.ACCESS_FINE_LOCATION)}
    @Test fun nativeEndpointButtonsPreviewRealWalkingThenCarAndKeepCarWhenStarting() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveRoadRoutes")=="true")
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,NavGateDatabase::class.java).build()
        val stores=ViewModelStore()
        lateinit var vm:NavGateViewModel
        val start=CampusPlace("road-start","Gateway area road",city="Mumbai",point=GeoPoint(18.92297,72.83329))
        val end=CampusPlace("marine","Marine Drive",city="Mumbai",point=GeoPoint(18.9341,72.82365))
        val data=object:BackendData {
            override suspend fun initialize()=Unit
            override suspend fun search(query:String,regionId:String)=listOf(start,end).filter{query.isBlank() || it.title.contains(query,true)}
            override suspend fun place(regionId:String,id:String)=listOf(start,end).find{it.id==id}
        }
        try {
            compose.runOnUiThread {
                val regions=RoomRegionStore(db)
                val routing=HybridRoutingProvider(regions,ValhallaRoutingProvider(cache=RoomRoadRouteCache(db)))
                vm=NavGateViewModel({scope->NavigationBackend(scope,data,regions,AndroidBackendClock,RoomSessionStore(db,regions),routingProvider=routing)},InMemoryUserPlacesStore(),InMemoryAppPreferencesStore(onboardingSeen=true))
                stores.put("road-vm",vm)
            }
            compose.setContent{NavGateFrontend(vm,renderMap=false,trackLocation=false)}
            compose.waitUntil(10_000){vm.uiState.value.preferencesLoaded && !vm.backendState.value.initializing}
            fun click(text:String)=compose.onNodeWithText(text,substring=false).performClick()
            compose.onNodeWithContentDescription("Directions").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("Marine")
            compose.waitUntil(5000){vm.uiState.value.places.any{it.id=="marine"}}
            click("Marine Drive")
            click("Choose start")
            compose.onNode(hasSetTextAction()).performTextInput("Gateway")
            compose.waitUntil(5000){vm.uiState.value.places.any{it.id=="road-start"}}
            click("Gateway area road")
            compose.waitUntil(35_000){vm.backendState.value.session.route!=null || vm.backendState.value.routeFailure!=null}
            assertNull(vm.backendState.value.routeFailure)
            assertEquals("walking",vm.backendState.value.session.route?.profile)
            compose.onNodeWithText("Walk",substring=false).assertExists()
            compose.onNodeWithContentDescription("Car").performClick()
            compose.waitUntil(35_000){!vm.backendState.value.planning && vm.backendState.value.session.route?.profile=="driving" && vm.uiState.value.snapshot.route?.travelMode=="driving"}
            assertEquals("driving",vm.uiState.value.snapshot.route?.travelMode)
            compose.onNodeWithText("Drive",substring=false).assertExists()
            val route=vm.backendState.value.session.route!!
            compose.runOnUiThread{vm.onLocationFix(LocationFix(route.geometry.first(),AndroidBackendClock.elapsedMillis(),4.0))}
            compose.waitUntil(5000){vm.uiState.value.currentLocationUsable}
            click("Start driving")
            try{compose.waitUntil(5000){vm.backendState.value.session.status==SessionStatus.Navigating}}
            catch(e:Throwable){val s=vm.backendState.value.session;throw AssertionError("Start did not navigate: status=${s.status} reason=${s.signalReason} failure=${vm.backendState.value.routeFailure}",e)}
            assertEquals("driving",vm.backendState.value.session.route?.profile)
            assertTrue(vm.backendState.value.session.remainingSeconds<1000)
            compose.onNodeWithContentDescription("Stop navigation").performClick()
            compose.onNodeWithText("Stop",substring=false).performClick()
            compose.waitUntil(5000){vm.backendState.value.session.status==SessionStatus.Stopped}
        }finally{compose.runOnUiThread{stores.clear()};db.close()}
    }
}
