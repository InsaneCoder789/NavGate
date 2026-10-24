package com.rohanc.navgate.ui

import android.Manifest
import android.app.Activity
import androidx.core.view.WindowCompat
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import android.os.SystemClock
import com.rohanc.navgate.ui.map.VehicleMarker
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import com.rohanc.navgate.data.local.NavGateServices
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rohanc.navgate.core.*
import com.rohanc.navgate.model.PlaceSearchResult
import com.rohanc.navgate.model.Coordinate
import com.rohanc.navgate.model.TravelProfile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import androidx.compose.ui.platform.LocalConfiguration
import io.github.dellisd.spatialk.geojson.BoundingBox
import com.rohanc.navgate.navigation.PresentationMode
import com.rohanc.navgate.ui.ar.*
import com.rohanc.navgate.ui.components.*
import com.rohanc.navgate.ui.location.*
import com.rohanc.navgate.ui.map.MapRouteGeoJson
import com.rohanc.navgate.ui.map.rememberMapConnection
import com.rohanc.navgate.ui.state.*
import com.rohanc.navgate.ui.theme.*
import org.maplibre.compose.camera.CameraMoveReason
import kotlin.time.Duration.Companion.milliseconds
import kotlin.math.*
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.span
import com.rohanc.navgate.ui.map.ManualRouteGeoJson
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.util.ClickResult
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import io.github.dellisd.spatialk.geojson.Position
import com.rohanc.navgate.ui.journey.JourneyActions
import com.rohanc.navgate.ui.journey.NgDialog
import com.rohanc.navgate.ui.journey.JourneyScreens
import com.rohanc.navgate.ui.journey.JourneyUi

import com.rohanc.navgate.ui.journey.GlassPage
import com.rohanc.navgate.ui.journey.LiveCameraLayer
import com.rohanc.navgate.model.asMetersLabel
import com.rohanc.navgate.ui.journey.LayersSheet
import com.rohanc.navgate.ui.journey.JourneyData
import com.rohanc.navgate.ui.journey.LocalHazeState
import com.rohanc.navgate.ui.journey.SettingsModel
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import com.rohanc.navgate.ui.ar.alignmentStatus

/** The whole app: the map, and one set of glass screens drawn over it. */
@Composable
fun NavGateFrontend(injectedViewModel:NavGateViewModel?=null,renderMap:Boolean=true,trackLocation:Boolean=true,showMotion:Boolean=trackLocation) {
    val context=LocalContext.current
    val vm:NavGateViewModel=injectedViewModel ?: viewModel(factory=NavGateViewModel.factory(context.applicationContext as Application))
    val services=remember(context){NavGateServices.get(context)}
    val scope=rememberCoroutineScope()
    val keyboard=LocalSoftwareKeyboardController.current
    var feedback by remember { mutableStateOf<String?>(null) }
    fun appSettings(){context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))}
    fun share(place:PlaceSearchResult){
        val coordinate=place.coordinate
        val text=place.title+(coordinate?.let{"\nhttps://www.openstreetmap.org/?mlat=${it.latitude}&mlon=${it.longitude}#map=18/${it.latitude}/${it.longitude}"} ?: "\n"+place.subtitle)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,text)},"Share place"))
    }
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val backend by vm.backendState.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf("Explore") }
    val manualFormState=rememberSaveableStateHolder()
    var manualPinsJson by rememberSaveable { mutableStateOf("[]") }
    val manualPins=remember(manualPinsJson){Json.decodeFromString<List<RoutePin>>(manualPinsJson)}
    var manualPointJson by rememberSaveable { mutableStateOf<String?>(null) }
    val manualPoint=remember(manualPointJson){manualPointJson?.let{Json.decodeFromString<GeoPoint>(it)}}
    var inspectManualPoint by remember { mutableStateOf<Coordinate?>(null) }
    var directionCaller by rememberSaveable { mutableStateOf("Explore") }
    var directionPlaceId by rememberSaveable { mutableStateOf<String?>(null) }
    var directionQuery by rememberSaveable { mutableStateOf("") }
    var placeCaller by rememberSaveable { mutableStateOf("Explore") }
    var settingsCaller by rememberSaveable { mutableStateOf("Explore") }
    var utilityCaller by rememberSaveable { mutableStateOf("Explore") }
    var utilityStack by rememberSaveable { mutableStateOf(listOf<String>()) }
    var searchFor by rememberSaveable { mutableStateOf("place") }
    var stopDialog by remember { mutableStateOf(false) }
    var clearDialog by remember { mutableStateOf(false) }
    var clearHistoryDialog by remember { mutableStateOf(false) }
    var recentView by rememberSaveable { mutableStateOf("places") }
    var savedSort by rememberSaveable { mutableStateOf(false) }
    var mapList by rememberSaveable { mutableStateOf(true) }
    var expandedDetails by rememberSaveable { mutableStateOf(false) }
    var selectedManeuver by rememberSaveable { mutableStateOf<Int?>(null) }
    var permission by remember { mutableStateOf(hasLocationPermission(context)) }
    var cameraPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) }
    val notificationLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}
    val locationLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){permission=it.values.any{granted->granted};if(permission&&vm.uiState.value.showOnboarding)vm.dismissOnboarding()
        // The navigation notification needs this on Android 13+. Asked once, here, never while a trip is starting.
        if(permission&&Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)}
    val cameraLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){cameraPermission=it}
    val lifecycleOwner=LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner){
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME){permission=hasLocationPermission(context);cameraPermission=ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED}}
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose{lifecycleOwner.lifecycle.removeObserver(observer)}
    }
    val active=backend.session.status in NavigationSession.ACTIVE
    LaunchedEffect(screen,active){com.rohanc.navgate.app.CrashReporter.note("$screen${if(active)"+trip" else ""}")}
    CrashReportPrompt(autoSend=ui.frontendPreferences.sendCrashReports)
    val driving=(backend.session.route?.profile ?: ui.travelProfile.name.lowercase())=="driving"
    val prefs=ui.frontendPreferences
    val dark=when(prefs.appearance){"light"->false;"system"->isSystemInDarkTheme();else->true}
    val fusion=remember{MotionFusion()}
    if(trackLocation)LocationPipeline(vm,fusion,permission,active,screen){ui.displayLocation?.let{GeoPoint(it.latitude,it.longitude)}}
    val voiceAvailability=GuidanceRuntime(vm.backendState, vm.uiState, permission, vm::checkSignal)
    LaunchedEffect(backend.session.route?.id){selectedManeuver=null}
    LaunchedEffect(backend.initializing){if(!backend.initializing)vm.refreshRegionStatus()}
    LaunchedEffect(backend.session.status,backend.initializing){if(!backend.initializing)screen=sessionDestination(backend.session.status,screen)}
    fun search(intent:String){searchFor=intent;vm.updateSearch("");screen="Search"}
    fun directions(place:PlaceSearchResult,asOrigin:Boolean=false){
        directionCaller=screen;directionPlaceId=place.id;directionQuery=ui.searchQuery
        if(asOrigin)vm.startHere(place)else vm.selectDestination(place)
        screen="Directions"
    }
    fun choose(place:PlaceSearchResult){
        if(active){feedback="Stop the current trip before changing its destination.";return}
        when(searchFor){
            "from"->{if(place.coordinate!=null){vm.selectOrigin(place);screen="Directions"}else feedback="This place has no reviewed starting coordinate. Choose another start."}
            "to"->{vm.selectDestination(place);screen="Directions"}
            else->{placeCaller=screen;vm.focusPlace(place);screen="Place"}
        }
    }
    fun openUtility(page:String){
        if(screen!=page){utilityStack=(utilityStack+screen).takeLast(20);if(page=="Settings")settingsCaller=screen else utilityCaller=screen;screen=page}
        if(page=="Offline")vm.refreshRegionStatus()
    }
    fun currentOrigin(){
        if(!permission)locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))
        else if(vm.useCurrentOrigin())screen="Directions"
    }
    fun back(){
        keyboard?.hide()
        if(screen=="Navigation"){stopDialog=true;return}
        if(screen in listOf("Settings","Offline")&&utilityStack.isNotEmpty()){screen=utilityStack.last();utilityStack=utilityStack.dropLast(1);return}
        if(screen=="Arrival"){vm.finishArrival();screen="Explore";return}
        if(screen=="Directions"){
            if(directionCaller=="Place"){
                val prior=(listOfNotNull(ui.selectedOrigin,ui.selectedDestination)+ui.savedPlaces+ui.recentPlaces).find{it.id==directionPlaceId}
                if(prior!=null)vm.focusPlace(prior)else directionCaller="Explore"
            }
            if(directionCaller in listOf("Search","Results"))vm.updateSearch(directionQuery)
        }
        if(screen=="ManualPin"){screen="My routes";return}
        if(screen=="Camera"||screen=="Live")vm.switchMode(PresentationMode.MAP)
        screen=frontendBack(screen,active,placeCaller,settingsCaller,utilityCaller,searchFor,directionCaller)
    }
    DisposableEffect(dark) {
        val window=(context as? Activity)?.window
        val bars=window?.let{WindowCompat.getInsetsController(it,it.decorView)}
        val oldStatus=bars?.isAppearanceLightStatusBars
        val oldNavigation=bars?.isAppearanceLightNavigationBars
        bars?.isAppearanceLightStatusBars=!dark
        bars?.isAppearanceLightNavigationBars=!dark
        onDispose {
            if(oldStatus!=null)bars?.isAppearanceLightStatusBars=oldStatus
            if(oldNavigation!=null)bars?.isAppearanceLightNavigationBars=oldNavigation
        }
    }
    BackHandler(enabled=screen!="Explore"){back()}
    val hazeState=rememberHazeState()
    // The compass loop polls forever, so it only runs while the camera screen needs it.
    val headingState = if (screen=="Camera"||screen=="Live") rememberHeadingState(backend.session.location) else remember { mutableStateOf<Double?>(null) }
    val heading by headingState
    val alignment=alignmentStatus(ui.snapshot,heading)
    // First time into AR or Live view: show how it works, then carry on to where the driver was going.
    var introMode by remember{mutableStateOf<String?>(null)}
    var introGo by remember{mutableStateOf<(()->Unit)?>(null)}
    fun gated(mode:String,go:()->Unit){val seen=if(mode=="VR")prefs.vrIntroSeen else prefs.arIntroSeen;if(seen)go() else{introMode=mode;introGo=go}}
    val actions=JourneyActions(
        back={back()},openSearch={type->search(type)},query=vm::updateSearch,submitSearch={keyboard?.hide();screen="Results"},choose={choose(it)},
        directions={directions(it)},share={share(it)},toggleSaved={vm.toggleSaved(it)},
        openSettings={openUtility("Settings")},openTab={name->utilityStack=emptyList();screen=name},openMyRoutes={screen="My routes"},
        clearRecents={clearDialog=true},useCurrentLocation={currentOrigin()},chooseOnMap={type->searchFor=type;vm.updateSearch("");screen="MapPick"},
        swap=vm::swapEndpoints,clearPlan={vm.clearPlan();selectedManeuver=null;screen="Explore"},setProfile={vm.setTravelProfile(it)},replan=vm::replan,start=vm::startNavigation,
        requestStop={stopDialog=true},requestLocation={locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))},
        openAppSettings={appSettings()},showSteps={screen="Steps"},openCamera={gated("AR"){screen="Camera";vm.switchMode(PresentationMode.AR_ASSIST)}},
        chooseRoute={vm.chooseRoute(it)},
        exportArDiagnostics={
            val version=runCatching{context.packageManager.getPackageInfo(context.packageName,0).let{"${it.versionName} (${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it)})"}}.getOrDefault("?")
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT,"NavGate AR diagnostics").putExtra(Intent.EXTRA_TEXT,com.rohanc.navgate.ui.ar.ArDiagnostics.export(version)),"Share AR diagnostics"))
        },
        practiceAr={gated("AR"){
            val here=ui.displayLocation
            if(here==null||!permission)feedback="Turn on location to try AR here."
            else if(!cameraPermission){cameraLauncher.launch(Manifest.permission.CAMERA)}
            else scope.launch{
                val start=GeoPoint(here.latitude,here.longitude)
                val heading=com.rohanc.navgate.ui.ar.readPhoneHeading(context,start) ?: 0.0
                vm.previewLocalRoute(com.rohanc.navgate.ui.ar.PracticeRoute.build(start,heading,System.currentTimeMillis()))
                vm.updateSearch("");screen="Camera";vm.switchMode(PresentationMode.AR_ASSIST)
            }
        }},
        saveParking={
            val here=ui.displayLocation
            if(here==null)feedback="Waiting for your location to save the parking spot."
            else{vm.toggleSaved(PlaceSearchResult("parked:${System.currentTimeMillis()}","Parked car","Saved on arrival",here.latitude,here.longitude,com.rohanc.navgate.model.PlaceType.Landmark));feedback="Parking spot saved. Find it under Saved."}
        },
        setViewMode={mode->
            if(mode=="VR"){
                if(backend.session.route?.profile!="walking")feedback="Live view is for walking routes. Choose Walk to use it."
                else if(!cameraPermission)cameraLauncher.launch(Manifest.permission.CAMERA) else gated("VR"){screen="Live";vm.switchMode(PresentationMode.AR_ASSIST)}
            }
            else if(mode=="AR"){
                val blocked=com.rohanc.navgate.ui.ar.arBlockedReason(backend.session.route,ui.displayLocation?.let{GeoPoint(it.latitude,it.longitude)},ui.displayAccuracy?.toDouble())
                if(blocked!=null)feedback=blocked else gated("AR"){screen="Camera";vm.switchMode(PresentationMode.AR_ASSIST)}
            }
            else{
                if(screen=="Camera"||screen=="Live"){vm.switchMode(PresentationMode.MAP)
                    if(backend.session.route?.destinationPlaceId==com.rohanc.navgate.ui.ar.PracticeRoute.PLACE_ID){vm.clearPlan();screen="Settings"}else screen=if(active)"Navigation" else "Directions"}
                vm.updateFrontendPreferences(prefs.copy(buildings3d=mode=="3D"))
            }
        },
        toggleVoice={vm.updateFrontendPreferences(prefs.copy(voiceEnabled=!prefs.voiceEnabled))},finishArrival={vm.finishArrival();screen="Explore"},openOfflineData={openUtility("Offline")},
        openPlace={place->placeCaller=screen;vm.focusPlace(place);screen="Place"},planAgain={title->searchFor="to";vm.updateSearch(title);screen="Search"},
        clearHistory={clearHistoryDialog=true},setPrefs={vm.updateFrontendPreferences(it)},setCity={vm.setCityMode(it)},reviewSetup={screen="Setup"},
        closeCamera={vm.switchMode(PresentationMode.MAP)
            if(backend.session.route?.destinationPlaceId==com.rohanc.navgate.ui.ar.PracticeRoute.PLACE_ID){vm.clearPlan();screen="Settings"}else screen=if(active)"Navigation" else "Route"},requestCamera={cameraLauncher.launch(Manifest.permission.CAMERA)},
        dismissOnboarding={vm.dismissOnboarding();screen="Explore"},
        cancelPick={back()},
        openCategory={category->searchFor="place";vm.updateSearch(category.query);screen="Results"},closeResults={vm.updateSearch("");screen="Explore"},
        call={phone->runCatching{context.startActivity(Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+phone.filter{it.isDigit()||it=='+'})))}},
        openWebsite={site->runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(if(site.startsWith("http://")||site.startsWith("https://"))site else "https://$site")))}})
    val locationServicesOn=com.rohanc.navgate.ui.location.rememberLocationServicesOn()
    val trip=rememberTripRuntime(vm,backend.session,active,screen)
    val online=trip.online
    val tripSummary=trip.tripSummary
    // The planned route as a measured line, and how far along it the vehicle is: what the car marker glides on.
    val navRoute=backend.session.route
    val navGeometry=remember(navRoute?.id){navRoute?.let{com.rohanc.navgate.core.RouteGeometry(it.geometry)}}
    val navTrack=if(active&&navRoute!=null)com.rohanc.navgate.ui.map.TrackInput(navRoute.routeToShapeMeters(backend.session.alongMeters),backend.session.speedAlongMps,backend.session.fixElapsedMillis,backend.session.lockedToRoute&&backend.session.snappedLocation!=null)else null
    val data=JourneyData(screen,searchFor,ui,backend.session,ui.snapshot.route?.steps.orEmpty(),backend.session.route?.takeIf{it.external}?.warnings.orEmpty(),permission,prefs.voiceEnabled,ui.areaName,
        if(searchFor=="saved")vm.searchSaved(ui.searchQuery)else ui.places,active,if(showMotion)vm.motionState else null,
        SettingsModel(prefs,ui.cityMode,active,voiceAvailability,permission),if(screen=="Camera")"AR" else if(screen=="Live")"VR" else if(prefs.buildings3d)"3D" else "2D",cameraPermission,alignment,
        permission&&ui.currentLocationUsable,online=online,tripSummary=tripSummary,locationOn=locationServicesOn)
    var showLayers by remember{mutableStateOf(false)}
    var insetTop by remember{mutableFloatStateOf(0f)}
    var insetBottom by remember{mutableFloatStateOf(0f)}
    val journeyMap=screen in setOf("Explore","Place","Directions","Route","Navigation","Arrival","MapPick","ManualPin","My routes","Results")
    NavGateTheme(dark=dark){CompositionLocalProvider(LocalSolidGlass provides prefs.reduceTransparency,com.rohanc.navgate.ui.journey.LocalGlassOpacity provides prefs.glassOpacity,LocalHazeState provides hazeState){
        Surface(Modifier.fillMaxSize()){
            Box(Modifier.fillMaxSize()){
                Box(Modifier.fillMaxSize().hazeSource(hazeState)){
                    val mapUi=if(screen in listOf("Search","Results")&&searchFor=="saved")ui.copy(places=vm.searchSaved(ui.searchQuery))else ui
                    if(renderMap) NativeMap(mapUi,dark,permission,active,backend.session.sessionId,backend.session.accuracyMeters,inspectManualPoint.takeIf{screen in listOf("My routes","ManualPin")} ?: backend.session.route?.maneuvers?.getOrNull(selectedManeuver ?: -1)?.target?.let{Coordinate(it.latitude,it.longitude)},onFeedback={feedback=it},bottomInsetDp=if(journeyMap)insetBottom else LocalConfiguration.current.screenHeightDp*0.42f,topInsetDp=if(journeyMap)insetTop else 80f,controlsBottomDp=if(journeyMap)insetBottom+12f else null,onRequestLocation={locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))},screenIsExplore=screen=="Explore",showPins=screen=="Results",onOpenLayers={showLayers=true},onToggle3d={vm.updateFrontendPreferences(prefs.copy(buildings3d=!prefs.buildings3d))},nextTurn=backend.session.route?.maneuvers?.getOrNull(backend.session.maneuverIndex)?.target?.let{Coordinate(it.latitude,it.longitude)},motion=if(showMotion)vm.motionState else null,alongMeters=backend.session.alongMeters,manualPins=if(screen in listOf("My routes","ManualPin"))manualPins else emptyList(),showControls=screen=="Navigation",live=screen=="Live",routeGeometry=navGeometry,trackInput=navTrack,onPin=when(screen){
                        "ManualPin"->({point->manualPointJson=Json.encodeToString(point);inspectManualPoint=Coordinate(point.latitude,point.longitude);screen="My routes"})
                        "MapPick"->({point->choose(PlaceSearchResult("pin:${java.util.UUID.randomUUID()}","Dropped pin","Selected on map",point.latitude,point.longitude,com.rohanc.navgate.model.PlaceType.Landmark))})
                        else->null
                    }){place->
                        if(screen in listOf("Search","Results","MapPick"))choose(place)
                        else if(!active){placeCaller=screen;vm.focusPlace(place);screen="Place"}
                    }
                    val arRoute=backend.session.route
                    if(screen=="Camera" && cameraPermission && arRoute!=null){
                        com.rohanc.navgate.ui.ar.ArNavigationLayer(arRoute,navGeometry,navTrack ?: com.rohanc.navgate.ui.map.TrackInput(arRoute.routeToShapeMeters(backend.session.alongMeters),backend.session.speedAlongMps,backend.session.fixElapsedMillis,true),
                            backend.session.snappedLocation,ui.displayLocation?.let{GeoPoint(it.latitude,it.longitude)},backend.session.distanceToManeuver,(arRoute.profile)=="driving",
                            when(prefs.arFps){"saver"->com.rohanc.navgate.ui.ar.ArFpsMode.Saver;"smooth"->com.rohanc.navgate.ui.ar.ArFpsMode.Smooth;"max"->com.rohanc.navgate.ui.ar.ArFpsMode.Max;else->com.rohanc.navgate.ui.ar.ArFpsMode.Auto},Modifier.fillMaxSize(),prefs.arObjects,backend.session.route?.maneuvers?.getOrNull(backend.session.maneuverIndex)?.kind)
                    }
                }
                if(screen=="Live"&&cameraPermission){
                    val liveRoute=backend.session.route; val liveIndex=backend.session.maneuverIndex
                    val liveTarget=liveRoute?.maneuvers?.getOrNull(liveIndex)?.target
                    val liveHere=ui.displayLocation?.let{GeoPoint(it.latitude,it.longitude)}
                    // Where the turn lies across the picture: its bearing from here against where the phone points, over the lens's field of view.
                    val across=if(liveTarget!=null&&liveHere!=null&&heading!=null)(0.5f+(((liveHere.bearingTo(liveTarget)-heading!!+540.0)%360.0)-180.0).toFloat()/65f).coerceIn(0.14f,0.86f) else 0.5f
                    val instruction=backend.session.instruction
                    val road=Regex("(?:to stay on|onto|on)\\s+(.+?)\\.?$").find(instruction)?.groupValues?.get(1) ?: instruction
                    val bubble=if(liveRoute!=null)com.rohanc.navgate.ui.journey.LiveBubble(road,backend.session.distanceToManeuver.asMetersLabel(),liveRoute.maneuvers.getOrNull(liveIndex)?.kind,across) else null
                    val secs=backend.session.remainingSeconds
                    val arSlot:(@Composable (Modifier,Float)->Unit)?=if(liveRoute!=null){{mod:Modifier,dome:Float->
                        com.rohanc.navgate.ui.ar.ArNavigationLayer(liveRoute,navGeometry,navTrack ?: com.rohanc.navgate.ui.map.TrackInput(liveRoute.routeToShapeMeters(backend.session.alongMeters),backend.session.speedAlongMps,backend.session.fixElapsedMillis,true),backend.session.snappedLocation,liveHere,backend.session.distanceToManeuver,false,com.rohanc.navgate.ui.ar.ArFpsMode.Auto,mod,prefs.arObjects,liveRoute.maneuvers.getOrNull(liveIndex)?.kind,dome)}}else null
                    Box(Modifier.fillMaxSize()){LiveCameraLayer(bubble,
                        secs.takeIf{it>0}?.let{java.time.LocalTime.now().plusSeconds(it.toLong()).format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))},
                        if(secs>=3600)"%d h %d min".format((secs/3600).toInt(),((secs%3600)/60).toInt()) else "${(secs/60).toInt().coerceAtLeast(1)} min",
                        backend.session.remainingMeters.asMetersLabel(),
                        arSlot)}
                }
                // Map details replaces the screen underneath instead of stacking on it.
                if(!showLayers)JourneyUi(data,{top,bottom->insetTop=top;insetBottom=bottom},actions){
                    if(screen=="My routes")GlassPage("My routes",actions.back){
                        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal=16.dp).padding(bottom=24.dp)){
                            manualFormState.SaveableStateProvider("manual-editor"){ManualRoutePanel(services.manualRoutes,manualPins,{manualPinsJson=Json.encodeToString(it)},manualPoint,
                                onPick={screen="ManualPin"},onClearPoint={manualPointJson=null},onInspect={inspectManualPoint=Coordinate(it.latitude,it.longitude)},onClearInspection={inspectManualPoint=null},onRoadRoute={itinerary->vm.planItinerary(itinerary){ok->if(ok)screen="Directions"}})}
                        }
                    }
                }
                introMode?.let{m->com.rohanc.navgate.ui.journey.ArIntroOverlay(m,onStart={
                    vm.updateFrontendPreferences(if(m=="VR")prefs.copy(vrIntroSeen=true) else prefs.copy(arIntroSeen=true))
                    val go=introGo;introMode=null;introGo=null;go?.invoke()
                },onClose={introMode=null;introGo=null})}
                if(showLayers)LayersSheet(prefs,{vm.updateFrontendPreferences(it)},{showLayers=false})
            }
        }
        if(stopDialog)NgDialog("Stop this trip?","You can plan another route after stopping.","Stop",{vm.stopNavigation();stopDialog=false},{stopDialog=false},"Keep navigating",confirmEnabled=!ui.stopPending,icon=Icons.Rounded.StopCircle)
        feedback?.let{message->NgDialog("NavGate",message,"OK",{feedback=null},{feedback=null})}
        if(clearHistoryDialog)NgDialog("Clear trip history?","Saved places, recent places and an active trip are retained.","Clear",{vm.clearRouteHistory();clearHistoryDialog=false},{clearHistoryDialog=false},"Cancel")
        if(clearDialog)NgDialog("Clear recent places?","Your saved places will remain available.","Clear",{vm.clearRecents();clearDialog=false},{clearDialog=false},"Cancel")
    }}
}
