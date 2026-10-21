package com.rohanc.navgate.ui

import android.content.Context
import android.graphics.Bitmap
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
import java.io.File
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in (screenshots=true): renders each journey screen with the live map and real routes, and saves PNGs for visual review. */
@RunWith(AndroidJUnit4::class)
class JourneyScreenshotTest {
    @get:Rule val compose=createComposeRule()
    @org.junit.Before fun grantLocation(){InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
        ApplicationProvider.getApplicationContext<Context>().packageName,android.Manifest.permission.ACCESS_FINE_LOCATION)}

    private fun shot(name:String){
        // Compose only applies pending changes while the test waits for idle, so settle first, then give map tiles time.
        compose.waitForIdle();Thread.sleep(6_000);compose.waitForIdle()
        // The shell user writes to shared storage, so the PNGs outlive the test app's uninstall.
        val out=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/Download/navgate-$name.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(out).use{it.readBytes()}
    }

    @Test fun captureEveryJourneyScreen(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("screenshots")=="true")
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,NavGateDatabase::class.java).build()
        val stores=ViewModelStore()
        lateinit var vm:NavGateViewModel
        val start=CampusPlace("start","Colaba Causeway",city="Mumbai",point=GeoPoint(18.9151,72.8230),subtitle="Colaba, Mumbai, Maharashtra, India",category="road")
        val end=CampusPlace("marine","Marine Drive",city="Mumbai",point=GeoPoint(18.9440,72.8236),subtitle="C Ward, Mumbai, Maharashtra, India",category="road")
        val data=object:BackendData {
            override suspend fun initialize()=Unit
            override suspend fun search(query:String,regionId:String)=listOf(start,end).filter{query.isBlank()||it.title.contains(query,true)}
            override suspend fun place(regionId:String,id:String)=listOf(start,end).find{it.id==id}
        }
        try {
            compose.runOnUiThread{
                val regions=RoomRegionStore(db)
                val road=ValhallaRoutingProvider(cache=RoomRoadRouteCache(db))
                val routing=HybridRoutingProvider(regions,road)
                vm=NavGateViewModel({scope->NavigationBackend(scope,data,regions,AndroidBackendClock,RoomSessionStore(db,regions),routingProvider=routing)},InMemoryUserPlacesStore(),
                    InMemoryAppPreferencesStore(onboardingSeen=true,storedCityMode=CityMode.KiitBeta),estimator=road)
                stores.put("shots",vm)
            }
            compose.setContent{
                // Waiting for live map tiles takes longer than a screen timeout.
                val activity=androidx.compose.ui.platform.LocalContext.current as android.app.Activity
                androidx.compose.runtime.SideEffect{activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
                NavGateFrontend(vm,renderMap=true,trackLocation=false,showMotion=true)
            }
            compose.waitUntil(10_000){vm.uiState.value.preferencesLoaded&&!vm.backendState.value.initializing}
            compose.runOnUiThread{vm.onLocationFix(LocationFix(start.point!!,AndroidBackendClock.elapsedMillis(),6.0))}
            shot("1-explore")
            compose.onNodeWithText("Search here").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("Marine")
            android.util.Log.i("SHOT","query after typing=["+vm.uiState.value.searchQuery+"] screen-places="+vm.uiState.value.places.size)
            Thread.sleep(2_000)
            android.util.Log.i("SHOT","query 2s later=["+vm.uiState.value.searchQuery+"]")
            compose.waitUntil(8_000){vm.uiState.value.places.any{it.id=="marine"}}
            shot("2-search")
            compose.onAllNodes(hasText("Marine Drive",substring=true) and !hasSetTextAction())[0].performClick()
            compose.waitUntil(8_000){vm.uiState.value.focusedPlace?.id=="marine"}
            shot("3-place")
            compose.onNodeWithText("Directions").performClick()
            compose.waitUntil(40_000){vm.backendState.value.session.route!=null||vm.backendState.value.routeFailure!=null}
            assertNull(vm.backendState.value.routeFailure)
            shot("4-directions")
            compose.onNodeWithContentDescription("Car").performClick()
            compose.waitUntil(40_000){vm.backendState.value.session.route?.profile=="driving"&&!vm.backendState.value.planning}
            compose.runOnUiThread{vm.onLocationFix(LocationFix(vm.backendState.value.session.route!!.geometry.first(),AndroidBackendClock.elapsedMillis(),4.0))}
            compose.waitUntil(5_000){vm.uiState.value.currentLocationUsable}
            shot("5-directions-drive")
            compose.onNodeWithText("Steps").performClick()
            shot("6-steps")
            // A real phone streams fixes; the guidance rules reject a position older than a few seconds.
            compose.runOnUiThread{vm.onLocationFix(LocationFix(vm.backendState.value.session.route!!.geometry.first(),AndroidBackendClock.elapsedMillis(),4.0))}
            compose.onNodeWithText("Start driving").performClick()
            compose.waitUntil(30_000){vm.backendState.value.session.status==SessionStatus.Navigating}
            // Feed a plausible fused estimate (heading along the road, mild acceleration) so the car marker and sensor panel render.
            val route=vm.backendState.value.session.route!!
            val at=route.geometry[minOf(3,route.geometry.lastIndex)]
            compose.runOnUiThread{vm.onMotion(MotionEstimate(at,13.9,route.geometry[0].bearingTo(route.geometry[1]),0.9,-0.6,5.0,CalibrationState.Good,false,false,AndroidBackendClock.elapsedMillis()))}
            shot("7-navigation")
            compose.onNodeWithContentDescription("Stop navigation").performClick()
            compose.onNodeWithText("Stop",substring=false).performClick()
            compose.waitUntil(8_000){vm.backendState.value.session.status==SessionStatus.Stopped}
            compose.onNodeWithText("Saved").performClick()
            shot("8-saved")
            compose.onNodeWithText("Recents").performClick()
            shot("9-recents")
            compose.onNodeWithText("Explore").performClick()
            compose.onNodeWithContentDescription("Settings").performClick()
            shot("10-settings")
        }finally{compose.runOnUiThread{stores.clear()};db.close()}
    }
}
