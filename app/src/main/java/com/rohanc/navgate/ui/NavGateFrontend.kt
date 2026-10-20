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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rohanc.navgate.core.*
import com.rohanc.navgate.model.PlaceSearchResult
import com.rohanc.navgate.model.Coordinate
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

/** Native UI: all place, route and progress values come from the local backend. */
@Composable
fun NavGateFrontend(injectedViewModel:NavGateViewModel?=null,renderMap:Boolean=true,trackLocation:Boolean=true) {
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
        else if(pack!=null&&region!=null){if(vm.backendState.value.session.status in NavigationSession.ACTIVE){feedback="Stop the current walk before installing region files.";packUri=null;importRegion=null}else importJob=scope.launch{
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
    val locationLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){permission=it.values.any{granted->granted}}
    val cameraLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){cameraPermission=it}
    val lifecycleOwner=LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner){
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME){permission=hasLocationPermission(context);cameraPermission=ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED}}
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose{lifecycleOwner.lifecycle.removeObserver(observer)}
    }
    val active=backend.session.status in NavigationSession.ACTIVE
    val prefs=ui.frontendPreferences
    val dark=when(prefs.appearance){"light"->false;"system"->isSystemInDarkTheme();else->true}
    if(trackLocation) BindLocationTracking(enabled=permission,onLocationSample=vm::onLocationFix,onSignalTick=vm::checkSignal)
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
    var sheetExpanded by rememberSaveable(screen){mutableStateOf(screen in listOf("Search","Results","Settings","Offline","Steps","Saved","Recents"))}
    BackHandler(enabled=screen!="Explore"){back()}
    NavGateTheme(dark=dark){CompositionLocalProvider(LocalSolidGlass provides prefs.reduceTransparency){
        Surface(Modifier.fillMaxSize()){
            Box(Modifier.fillMaxSize()){
                val mapUi=if(screen in listOf("Search","Results")&&searchFor=="saved")ui.copy(places=vm.searchSaved(ui.searchQuery))else ui
                if(renderMap) NativeMap(mapUi,dark,permission,active,backend.session.sessionId,backend.session.accuracyMeters,backend.session.route?.maneuvers?.getOrNull(selectedManeuver ?: -1)?.target?.let{Coordinate(it.latitude,it.longitude)},onFeedback={feedback=it}){place->
                    if(screen in listOf("Search","Results","MapPick"))choose(place)
                    else if(!active){placeCaller=screen;vm.focusPlace(place);screen="Place"}
                }
                if(screen=="Camera" && cameraPermission)CameraPreview(Modifier.fillMaxSize())
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Row(verticalAlignment=Alignment.CenterVertically){
                        if(screen!="Explore" && ui.preferencesLoaded)MapControlButton(onClick={back()}){Icon(Icons.Rounded.ArrowBack,"Back")}
                        if(screen!="Explore")Spacer(Modifier.width(10.dp))
                        Surface(onClick={openUtility("Offline")},enabled=ui.preferencesLoaded,shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=.97f),shadowElevation=3.dp){
                            Row(Modifier.heightIn(min=48.dp).padding(horizontal=16.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                Icon(Icons.Rounded.Place,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.primary)
                                Text("Worldwide",style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.SemiBold)
                                Icon(Icons.Rounded.ExpandMore,null,Modifier.size(16.dp))
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        MapControlButton(enabled=ui.preferencesLoaded,onClick={openUtility("Settings")}){Icon(Icons.Rounded.Settings,"Settings",tint=MaterialTheme.colorScheme.onSurface)}
                    }
                    if(active && (!permission||backend.session.signalReason!=null))NavGateGlass{Text("Location needs attention",fontWeight=FontWeight.Bold);Text(if(!permission)"Location access is off. Guidance will pause until a fresh accurate fix is available." else backend.session.signalReason.orEmpty());if(!permission)TextButton(onClick={appSettings()}){Text("Open app settings")}}
                    if(screen=="Navigation" && active)NavGateGlass(guidance=true){Text(backend.session.instruction ?: "Waiting for guidance",style=MaterialTheme.typography.headlineSmall);Text(backend.session.distanceToManeuver.asDistanceLabel())}
                    Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.BottomCenter){
                    NavGateGlass(Modifier.fillMaxWidth().heightIn(max=if(sheetExpanded) 600.dp else 410.dp)){
                        val pageScroll=key(screen){rememberScrollState()}
                        Column(Modifier.verticalScroll(pageScroll),verticalArrangement=Arrangement.spacedBy(12.dp)){
                            val title=if(!ui.preferencesLoaded)"Loading local preferences" else if((ui.showOnboarding && screen=="Explore")||screen=="Setup")"Welcome to NavGate" else when(screen){"Place"->ui.focusedPlace?.title ?: "Place";"Route"->"Your walking route";"Navigation"->"On your way";"Arrival"->"You have arrived";"Offline"->"Offline & region data";"Camera"->"Camera assistance";else->screen}
                            SheetGrip(expanded=sheetExpanded,onExpandedChange={sheetExpanded=it})
                            if(screen!="Explore"||ui.showOnboarding||!ui.preferencesLoaded)Text(title,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
                            if(!ui.preferencesLoaded){CircularProgressIndicator();Text("Loading your region and saved settings…")}else if((ui.showOnboarding && screen=="Explore")||screen=="Setup"){
                                Text("Search cities, addresses and landmarks around the world.")
                                Text("Location helps position you. You can also choose a start manually.")
                                Button(onClick={locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))},modifier=Modifier.fillMaxWidth()){Text("Enable location")}
                                TextButton(onClick={vm.dismissOnboarding();screen="Explore"}){Text("Continue to map")}
                                Text("Camera assistance is optional and asks for permission only when you choose it during a walk.")
                            }else when(screen){
                                "Explore"->{
                                    Text("Where would you like to go?",style=MaterialTheme.typography.titleLarge)
                                    Surface(onClick={search("place")},shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceVariant){Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){Icon(Icons.Rounded.Search,null,tint=MaterialTheme.colorScheme.primary);Text("Search anywhere",style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
                                    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("Gate","Academic","Food","Hostel","Transit").forEach{category->FilterChip(ui.searchQuery==category,{vm.updateSearch(category);searchFor="place";screen="Results"},{Text(category)})}}
                                    Text("Installed local places",fontWeight=FontWeight.Bold)
                                    if(ui.isLoadingPlaces)LinearProgressIndicator(Modifier.fillMaxWidth())
                                    if(!ui.isLoadingPlaces&&ui.places.isEmpty())Text("No places are installed for this region. Open region data to check availability.")
                                    ui.places.take(1).forEach{place->PlaceRow(place){placeCaller="Explore";vm.focusPlace(place);screen="Place"}}
                                }
                                "Search","Results"->{
                                    OutlinedTextField(value=ui.searchQuery,onValueChange=vm::updateSearch,label={Text(if(searchFor=="from")"Choose start" else if(searchFor=="to")"Choose destination" else "Search anywhere")},singleLine=true,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={keyboard?.hide();screen="Results"}),modifier=Modifier.fillMaxWidth())
                                    if(searchFor=="from"){TextButton(onClick={currentOrigin()}){Text("Use current location")};if(!permission)TextButton(onClick={appSettings()}){Text("Location permission settings")}}
                                    TextButton(onClick={vm.updateSearch("")}){Text("Clear query")}
                                    if(ui.searchQuery.isBlank()&&ui.recentPlaces.isNotEmpty()&&searchFor!="saved"){Text("Recent places",fontWeight=FontWeight.Bold);ui.recentPlaces.take(3).forEach{place->PlaceRow(place,onDirections=if(searchFor in listOf("from","to"))null else ({directions(place)})){choose(place)}};TextButton(onClick={clearDialog=true}){Text("Clear recent places")}}
                                    if(searchFor!="saved")ui.searchNotice?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
                                    val results=if(searchFor=="saved")vm.searchSaved(ui.searchQuery)else ui.places
                                    if(ui.isLoadingPlaces&&searchFor!="saved")LinearProgressIndicator(Modifier.fillMaxWidth())
                                    if(!ui.isLoadingPlaces && results.isEmpty()){Text("No places found. Try a different name.");TextButton(onClick={vm.updateSearch("")}){Text("Clear search")}}
                                    if(screen=="Results")Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("Food","Academic","Hostel","Transit").forEach{category->FilterChip(ui.searchQuery==category,{vm.updateSearch(category)},{Text(category)})}}
                                    if(screen=="Results")TextButton(onClick={mapList=!mapList}){Text(if(mapList)"Show map only"else"Show result list")}
                                    if(mapList||screen=="Search")results.filterNot{place->screen=="Search"&&ui.searchQuery.isBlank()&&searchFor!="saved"&&ui.recentPlaces.take(3).any{it.id==place.id}}.forEach{place->PlaceRow(place,onDirections=if(searchFor in listOf("from","to"))null else ({directions(place)})){choose(place)}}
                                }
                                "MapPick"->{Text("Tap a mapped place to choose a starting point. Places without reviewed coordinates cannot be selected on the map.");Button(onClick={search("from")}){Text("Search for a start instead")}}
                                "Place"->{ui.focusedPlace?.let{place->
                                    Text(place.subtitle);Text(place.category ?: place.type.name);Text(if(place.id.startsWith("osm:"))"Worldwide search result. Directions require a supported routing provider." else readinessLabel(details?.availability?.readiness ?: backend.destinationAvailability[place.id]?.readiness));Text(if(place.coordinate==null)"Coordinates have not been reviewed for this place." else if(place.id.startsWith("osm:"))"OpenStreetMap location · ${place.city.orEmpty()}" else "Installed location · ${place.type}")
                                    Button(enabled=!active,onClick={directions(place)},modifier=Modifier.fillMaxWidth()){Text("Directions")}
                                    details?.availability?.entranceIds.orEmpty().forEach{id->OutlinedButton(enabled=!active,onClick={directions(place);vm.selectEntrance(id)}){Text("Use entrance: ${details?.entranceLabels?.get(id) ?: id}")}}
                                    TextButton(onClick={expandedDetails=!expandedDetails}){Text(if(expandedDetails)"Collapse details"else"Expand details")}
                                    OutlinedButton(enabled=place.coordinate!=null && !active,onClick={directions(place,asOrigin=true)}){Text("Start here")}
                                    OutlinedButton(onClick={share(place)}){Text("Share place")}
                                    OutlinedButton(onClick={vm.toggleSaved(place)}){Text(if(ui.savedPlaces.any{it.id==place.id})"Remove from saved" else "Save place")}
                                }}
                                "Directions","Route"->{
                                    if(detailId!=null&&details==null&&screen in listOf("Place","Arrival"))TextButton(onClick={detailRetry++}){Text("Reload place details")}
                            if(selectedManeuver!=null&&screen in listOf("Route","Navigation"))TextButton(onClick={selectedManeuver=null}){Text("Clear maneuver inspection")}
                            if(ui.canResume){
                                        Text(details?.title ?: backend.session.route?.destinationPlaceId.orEmpty(),fontWeight=FontWeight.Bold)
                                        backend.session.route?.let{Text("${it.distanceMeters.asDistanceLabel()} · ${it.estimatedSeconds.asEtaLabel()}")}
                                    }else{
                                    OutlinedButton(onClick={search("from")},modifier=Modifier.fillMaxWidth()){Text("From: ${ui.selectedOrigin?.title ?: "Choose start"}")}
                                    OutlinedButton(onClick={search("to")},modifier=Modifier.fillMaxWidth()){Text("To: ${ui.selectedDestination?.title ?: "Choose destination"}")}
                                    Row(Modifier.horizontalScroll(rememberScrollState())) {TextButton(onClick=vm::swapEndpoints){Text("Swap")};TextButton(onClick={currentOrigin()}){Text("Current location")}}
                                    OutlinedButton(onClick={searchFor="from";vm.updateSearch("");screen="MapPick"}){Text("Choose start on map")}
                                    TextButton(onClick={vm.clearPlan();selectedManeuver=null;screen="Directions"}){Text("Clear plan")}
                                    Text("Walking",fontWeight=FontWeight.Bold)
                                    val entrances=details?.availability?.entranceIds ?: backend.destinationAvailability[ui.selectedDestination?.id]?.entranceIds.orEmpty()
                                    if(entrances.isNotEmpty()){
                                        Text("Reviewed entrances",fontWeight=FontWeight.Bold)
                                        entrances.forEach{id->FilterChip(selected=ui.selectedEntranceId==id,onClick={vm.selectEntrance(id)},label={Text(details?.entranceLabels?.get(id) ?: id)})}
                                        if(ui.selectedEntranceId!=null)TextButton(onClick={vm.selectEntrance(null)}){Text("Use recommended entrance")}
                                    }
                                    ui.selectedDestination?.let{Text(readinessLabel(details?.availability?.readiness ?: backend.destinationAvailability[it.id]?.readiness))}
                                    if(ui.isLoadingRoute)LinearProgressIndicator(Modifier.fillMaxWidth())
                                    ui.preview?.let{Text("${it.etaLabel} · ${it.distanceLabel}",style=MaterialTheme.typography.titleLarge);Text(it.firstInstruction)}
                                    ui.statusMessage?.let{Text(it,color=MaterialTheme.colorScheme.secondary)}
                                    if(ui.routeFailure!=null){Text("A reviewed campus path is required before guidance can start.");TextButton(onClick={openUtility("Offline")}){Text("View region readiness")}}
                                    Button(onClick=vm::replan,enabled=!ui.isLoadingRoute && ui.selectedDestination!=null && ui.selectedOrigin!=null,modifier=Modifier.fillMaxWidth()){Text("Preview walking route")}
                                    if(ui.preview!=null&&!permission)TextButton(onClick={appSettings()}){Text("Enable location in app settings to start")}
                                    if(ui.preview!=null){Button(onClick=vm::startNavigation,enabled=!ui.startPending&&permission,modifier=Modifier.fillMaxWidth()){Text("Start walking")};TextButton(onClick={screen="Steps"}){Text("All steps")};ui.selectedDestination?.let{place->TextButton(onClick={share(place)}){Text("Share destination")};OutlinedButton(onClick={vm.toggleSaved(place)}){Text(if(ui.savedPlaces.any{it.id==place.id})"Remove saved destination"else"Save destination")}}}
                                    }
                                }
                                "Navigation"->{
                                    Text("${backend.session.remainingSeconds.asEtaLabel()} · ${backend.session.remainingMeters.asDistanceLabel()}",style=MaterialTheme.typography.headlineSmall)
                                    Text(ui.selectedDestination?.title ?: details?.title ?: backend.session.route?.destinationPlaceId.orEmpty())
                                    Row(Modifier.horizontalScroll(rememberScrollState())){TextButton(onClick={screen="Steps"}){Text("Steps")};TextButton(onClick={screen="Camera";vm.switchMode(PresentationMode.AR_ASSIST)}){Text("Camera")};TextButton(onClick={vm.updateFrontendPreferences(prefs.copy(voiceEnabled=!prefs.voiceEnabled))}){Text(if(prefs.voiceEnabled)"Mute" else "Sound")}}
                                    if(prefs.voiceEnabled&&voiceAvailability==VoiceAvailability.Unavailable)Text("Offline speech is unavailable. Follow the written directions.")
                                    Button(enabled=!ui.stopPending,onClick={vm.stopNavigation()},modifier=Modifier.fillMaxWidth()){Text("Stop navigation")}
                                }
                                "Steps"->{
                                    backend.session.route?.maneuvers?.forEachIndexed{index,step->TextButton(onClick={selectedManeuver=index;screen=if(active)"Navigation"else"Route"}){Text("${index+1}. ${step.instruction}",fontWeight=if(index==backend.session.maneuverIndex)FontWeight.Bold else FontWeight.Normal)}}
                                    if(backend.session.route==null)Text("Plan a reviewed route to see its steps.")
                                    if(!active&&backend.session.route!=null)Button(enabled=permission&&!ui.startPending,onClick=vm::startNavigation){Text("Start walking")}
                                    Button(onClick={screen=if(active)"Navigation" else "Route"}){Text("Return to map")}
                                }
                                "Arrival"->{Text("Your walking guidance is complete.");OutlinedButton(enabled=ui.selectedDestination!=null||details?.place!=null,onClick={vm.finishArrival(keepDestinationContext=true,destinationContext=details?.place);screen="Explore"}){Text("Explore nearby")};Text(ui.selectedDestination?.title ?: details?.title ?: backend.session.route?.destinationPlaceId.orEmpty());(ui.selectedDestination ?: details?.place)?.let{place->OutlinedButton(onClick={vm.toggleSaved(place)}){Text(if(ui.savedPlaces.any{it.id==place.id})"Remove from saved"else"Save destination")}};Button(onClick={vm.finishArrival();screen="Explore"}){Text("Done")}}
                                "Saved","Recents"->{
                                    if(screen=="Saved"){OutlinedButton(onClick={search("saved")}){Text("Search saved places")};FilterChip(savedSort,{savedSort=!savedSort},{Text(if(savedSort)"Sorted A–Z"else"Sort A–Z")})}
                                    if(screen=="Recents")Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                        FilterChip(recentView=="places",{recentView="places"},{Text("Places")})
                                        FilterChip(recentView=="trips",{recentView="trips"},{Text("Trips")})
                                    }
                                    if(screen=="Recents"&&recentView=="trips"){
                                        if(ui.routeHistory.isEmpty())Text("Your completed and stopped walks will appear here.")
                                        ui.routeHistory.forEach{trip->
                                            Text(trip.destinationTitle,fontWeight=FontWeight.Bold)
                                            Text("${trip.status} · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(trip.recordedAtEpochMillis))}")
                                            OutlinedButton(enabled=!active,onClick={searchFor="to";vm.updateSearch(trip.destinationTitle);screen="Search"}){Text("Plan this destination again")}
                                        }
                                        if(ui.routeHistory.isNotEmpty())TextButton(onClick={clearHistoryDialog=true}){Text("Clear trip history")}
                                    }else{
                                        val places=if(screen=="Saved")if(savedSort)ui.savedPlaces.sortedBy{it.title.lowercase()}else ui.savedPlaces else ui.recentPlaces
                                        if(places.isEmpty())Text(if(screen=="Saved")"Save places to find them here." else "Places you choose will appear here.")
                                        places.forEach{place->PlaceRow(place,onDirections={directions(place)},onSave=if(screen=="Saved")({vm.toggleSaved(place)})else null,saved=ui.savedPlaces.any{it.id==place.id}){placeCaller=screen;vm.focusPlace(place);screen="Place"}}
                                        if(screen=="Recents"&&places.isNotEmpty())TextButton(onClick={clearDialog=true}){Text("Clear recent places")}
                                    }
                                }
                                "Settings"->{
                                    Text("Place history and trip summaries are stored locally. Clearing them keeps your bookmarks.")
                                    TextButton(onClick={clearDialog=true}){Text("Clear local recent places")}
                                    TextButton(enabled=!active,onClick={screen="Setup"}){Text("Review app setup")}
                                    Text("Appearance",fontWeight=FontWeight.Bold)
                                    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("dark","light","system").forEach{mode->FilterChip(prefs.appearance==mode,{vm.updateFrontendPreferences(prefs.copy(appearance=mode))},{Text(mode.replaceFirstChar{it.uppercase()})})}}
                                    PreferenceRow("Reduce transparency",prefs.reduceTransparency){vm.updateFrontendPreferences(prefs.copy(reduceTransparency=it))}
                                    PreferenceRow("Reduce motion",prefs.reduceMotion){vm.updateFrontendPreferences(prefs.copy(reduceMotion=it))}
                                    PreferenceRow("Spoken guidance",prefs.voiceEnabled){vm.updateFrontendPreferences(prefs.copy(voiceEnabled=it))}
                                    Text(when(voiceAvailability){VoiceAvailability.Initializing->"Checking installed offline speech…";VoiceAvailability.OfflineReady->"Offline speech is ready";VoiceAvailability.Unavailable->"No usable offline voice is installed. Written guidance remains available."})
                                    Text("Region",fontWeight=FontWeight.Bold)
                                    CityMode.entries.forEach{city->TextButton(enabled=!active,onClick={vm.setCityMode(city)}){Text(city.label+(if(city==ui.cityMode)" ✓" else ""))}}
                                    OutlinedButton(onClick={openUtility("Offline")}){Text("Offline & region data")}
                                    TextButton(onClick={appSettings()}){Text("Permissions in Android settings")}
                                    if(!permission)Button(onClick={locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))}){Text("Enable location")}
                                }
                                "Offline"->{
                                    val region=ui.regionStatus
                                    if(region==null){Text("Region status is not available yet.")}else {
                                        Text("Readiness: ${region.readiness}")
                                        Text("${region.placeCount} places · ${region.missingCoordinateCount} without coordinates")
                                        Text("${region.reviewedEdgeCount} reviewed paths · ${region.reviewedEntranceCount} reviewed entrances")
                                        region.version?.let{Text("Version: $it")};region.reviewedAt?.let{Text("Reviewed: $it")}
                                        region.source?.let{Text("Source: $it")};region.attribution?.let{Text(it)};region.license?.let{Text("License: $it")}
                                        Text("Map tiles require a connection. Routing data and saved places are stored locally.")
                                    }
                                    val importing=importJob?.isActive==true
                                    val updateEnabled=services.regionDocuments.enabled&&!active&&!importing
                                    Button(enabled=updateEnabled,onClick={importRegion=ui.cityMode.regionId;packLauncher.launch(arrayOf("application/json","application/octet-stream","*/*"))}){Text("Install signed region files")}
                                    if(!services.regionDocuments.enabled)Text("External region updates are not enabled in this build. Your installed data remains available.")
                                    if(active)Text("Stop this walk before installing another region.")
                                    if(importing){LinearProgressIndicator(Modifier.fillMaxWidth());Text(if(importState==ImportState.Verifying)"Verifying signature and routing data…" else "Reading selected documents…");TextButton(onClick={importJob?.cancel()}){Text("Cancel import")}}
                                    OutlinedButton(onClick=vm::refreshRegionStatus){Text("Refresh status")}
                                }
                                "Camera"->{
                                    if(!cameraPermission){Text("Allow camera access to view compass assistance.");TextButton(onClick={appSettings()}){Text("Camera permission settings")};Button(onClick={cameraLauncher.launch(Manifest.permission.CAMERA)}){Text("Allow camera")}}
                                    else{val heading by rememberHeadingState(backend.session.location);val alignment=alignmentStatus(ui.snapshot,heading);Text(if(permission&&ui.currentLocationUsable)alignment.label else "Camera cues paused until location access and an accurate fix are available.");if(permission&&ui.currentLocationUsable&&alignment.level!=AlignmentLevel.Recover)Icon(Icons.Rounded.Navigation,"Direction of route",modifier=Modifier.size(64.dp).graphicsLayer(rotationZ=alignment.deltaDegrees.toFloat()));Text("Compass assistance uses the same walking session. No world-anchored route is available.")}
                                    TextButton(onClick={feedback=backend.session.signalReason ?: "Location accuracy: ${backend.session.accuracyMeters?.asDistanceLabel() ?: "waiting for a fix"}. Compass assistance uses device heading; it does not detect obstacles or anchor a route in the camera image."}){Text("Camera signal details")}
                                    TextButton(onClick={vm.updateFrontendPreferences(prefs.copy(voiceEnabled=!prefs.voiceEnabled))}){Text(if(prefs.voiceEnabled)"Mute spoken cues" else "Enable spoken cues")}
                                    Button(enabled=!ui.stopPending,onClick={vm.stopNavigation()}){Text("Stop navigation")}
                                    Button(onClick={vm.switchMode(PresentationMode.MAP);screen="Navigation"}){Text("Return to map")}
                                }
                            }
                            if(detailId!=null&&details==null&&screen in listOf("Place","Arrival"))TextButton(onClick={detailRetry++}){Text("Reload place details")}
                            if(selectedManeuver!=null&&screen in listOf("Route","Navigation"))TextButton(onClick={selectedManeuver=null}){Text("Clear maneuver inspection")}
                            if(ui.canResume){Text("A previous walk is available. A fresh location is required to resume.");Button(enabled=permission&&!ui.startPending,onClick=vm::startNavigation){Text("Resume walk")};TextButton(onClick=vm::stopNavigation){Text("Discard previous walk")}}
                            if(!ui.showOnboarding && screen !in listOf("Directions","Route"))ui.statusMessage?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
                            if(backend.storageError!=null)TextButton(onClick=vm::retryInitialization){Text("Retry local data")}
                        }
                    }
                    }
                    if(!ui.showOnboarding && !active && screen!="Arrival")NavGateDock(
                        modifier=Modifier.align(Alignment.CenterHorizontally),
                        selected=selectedFrontendTab(if(screen in listOf("Settings","Offline"))utilityStack.firstOrNull() ?: screen else screen,searchFor),
                        onSelect={name->utilityStack=emptyList();if(name=="Directions")directionCaller="Explore";screen=name})
                }
            }
        }
        if(stopDialog)AlertDialog(onDismissRequest={stopDialog=false},title={Text("Stop this walk?")},text={Text("You can plan another route after stopping.")},confirmButton={TextButton(enabled=!ui.stopPending,onClick={vm.stopNavigation();stopDialog=false}){Text("Stop")}},dismissButton={TextButton(onClick={stopDialog=false}){Text("Keep walking")}})
        feedback?.let{message->AlertDialog(onDismissRequest={feedback=null},title={Text("NavGate")},text={Text(message)},confirmButton={TextButton(onClick={feedback=null}){Text("OK")}})}
        if(clearHistoryDialog)AlertDialog(onDismissRequest={clearHistoryDialog=false},title={Text("Clear trip history?")},text={Text("Saved places, recent places and an active trip are retained.")},confirmButton={TextButton(onClick={vm.clearRouteHistory();clearHistoryDialog=false}){Text("Clear")}},dismissButton={TextButton(onClick={clearHistoryDialog=false}){Text("Cancel")}})
        if(clearDialog)AlertDialog(onDismissRequest={clearDialog=false},title={Text("Clear recent places?")},text={Text("Your saved places will remain available.")},confirmButton={TextButton(onClick={vm.clearRecents();clearDialog=false}){Text("Clear")}},dismissButton={TextButton(onClick={clearDialog=false}){Text("Cancel")}})
    }}
}

@Composable private fun PlaceRow(place:PlaceSearchResult,onDirections:(()->Unit)?=null,onSave:(()->Unit)?=null,onShare:(()->Unit)?=null,saved:Boolean=false,onClick:()->Unit){
    val colors=MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()){
        Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically){
            Surface(shape=RoundedCornerShape(14.dp),color=colors.secondaryContainer.copy(alpha=.65f)){
                Box(Modifier.size(44.dp),contentAlignment=Alignment.Center){Icon(Icons.Rounded.Place,null,Modifier.size(22.dp),tint=colors.onSecondaryContainer)}
            }
            Column(Modifier.padding(horizontal=12.dp).weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){
                Text(place.title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(place.subtitle,style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.ChevronRight,null,Modifier.size(20.dp),tint=colors.onSurfaceVariant)
        }
        if(onDirections!=null||onSave!=null||onShare!=null)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){
            onDirections?.let{FilledTonalButton(onClick=it,shape=RoundedCornerShape(14.dp),contentPadding=PaddingValues(horizontal=14.dp,vertical=8.dp)){Icon(Icons.Rounded.DirectionsWalk,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("Directions")}}
            Spacer(Modifier.weight(1f))
            onSave?.let{IconButton(onClick=it){Icon(if(saved)Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,if(saved)"Unsave ${place.title}"else"Save ${place.title}",tint=if(saved)colors.primary else colors.onSurfaceVariant)}}
            onShare?.let{IconButton(onClick=it){Icon(Icons.Rounded.Share,"Share ${place.title}",tint=colors.onSurfaceVariant)}}
        }
        HorizontalDivider(Modifier.padding(top=8.dp),color=colors.onSurface.copy(alpha=.07f))
    }
}
@Composable private fun PreferenceRow(label:String,checked:Boolean,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f));Switch(checked,onChange)}}

@Composable private fun NativeMap(ui:NavGateUiState,dark:Boolean,permission:Boolean,active:Boolean,sessionId:String?,accuracyMeters:Double?,inspectPoint:Coordinate?,onFeedback:(String)->Unit,onPlace:(PlaceSearchResult)->Unit){
    val camera=rememberCameraState(firstPosition=CameraPosition(target=Position(latitude=20.0,longitude=0.0),zoom=2.0))
    val scope=rememberCoroutineScope()
    val viewport=LocalConfiguration.current
    val connection=rememberMapConnection()
    var following by rememberSaveable { mutableStateOf(true) }
    var centeredOnLocation by rememberSaveable { mutableStateOf(false) }
    var followingSession by rememberSaveable { mutableStateOf<String?>(null) }
    var perspective by rememberSaveable { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    val style=remember(dark){BaseStyle.Uri(if(dark)"asset://navgate-map-dark.json" else "asset://navgate-map-daylight.json")}
    val options=remember{MapOptions(renderOptions=RenderOptions(renderMode=RenderOptions.RenderMode.TextureView),ornamentOptions=OrnamentOptions(padding=PaddingValues(top=100.dp,start=12.dp,end=12.dp),logoAlignment=Alignment.TopStart,attributionAlignment=Alignment.TopEnd,isScaleBarEnabled=false))}
    suspend fun move(next:CameraPosition){if(ui.frontendPreferences.reduceMotion)camera.position=next else camera.animateTo(next,400.milliseconds)}
    fun fitRoute(){val points=ui.snapshot.route?.pathCoordinates.orEmpty();if(points.isEmpty())return
        val south=points.minOf{it.latitude};val north=points.maxOf{it.latitude};val west=points.minOf{it.longitude};val east=points.maxOf{it.longitude}
        following=false
        scope.launch{camera.animateTo(BoundingBox(Position(latitude=south,longitude=west),Position(latitude=north,longitude=east)),bearing=0.0,tilt=0.0,padding=PaddingValues(start=24.dp,end=72.dp,top=minOf(136,viewport.screenHeightDp/4).dp,bottom=(viewport.screenHeightDp*.45).dp),duration=if(ui.frontendPreferences.reduceMotion)0.milliseconds else 400.milliseconds)}
    }
    LaunchedEffect(inspectPoint){
        if(inspectPoint!=null){following=false;move(camera.position.copy(target=Position(latitude=inspectPoint.latitude,longitude=inspectPoint.longitude),zoom=18.0))}
        else if(active)following=true
    }
    LaunchedEffect(active,sessionId){if(active&&followingSession!=sessionId){followingSession=sessionId;following=true}}
    LaunchedEffect(camera.moveReason){if(camera.moveReason==CameraMoveReason.GESTURE)following=false}
    LaunchedEffect(ui.focusedPlace?.id,ui.cityMode){val target=ui.focusedPlace?.coordinate
        if(target!=null){following=false;move(camera.position.copy(target=Position(latitude=target.latitude,longitude=target.longitude),zoom=17.0))}
        else if(!active&&ui.snapshot.route==null && !centeredOnLocation){
            val fix=ui.snapshot.userLocation?.takeIf{ui.currentLocationUsable}
            if(fix!=null){centeredOnLocation=true;move(camera.position.copy(target=Position(latitude=fix.latitude,longitude=fix.longitude),zoom=15.5))}
        }
    }
    LaunchedEffect(ui.snapshot.route){if(!active)fitRoute()}
    LaunchedEffect(ui.snapshot.userLocation,following,active,permission){val fix=ui.snapshot.userLocation
        if(!active && !centeredOnLocation && ui.focusedPlace==null && ui.snapshot.route==null && permission && ui.currentLocationUsable && fix!=null){
            centeredOnLocation=true;move(camera.position.copy(target=Position(latitude=fix.latitude,longitude=fix.longitude),zoom=15.5))
        }
        if(active&&following&&permission&&ui.currentLocationUsable&&fix!=null&&ui.snapshot.locationConfidence.label!=com.rohanc.navgate.navigation.GuidanceConfidence.Low)move(camera.position.copy(target=Position(latitude=fix.latitude,longitude=fix.longitude),zoom=17.5))
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)){
        key(retry){MaplibreMap(modifier=Modifier.fillMaxSize(),baseStyle=style,options=options,cameraState=camera,onMapClick={position,_->
            val nearest=ui.places.filter{it.coordinate!=null}.minByOrNull{val c=it.coordinate!!;hypot((c.latitude-position.latitude)*111000,(c.longitude-position.longitude)*111000*cos(Math.toRadians(position.latitude)))}
            val c=nearest?.coordinate;val radius=(camera.metersPerDpAtTarget*24).takeIf{it.isFinite()}?.coerceIn(8.0,100.0) ?: 40.0
            if(c!=null&&hypot((c.latitude-position.latitude)*111000,(c.longitude-position.longitude)*111000*cos(Math.toRadians(position.latitude)))<radius){onPlace(nearest);ClickResult.Consume}else ClickResult.Pass
        },onMapLoadFailed={failed=true;loading=false},onMapLoadFinished={failed=false;loading=false}){
            val points=ui.places.mapNotNull{it.coordinate}.joinToString(","){"""{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[${it.longitude},${it.latitude}]}}"""}
            val places=rememberGeoJsonSource(GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[$points]}"""))
            CircleLayer(id="campus-places",source=places,radius=const(5.dp),color=const(NavSecondary),strokeColor=const(Color.White),strokeWidth=const(1.dp))
            val route=rememberGeoJsonSource(GeoJsonData.JsonString(MapRouteGeoJson.lineString(ui.snapshot.route)))
            LineLayer(id="walking-route-glow",source=route,color=const(NavPrimary.copy(alpha=.25f)),width=const(12.dp))
            LineLayer(id="walking-route",source=route,color=const(NavPrimary),width=const(6.dp))
            val endpoints=rememberGeoJsonSource(GeoJsonData.JsonString(MapRouteGeoJson.endpoints(ui.selectedOrigin?.coordinate,ui.selectedDestination?.coordinate)))
            CircleLayer(id="trip-endpoints",source=endpoints,radius=const(8.dp),color=const(NavPrimary),strokeColor=const(Color.White),strokeWidth=const(2.dp))
            val inspection=rememberGeoJsonSource(GeoJsonData.JsonString(MapRouteGeoJson.endpoints(inspectPoint,null)))
            CircleLayer(id="inspected-maneuver",source=inspection,radius=const(10.dp),color=const(NavTertiary),strokeColor=const(Color.White),strokeWidth=const(2.dp))
            val location=rememberGeoJsonSource(GeoJsonData.JsonString(MapRouteGeoJson.endpoints(if(permission)ui.snapshot.userLocation else null,null)))
            val radius=(accuracyMeters?.div(camera.metersPerDpAtTarget))?.takeIf{it.isFinite()}?.coerceIn(7.0,120.0) ?: 7.0
            CircleLayer(id="location-uncertainty",source=location,radius=const(radius.dp),color=const(NavSecondary.copy(alpha=.15f)))
            CircleLayer(id="current-position",source=location,radius=const(7.dp),color=const(if(!ui.currentLocationUsable)Color.Gray else NavSecondary),strokeColor=const(Color.White),strokeWidth=const(2.dp))
        }}
        Column(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top=80.dp,end=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            MapControlButton(onClick={val fix=ui.snapshot.userLocation;if(!permission)onFeedback("Enable location access in Android settings to center the map.")else if(fix==null||!ui.currentLocationUsable)onFeedback("Waiting for a fresh location. You can choose a starting place manually.")else{following=true;scope.launch{move(camera.position.copy(target=Position(latitude=fix.latitude,longitude=fix.longitude),zoom=17.5))}}}){Icon(Icons.Rounded.MyLocation,if(following)"Following current location"else"Recenter on current location")}
            MapControlButton(enabled=!loading&&!failed,onClick={following=false;scope.launch{move(camera.position.copy(bearing=0.0))}}){Icon(Icons.Rounded.Explore,"Orient map north")}
            MapControlButton(enabled=!loading&&!failed,onClick={perspective=!perspective;scope.launch{move(camera.position.copy(tilt=if(perspective)35.0 else 0.0))}}){Text(if(perspective)"2D"else"3D")}
            if(ui.snapshot.route!=null)MapControlButton(enabled=!loading&&!failed,onClick={fitRoute()}){Icon(Icons.Rounded.ZoomOutMap,"Fit route")}
        }
        if(!connection&&!loading&&!failed)Text("No map connection. Cached tiles may remain visible; local places are still available.",Modifier.align(Alignment.TopStart).padding(top=132.dp,start=16.dp,end=80.dp).background(MaterialTheme.colorScheme.surface).padding(12.dp))
        if(loading)Text("Loading map…",Modifier.align(Alignment.Center).background(MaterialTheme.colorScheme.surface).padding(12.dp))
        if(failed)NavGateGlass(Modifier.align(Alignment.Center).padding(24.dp)){Text("Map unavailable. Search and saved places remain available.");Button(onClick={failed=false;loading=true;retry++}){Text("Retry map")}}
    }
}
