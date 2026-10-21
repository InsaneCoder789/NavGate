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
import com.rohanc.navgate.data.local.DestinationDetails
import com.rohanc.navgate.data.local.destinationDetails
import android.content.pm.PackageManager
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
import com.rohanc.navgate.ui.journey.JourneyScreens
import com.rohanc.navgate.ui.journey.JourneyUi

import com.rohanc.navgate.ui.journey.GlassPage
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
    var packUri by rememberSaveable { mutableStateOf<String?>(null) }
    var importRegion by rememberSaveable { mutableStateOf<String?>(null) }
    var importJob by remember { mutableStateOf<Job?>(null) }
    val importState by services.regionDocuments.state.collectAsStateWithLifecycle()
    val signatureLauncher=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){signature->
        val pack=packUri;val region=importRegion
        if(signature==null){feedback="Signature selection cancelled. Installed data is unchanged.";packUri=null}
        else if(pack!=null&&region!=null){if(vm.backendState.value.session.status in NavigationSession.ACTIVE){feedback="Stop the current trip before installing region files.";packUri=null;importRegion=null}else importJob=scope.launch{
            try { when(val result=services.regionDocuments.import(region,Uri.parse(pack),signature)){
                is InstallResult.Installed->{feedback="Installed region ${result.regionId}, version ${result.version}";vm.refreshRegionStatus();vm.updateSearch(vm.uiState.value.searchQuery)}
                is InstallResult.Rejected->feedback=result.reasons.joinToString("\n")
            }} catch(cancelled:CancellationException){feedback="Import cancelled. Refresh region status to check the installed version.";vm.refreshRegionStatus();throw cancelled}
            finally {packUri=null;importRegion=null;importJob=null}
        }}
    }
    val packLauncher=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri==null){feedback="Pack selection cancelled. Installed data is unchanged.";importRegion=null}
        else {packUri=uri.toString();signatureLauncher.launch(arrayOf("*/*"))}
    }
    fun appSettings(){context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))}
    fun share(place:PlaceSearchResult){
        val coordinate=place.coordinate
        val text=place.title+(coordinate?.let{"\nhttps://www.openstreetmap.org/?mlat=${it.latitude}&mlon=${it.longitude}#map=18/${it.latitude}/${it.longitude}"} ?: "\n"+place.subtitle)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,text)},"Share place"))
    }
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val backend by vm.backendState.collectAsStateWithLifecycle()
    var detailRetry by remember { mutableIntStateOf(0) }
    val detailId=ui.focusedPlace?.id ?: ui.selectedDestination?.id ?: backend.session.route?.destinationPlaceId
    val detailVersion=backend.session.route?.takeIf{ui.focusedPlace==null&&it.destinationPlaceId==detailId}?.graphVersion
    val details by produceState<DestinationDetails?>(null,detailId,ui.cityMode,ui.regionStatus?.version,detailVersion,detailRetry){
        value=null
        if(detailId!=null)try{value=services.destinationDetails(if(detailVersion!=null)backend.session.route?.regionId ?: ui.cityMode.regionId else ui.cityMode.regionId,detailId,detailVersion)}
        catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){feedback="Place access details could not be loaded. Planning will check the installed region again."}
    }
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
    val locationLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){permission=it.values.any{granted->granted};if(permission&&vm.uiState.value.showOnboarding)vm.dismissOnboarding()}
    val cameraLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){cameraPermission=it}
    val lifecycleOwner=LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner){
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME){permission=hasLocationPermission(context);cameraPermission=ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED}}
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose{lifecycleOwner.lifecycle.removeObserver(observer)}
    }
    val active=backend.session.status in NavigationSession.ACTIVE
    val driving=(backend.session.route?.profile ?: ui.travelProfile.name.lowercase())=="driving"
    val prefs=ui.frontendPreferences
    val dark=when(prefs.appearance){"light"->false;"system"->isSystemInDarkTheme();else->true}
    val fusion=remember{MotionFusion()}
    if(trackLocation){
        BindMotionSensors(enabled=permission,fusion=fusion){ui.displayLocation?.let{GeoPoint(it.latitude,it.longitude)}}
        BindLocationTracking(enabled=permission,onLocationSample={fix->
            fusion.onGps(GpsSample(fix.point,fix.accuracyMeters,fix.speedMps,fix.heading,fix.elapsedMillis));vm.onRawFix(fix)
        },onSignalTick=vm::checkSignal)
        // Ten times a second the fused estimate drives the vehicle marker; twice a second it feeds guidance.
        LaunchedEffect(fusion,permission){
            if(!permission)return@LaunchedEffect
            var tick=0
            while(true){
                delay(100)
                val now=SystemClock.elapsedRealtime()
                val estimate=fusion.estimate(now)
                vm.onMotion(estimate)
                if(estimate!=null && ++tick%5==0 && now-estimate.elapsedMillis<1_000 && !(estimate.usingDeadReckoning && estimate.accuracyMeters>45)){
                    vm.onLocationFix(LocationFix(estimate.point,now,estimate.accuracyMeters,estimate.headingDegrees,estimate.speedMps))
                }
            }
        }
    }
    val voiceAvailability=VoiceGuidance(enabled=prefs.voiceEnabled && active && permission,utteranceKey=ui.voiceSequence.toString(),message=ui.voiceAnnouncement)
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
        if(screen=="Camera")vm.switchMode(PresentationMode.MAP)
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
    val headingState = if (screen=="Camera") rememberHeadingState(backend.session.location) else remember { mutableStateOf<Double?>(null) }
    val heading by headingState
    val alignment=alignmentStatus(ui.snapshot,heading)
    val actions=JourneyActions(
        back={back()},openSearch={type->search(type)},query=vm::updateSearch,submitSearch={keyboard?.hide();screen="Results"},choose={choose(it)},
        directions={directions(it)},directionsWithEntrance={place,id->directions(place);vm.selectEntrance(id)},share={share(it)},toggleSaved={vm.toggleSaved(it)},
        openSettings={openUtility("Settings")},openTab={name->utilityStack=emptyList();screen=name},openMyRoutes={screen="My routes"},
        clearRecents={clearDialog=true},useCurrentLocation={currentOrigin()},chooseOnMap={type->searchFor=type;vm.updateSearch("");screen="MapPick"},
        swap=vm::swapEndpoints,clearPlan={vm.clearPlan();selectedManeuver=null;screen="Explore"},setProfile={vm.setTravelProfile(it)},replan=vm::replan,start=vm::startNavigation,
        requestStop={stopDialog=true},requestLocation={locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))},
        openAppSettings={appSettings()},showSteps={screen="Steps"},openCamera={screen="Camera";vm.switchMode(PresentationMode.AR_ASSIST)},
        saveParking={
            val here=ui.displayLocation
            if(here==null)feedback="Waiting for your location to save the parking spot."
            else{vm.toggleSaved(PlaceSearchResult("parked:${System.currentTimeMillis()}","Parked car","Saved on arrival",here.latitude,here.longitude,com.rohanc.navgate.model.PlaceType.Landmark));feedback="Parking spot saved. Find it under Saved."}
        },
        setViewMode={mode->
            if(mode=="AR"){
                val blocked=com.rohanc.navgate.ui.ar.arBlockedReason(backend.session.route,ui.displayLocation?.let{GeoPoint(it.latitude,it.longitude)},ui.displayAccuracy?.toDouble())
                if(blocked!=null)feedback=blocked else{screen="Camera";vm.switchMode(PresentationMode.AR_ASSIST)}
            }
            else{
                if(screen=="Camera"){vm.switchMode(PresentationMode.MAP);screen=if(active)"Navigation" else "Directions"}
                vm.updateFrontendPreferences(prefs.copy(buildings3d=mode=="3D"))
            }
        },
        toggleVoice={vm.updateFrontendPreferences(prefs.copy(voiceEnabled=!prefs.voiceEnabled))},finishArrival={vm.finishArrival();screen="Explore"},openOfflineData={openUtility("Offline")},
        openPlace={place->placeCaller=screen;vm.focusPlace(place);screen="Place"},planAgain={title->searchFor="to";vm.updateSearch(title);screen="Search"},
        clearHistory={clearHistoryDialog=true},setPrefs={vm.updateFrontendPreferences(it)},setCity={vm.setCityMode(it)},reviewSetup={screen="Setup"},
        closeCamera={vm.switchMode(PresentationMode.MAP);screen=if(active)"Navigation" else "Route"},requestCamera={cameraLauncher.launch(Manifest.permission.CAMERA)},
        dismissOnboarding={vm.dismissOnboarding();screen="Explore"},refreshRegion=vm::refreshRegionStatus,
        installRegion={importRegion=ui.cityMode.regionId;packLauncher.launch(arrayOf("application/json","application/octet-stream","*/*"))},
        cancelImport={importJob?.cancel()},cancelPick={back()},
        openCategory={category->searchFor="place";vm.updateSearch(category.query);screen="Results"},closeResults={vm.updateSearch("");screen="Explore"},
        call={phone->runCatching{context.startActivity(Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+phone.filter{it.isDigit()||it=='+'})))}},
        openWebsite={site->runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(if(site.startsWith("http://")||site.startsWith("https://"))site else "https://$site")))}})
    val importing=importJob?.isActive==true
    // --- Behind the scenes: connectivity, trip memory, touch cues, screen wake ---
    val online=com.rohanc.navgate.ui.map.rememberMapConnection()
    SideEffect{vm.networkAvailable=online}
    val tripRoute=backend.session.route
    // Save the map along the route while there is a connection, so it still draws if the signal drops mid-trip.
    LaunchedEffect(tripRoute?.id,online,screen){
        if(online&&tripRoute!=null&&screen in listOf("Directions","Route","Navigation","Camera"))
            com.rohanc.navgate.ui.map.RouteTilePrefetcher.prefetch(context,tripRoute.id,tripRoute.geometry)
    }
    // Keep the screen on while guiding, and give a touch cue before each turn.
    DisposableEffect(active,screen){
        val window=(context as? Activity)?.window
        if(active||screen=="Camera")window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose{window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
    }
    val drivingTrip=(tripRoute?.profile?:"")=="driving"
    val toTurn=backend.session.distanceToManeuver
    val cue=if(!active)0 else if(drivingTrip) when{toTurn<40->2;toTurn<150->1;else->0} else when{toTurn<12->2;toTurn<40->1;else->0}
    val turnKind=tripRoute?.maneuvers?.getOrNull(backend.session.maneuverIndex)?.kind
    LaunchedEffect(active,backend.session.maneuverIndex,cue){
        if(cue>0)com.rohanc.navgate.ui.location.TurnHaptics.cue(context,turnKind,cue)
    }
    // Trip summary for the arrival screen.
    var tripStartMillis by remember{mutableLongStateOf(0L)}
    var topSpeedMps by remember{mutableDoubleStateOf(0.0)}
    LaunchedEffect(active){if(active&&tripStartMillis==0L){tripStartMillis=System.currentTimeMillis();topSpeedMps=0.0}}
    // Collected in an effect, not in composition, so the 10 Hz motion updates do not recompose the whole screen.
    LaunchedEffect(active){if(active)vm.motionState.collect{m->m?.speedMps?.takeIf{it<80}?.let{if(it>topSpeedMps)topSpeedMps=it}}}
    LaunchedEffect(screen){if(screen=="Explore")tripStartMillis=0L}
    val tripSummary=if(screen=="Arrival"&&tripStartMillis>0&&tripRoute!=null){
        val minutes=((System.currentTimeMillis()-tripStartMillis)/60_000.0).coerceAtLeast(1.0)
        val km=tripRoute.distanceMeters/1000.0
        listOfNotNull("%.1f km".format(km),"${minutes.toInt()} min","avg %d km/h".format((km/(minutes/60.0)).toInt()),
            topSpeedMps.takeIf{drivingTrip&&it>1}?.let{"top %d km/h".format((it*3.6).toInt())}).joinToString(" · ")
    }else null
    val data=JourneyData(screen,searchFor,ui,backend.session,ui.snapshot.route?.steps.orEmpty(),backend.session.route?.takeIf{it.external}?.warnings.orEmpty(),permission,prefs.voiceEnabled,ui.areaName,
        if(searchFor=="saved")vm.searchSaved(ui.searchQuery)else ui.places,details?.availability,details?.entranceLabels.orEmpty(),active,if(showMotion)vm.motionState else null,
        SettingsModel(prefs,ui.cityMode,active,voiceAvailability,permission),if(screen=="Camera")"AR" else if(prefs.buildings3d)"3D" else "2D",services.regionDocuments.enabled,importing,importState==ImportState.Verifying,cameraPermission,alignment,
        permission&&ui.currentLocationUsable,online=online,tripSummary=tripSummary)
    var showLayers by remember{mutableStateOf(false)}
    var insetTop by remember{mutableFloatStateOf(0f)}
    var insetBottom by remember{mutableFloatStateOf(0f)}
    val journeyMap=screen in setOf("Explore","Place","Directions","Route","Navigation","Arrival","MapPick","ManualPin","My routes","Results")
    NavGateTheme(dark=dark){CompositionLocalProvider(LocalSolidGlass provides prefs.reduceTransparency,com.rohanc.navgate.ui.journey.LocalGlassOpacity provides prefs.glassOpacity,LocalHazeState provides hazeState){
        Surface(Modifier.fillMaxSize()){
            Box(Modifier.fillMaxSize()){
                Box(Modifier.fillMaxSize().hazeSource(hazeState)){
                    val mapUi=if(screen in listOf("Search","Results")&&searchFor=="saved")ui.copy(places=vm.searchSaved(ui.searchQuery))else ui
                    if(renderMap) NativeMap(mapUi,dark,permission,active,backend.session.sessionId,backend.session.accuracyMeters,inspectManualPoint.takeIf{screen in listOf("My routes","ManualPin")} ?: backend.session.route?.maneuvers?.getOrNull(selectedManeuver ?: -1)?.target?.let{Coordinate(it.latitude,it.longitude)},onFeedback={feedback=it},bottomInsetDp=if(journeyMap)insetBottom else LocalConfiguration.current.screenHeightDp*0.42f,topInsetDp=if(journeyMap)insetTop else 80f,controlsBottomDp=if(journeyMap)insetBottom+12f else null,onRequestLocation={locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))},screenIsExplore=screen=="Explore",showPins=screen=="Results",onOpenLayers={showLayers=true},motion=if(showMotion)vm.motionState else null,alongMeters=backend.session.alongMeters,manualPins=if(screen in listOf("My routes","ManualPin"))manualPins else emptyList(),onPin=when(screen){
                        "ManualPin"->({point->manualPointJson=Json.encodeToString(point);inspectManualPoint=Coordinate(point.latitude,point.longitude);screen="My routes"})
                        "MapPick"->({point->choose(PlaceSearchResult("pin:${java.util.UUID.randomUUID()}","Dropped pin","Selected on map",point.latitude,point.longitude,com.rohanc.navgate.model.PlaceType.Landmark))})
                        else->null
                    }){place->
                        if(screen in listOf("Search","Results","MapPick"))choose(place)
                        else if(!active){placeCaller=screen;vm.focusPlace(place);screen="Place"}
                    }
                    if(screen=="Camera" && cameraPermission && backend.session.route!=null){
                        var arFrame by remember{mutableStateOf<com.rohanc.navgate.ui.ar.ArFrameState?>(null)}
                        var arStatus by remember{mutableStateOf<com.rohanc.navgate.ui.ar.ArSessionStatus>(com.rohanc.navgate.ui.ar.ArSessionStatus.Starting)}
                        val aligner=remember{com.rohanc.navgate.ui.ar.ArGeoAligner()}
                        val arBasis by com.rohanc.navgate.ui.ar.rememberCameraBasis(true){ui.displayLocation?.let{GeoPoint(it.latitude,it.longitude)}}
                        val gps=ui.displayLocation?.let{GeoPoint(it.latitude,it.longitude)}
                        val motionNow=vm.motionState.value
                        val arCamera=remember(arFrame){arFrame?.let{f->
                            val compass=arBasis?.takeIf{kotlin.math.abs(it.pitchDegrees)<55}?.headingDegrees
                            aligner.update(f.reading,compass,gps,ui.displayAccuracy?.toDouble()?:20.0,motionNow?.headingDegrees,motionNow?.speedMps?:0.0)
                        }}
                        val here=if(arStatus is com.rohanc.navgate.ui.ar.ArSessionStatus.Unavailable)gps else (arCamera?.position?:gps)
                        // Checkpoints work behind the scenes: the track keeps only the road you are on loaded, and hands the overlay a short path.
                        val track=remember(backend.session.route?.id){backend.session.route?.let{com.rohanc.navgate.ui.ar.CheckpointTrack(it)}}
                        var path by remember(track){mutableStateOf<List<GeoPoint>>(emptyList())}
                        var roadEnd by remember(track){mutableStateOf(0.0)}
                        var turnKind by remember(track){mutableStateOf<String?>(null)}
                        var turnLabel by remember(track){mutableStateOf<String?>(null)}
                        val stepKey=here?.let{(it.latitude*1e5).toLong() to (it.longitude*1e5).toLong()}
                        LaunchedEffect(track,stepKey){
                            val position=here?:return@LaunchedEffect
                            val t=track?:return@LaunchedEffect
                            val result=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default){
                                t.update(position);Triple(t.pathAhead(),t.metersToStageEnd,t.stageEnd)
                            }
                            path=result.first;roadEnd=result.second;turnKind=result.third?.turnKind?:if(result.third?.kind==com.rohanc.navgate.ui.ar.GateKind.Finish)"Arrive" else null;turnLabel=result.third?.label
                        }
                        if(arStatus is com.rohanc.navgate.ui.ar.ArSessionStatus.Unavailable){
                            CameraPreview(Modifier.fillMaxSize())
                            com.rohanc.navgate.ui.ar.ArRoadOverlay(path,roadEnd,gps,arBasis,turnKind,turnLabel,roadEnd,Modifier.fillMaxSize())
                        }else{
                            com.rohanc.navgate.ui.ar.ArSessionView({arFrame=it;arStatus=com.rohanc.navgate.ui.ar.ArSessionStatus.Running},{if(it is com.rohanc.navgate.ui.ar.ArSessionStatus.Unavailable)arStatus=it},Modifier.fillMaxSize())
                            com.rohanc.navgate.ui.ar.ArRoadOverlay(path,roadEnd,arCamera?.position,arCamera?.basis,turnKind,turnLabel,roadEnd,Modifier.fillMaxSize(),arFrame)
                        }
                    }
                }
                JourneyUi(data,{top,bottom->insetTop=top;insetBottom=bottom},actions){
                    if(screen=="My routes")GlassPage("My routes",actions.back){
                        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal=16.dp).padding(bottom=24.dp)){
                            manualFormState.SaveableStateProvider("manual-editor"){ManualRoutePanel(services.manualRoutes,manualPins,{manualPinsJson=Json.encodeToString(it)},manualPoint,
                                onPick={screen="ManualPin"},onClearPoint={manualPointJson=null},onInspect={inspectManualPoint=Coordinate(it.latitude,it.longitude)},onClearInspection={inspectManualPoint=null})}
                        }
                    }
                }
                if(showLayers)LayersSheet(prefs,{vm.updateFrontendPreferences(it)},{showLayers=false})
            }
        }
        if(stopDialog)AlertDialog(onDismissRequest={stopDialog=false},title={Text("Stop this trip?")},text={Text("You can plan another route after stopping.")},confirmButton={TextButton(enabled=!ui.stopPending,onClick={vm.stopNavigation();stopDialog=false}){Text("Stop")}},dismissButton={TextButton(onClick={stopDialog=false}){Text("Keep navigating")}})
        feedback?.let{message->AlertDialog(onDismissRequest={feedback=null},title={Text("NavGate")},text={Text(message)},confirmButton={TextButton(onClick={feedback=null}){Text("OK")}})}
        if(clearHistoryDialog)AlertDialog(onDismissRequest={clearHistoryDialog=false},title={Text("Clear trip history?")},text={Text("Saved places, recent places and an active trip are retained.")},confirmButton={TextButton(onClick={vm.clearRouteHistory();clearHistoryDialog=false}){Text("Clear")}},dismissButton={TextButton(onClick={clearHistoryDialog=false}){Text("Cancel")}})
        if(clearDialog)AlertDialog(onDismissRequest={clearDialog=false},title={Text("Clear recent places?")},text={Text("Your saved places will remain available.")},confirmButton={TextButton(onClick={vm.clearRecents();clearDialog=false}){Text("Clear")}},dismissButton={TextButton(onClick={clearDialog=false}){Text("Cancel")}})
    }}
}
