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
import com.rohanc.navgate.ui.map.VehicleModel
import com.rohanc.navgate.ui.map.VehicleColor
import com.rohanc.navgate.ui.map.TrackInput
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import com.rohanc.navgate.data.local.NavGateServices
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

/** The MapLibre map with route, markers, vehicle and map controls. */
@Composable internal fun NativeMap(ui:NavGateUiState,dark:Boolean,permission:Boolean,active:Boolean,sessionId:String?,accuracyMeters:Double?,inspectPoint:Coordinate?,onFeedback:(String)->Unit,onRequestLocation:()->Unit={},screenIsExplore:Boolean=false,bottomInsetDp:Float=0f,topInsetDp:Float=80f,controlsBottomDp:Float?=null,showPins:Boolean=false,onOpenLayers:()->Unit,onToggle3d:()->Unit={},nextTurn:Coordinate?=null,live:Boolean=false,motion:StateFlow<MotionEstimate?>?=null,alongMeters:Double=0.0,manualPins:List<RoutePin> = emptyList(),onPin:((GeoPoint)->Unit)?=null,showControls:Boolean=true,routeGeometry:com.rohanc.navgate.core.RouteGeometry?=null,trackInput:TrackInput?=null,onPlace:(PlaceSearchResult)->Unit){
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
    val flat=!ui.frontendPreferences.buildings3d
    // 3D shows the buildings standing up: the map is tipped back while browsing, and more so while guiding. 2D stays top-down.
    val browseTilt=if(flat)0.0 else 50.0
    val guideTilt=if(flat)0.0 else 60.0
    // With no connection (or when the user chose to always draw offline maps) the active downloaded map pack replaces the live tile server.
    val appContext=androidx.compose.ui.platform.LocalContext.current
    val mapPacks=remember(appContext){com.rohanc.navgate.data.local.NavGateServices.get(appContext).mapPacks}
    val activePack by mapPacks.activeId.collectAsState()
    val alwaysOffline by mapPacks.alwaysOffline.collectAsState()
    val styleVersion by mapPacks.styleVersion.collectAsState()
    val useOffline=!connection||alwaysOffline
    val style=remember(dark,flat,useOffline,activePack,styleVersion){
        val asset="navgate-map-${if(dark)"dark" else "daylight"}${if(flat)"-flat" else ""}.json"
        BaseStyle.Uri((if(useOffline)mapPacks.offlineStyleUrl(asset) else null) ?: "asset://$asset")
    }
    val options=remember(bottomInsetDp){MapOptions(renderOptions=RenderOptions(renderMode=RenderOptions.RenderMode.TextureView),ornamentOptions=OrnamentOptions(isLogoEnabled=false,isAttributionEnabled=false,padding=PaddingValues(bottom=(bottomInsetDp+4f).dp,start=12.dp,end=12.dp),logoAlignment=Alignment.BottomStart,attributionAlignment=Alignment.BottomCenter,isScaleBarEnabled=false))}
    // Switching to 3D frames the buildings: zoom in to where they have height and tip the map back; 2D lays it flat again.
    LaunchedEffect(flat,loading){if(!loading&&!active&&camera.position.zoom>=12.0)runCatching{camera.animateTo(camera.position.copy(tilt=browseTilt,zoom=if(flat)camera.position.zoom else maxOf(camera.position.zoom,16.3)),650.milliseconds)}}
    suspend fun move(next:CameraPosition){if(ui.frontendPreferences.reduceMotion)camera.position=next else camera.animateTo(next,400.milliseconds)}
    fun fitRoute(){val points=ui.snapshot.route?.pathCoordinates.orEmpty();if(points.isEmpty())return
        val south=points.minOf{it.latitude};val north=points.maxOf{it.latitude};val west=points.minOf{it.longitude};val east=points.maxOf{it.longitude}
        following=false
        scope.launch{camera.animateTo(BoundingBox(Position(latitude=south,longitude=west),Position(latitude=north,longitude=east)),bearing=0.0,tilt=0.0,padding=PaddingValues(start=24.dp,end=72.dp,top=(topInsetDp+28f).dp,bottom=(bottomInsetDp+24f).dp),duration=if(ui.frontendPreferences.reduceMotion)1.milliseconds else 400.milliseconds)}
    }
    // Frame the results (and the user) in the part of the map the list does not cover.
    LaunchedEffect(showPins,ui.places,(bottomInsetDp/60f).toInt(),(topInsetDp/40f).toInt()){
        if(!showPins)return@LaunchedEffect
        // Fit what is near the user so the results can actually be read; only when nothing is near does the frame grow to the far ones.
        val found=ui.places.mapNotNull{it.coordinate}.take(25)
        val here=ui.displayLocation
        val near=if(here==null)found else found.filter{hypot((it.latitude-here.latitude)*111000,(it.longitude-here.longitude)*111000*cos(Math.toRadians(here.latitude)))<=30_000}
        val points=(if(near.isNotEmpty())near else found)+(if(near.isNotEmpty())listOfNotNull(here) else emptyList())
        if(points.isEmpty())return@LaunchedEffect
        following=false
        if(points.size==1){move(camera.position.copy(target=Position(latitude=points[0].latitude,longitude=points[0].longitude),zoom=16.0));return@LaunchedEffect}
        val south=points.minOf{it.latitude};val north=points.maxOf{it.latitude};val west=points.minOf{it.longitude};val east=points.maxOf{it.longitude}
        runCatching{camera.animateTo(BoundingBox(Position(latitude=south,longitude=west),Position(latitude=north,longitude=east)),bearing=0.0,tilt=0.0,
            padding=PaddingValues(start=40.dp,end=40.dp,top=(topInsetDp+48f).dp,bottom=(bottomInsetDp+48f).dp),duration=if(ui.frontendPreferences.reduceMotion)1.milliseconds else 500.milliseconds)}
    }
    LaunchedEffect(inspectPoint){
        if(inspectPoint!=null){following=false;move(camera.position.copy(target=Position(latitude=inspectPoint.latitude,longitude=inspectPoint.longitude),zoom=18.0))}
        else if(active)following=true
    }
    LaunchedEffect(active,sessionId){if(active&&followingSession!=sessionId){followingSession=sessionId;following=true}}
    LaunchedEffect(camera.moveReason){if(camera.moveReason==CameraMoveReason.GESTURE)following=false}
    LaunchedEffect(ui.focusedPlace?.id,ui.cityMode,(bottomInsetDp/40f).toInt()){val target=ui.focusedPlace?.coordinate
        if(target!=null){following=false;move(camera.position.copy(target=Position(latitude=target.latitude,longitude=target.longitude),zoom=16.5,padding=PaddingValues(top=topInsetDp.dp,bottom=bottomInsetDp.dp)))}
    }
    // Jump to the user as soon as there is a position (and again when permission is first granted).
    LaunchedEffect(permission){if(permission)centeredOnLocation=false}
    // Back on the home map after cancelling a route or stopping a trip: go back to where you are instead of staying on the old route.
    // Trip ended or route cancelled: fly straight back to where you are, flat and north-up. The request is remembered until it
    // has actually run, so other map changes at the same moment (the screen switching, the old route clearing) cannot cancel
    // it half-way and leave the camera on the old route.
    var wasActive by remember{mutableStateOf(false)}
    var goHome by remember{mutableStateOf(false)}
    val homeFix=ui.displayLocation ?: ui.snapshot.userLocation
    LaunchedEffect(active){if(wasActive&&!active)goHome=true;wasActive=active}
    LaunchedEffect(screenIsExplore,ui.snapshot.route==null){if(screenIsExplore&&!active&&ui.snapshot.route==null)goHome=true}
    LaunchedEffect(goHome,homeFix==null,active,permission){
        if(!goHome||active||!permission)return@LaunchedEffect
        val here=homeFix ?: return@LaunchedEffect
        centeredOnLocation=true;following=true
        move(camera.position.copy(target=Position(latitude=here.latitude,longitude=here.longitude),zoom=16.0,bearing=0.0,tilt=browseTilt,padding=PaddingValues(top=topInsetDp.dp,bottom=bottomInsetDp.dp)))
        goHome=false
    }
    // The route overview belongs to the directions screens; on the home map it would pull the camera back to a finished route.
    LaunchedEffect(ui.snapshot.route,(bottomInsetDp/60f).toInt()){if(!active&&!screenIsExplore&&!goHome)fitRoute()}
    LaunchedEffect(ui.snapshot.userLocation,ui.displayLocation,following,active,permission,loading,centeredOnLocation){val fix=ui.snapshot.userLocation
        val shown=ui.displayLocation ?: fix
        if(!active && !loading && !centeredOnLocation && ui.focusedPlace==null && (ui.snapshot.route==null||screenIsExplore) && permission && shown!=null){
            centeredOnLocation=true;val accuracy=ui.displayAccuracy ?: 50.0
            move(camera.position.copy(target=Position(latitude=shown.latitude,longitude=shown.longitude),zoom=if(accuracy<=150)16.0 else if(accuracy<=2_000)14.0 else 11.0,bearing=0.0,tilt=browseTilt))
        }
        if(motion==null&&active&&following&&permission&&ui.currentLocationUsable&&fix!=null&&ui.snapshot.locationConfidence.label!=com.rohanc.navgate.navigation.GuidanceConfidence.Low)move(camera.position.copy(target=Position(latitude=fix.latitude,longitude=fix.longitude),zoom=17.5))
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)){
        key(retry){MaplibreMap(modifier=Modifier.fillMaxSize(),baseStyle=style,options=options,cameraState=camera,onMapClick={position,_->
            if(onPin!=null){onPin(GeoPoint(position.latitude,position.longitude));ClickResult.Consume}else {
            val nearest=ui.places.filter{it.coordinate!=null}.minByOrNull{val c=it.coordinate!!;hypot((c.latitude-position.latitude)*111000,(c.longitude-position.longitude)*111000*cos(Math.toRadians(position.latitude)))}
            val c=nearest?.coordinate;val radius=(camera.metersPerDpAtTarget*24).takeIf{it.isFinite()}?.coerceIn(8.0,100.0) ?: 40.0
            if(c!=null&&hypot((c.latitude-position.latitude)*111000,(c.longitude-position.longitude)*111000*cos(Math.toRadians(position.latitude)))<radius){onPlace(nearest);ClickResult.Consume}else ClickResult.Pass
            }
        },onMapLoadFailed={failed=true;loading=false},onMapLoadFinished={failed=false;loading=false}){
            val points=(if(showPins)ui.places else emptyList()).mapNotNull{it.coordinate}.joinToString(","){"""{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[${it.longitude},${it.latitude}]}}"""}
            val places=rememberGeoJsonSource(GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[$points]}"""))
            CircleLayer(id="campus-places",source=places,radius=const(9.dp),color=const(Color(0xFFD93025)),strokeColor=const(Color.White),strokeWidth=const(2.5.dp))
            CircleLayer(id="campus-places-dot",source=places,radius=const(3.5.dp),color=const(Color.White))
            // While guiding, the road behind is dimmed and only the road ahead is bright. Cut every ~8 m, not on every 10 Hz update.
            // The bright line starts where the vehicle is drawn (last fix plus the distance covered since), not at the last fix.
            val alongNow=if(trackInput!=null)trackInput.alongShapeMeters+trackInput.speedMps*((android.os.SystemClock.elapsedRealtime()-trackInput.fixElapsedMillis)/1000.0).coerceIn(0.0,8.0) else 0.0
            val alongStep=if(active&&routeGeometry!=null&&trackInput!=null)(alongNow/4.0).toInt() else -1
            val progress=remember(routeGeometry,alongStep){if(alongStep>=0)MapRouteGeoJson.progressSplit(routeGeometry,alongStep*4.0) else null}
            val route=rememberGeoJsonSource(GeoJsonData.JsonString(progress?.second ?: MapRouteGeoJson.lineString(if(screenIsExplore)null else ui.snapshot.route)))
            val routeDone=rememberGeoJsonSource(GeoJsonData.JsonString(progress?.first ?: """{"type":"FeatureCollection","features":[]}"""))
            val routeColor=if(dark)NavRouteDark else NavRouteLight
            LineLayer(id="walking-route-done",source=routeDone,color=const(if(dark)Color(0xFF5F6B7A) else Color(0xFFB0B6C0)),width=const(7.dp))
            LineLayer(id="walking-route-casing",source=route,color=const(if(dark)Color(0xFF0B1B33) else Color.White),width=const(11.dp))
            LineLayer(id="walking-route",source=route,color=const(routeColor),width=const(7.dp))
            val nextTurnSource=rememberGeoJsonSource(GeoJsonData.JsonString(MapRouteGeoJson.endpoints(if(active)nextTurn else null,null)))
            CircleLayer(id="next-turn-ring",source=nextTurnSource,radius=const(13.dp),color=const(Color.Transparent),strokeColor=const(routeColor),strokeWidth=const(4.dp))
            CircleLayer(id="next-turn-dot",source=nextTurnSource,radius=const(5.dp),color=const(Color.White))
            val customPoints=manualPins.map{Coordinate(it.point.latitude,it.point.longitude)}
            val customLine=if(customPoints.size>=2)"""{"type":"LineString","coordinates":[${customPoints.joinToString(","){"[${it.longitude},${it.latitude}]"}}]}"""else """{"type":"FeatureCollection","features":[]}"""
            val manualLine=rememberGeoJsonSource(GeoJsonData.JsonString(customLine))
            LineLayer(id="manual-itinerary-line",source=manualLine,color=const(NavMapAccent.copy(alpha=.6f)),width=const(3.dp))
            val manualDots=rememberGeoJsonSource(GeoJsonData.JsonString(ManualRouteGeoJson.points(manualPins)))
            CircleLayer(id="manual-itinerary-pins",source=manualDots,radius=const(8.dp),color=const(NavMapAccent),strokeColor=const(Color.White),strokeWidth=const(2.dp))
            SymbolLayer(id="manual-itinerary-labels",source=manualDots,textField=format(span(feature.get("label").asString())),
                textFont=const(listOf("Noto Sans Regular")),textSize=const(14.sp),textColor=const(NavMapAccent),
                textHaloColor=const(if(dark)Color.Black else Color.White),textHaloWidth=const(1.dp),textRadialOffset=const(16.sp),textAnchor=const(org.maplibre.compose.expressions.value.SymbolAnchor.Bottom))
            val tripOrigin=rememberGeoJsonSource(GeoJsonData.JsonString(MapRouteGeoJson.endpoints(if(active)null else ui.selectedOrigin?.coordinate,null)))
            CircleLayer(id="trip-origin",source=tripOrigin,radius=const(8.dp),color=const(Color(0xFF1A73E8)),strokeColor=const(Color.White),strokeWidth=const(3.dp))
            // Destination and the place being looked at share one red marker.
            val pinned=ui.selectedDestination?.coordinate ?: ui.focusedPlace?.coordinate
            val destination=rememberGeoJsonSource(GeoJsonData.JsonString(MapRouteGeoJson.endpoints(null,pinned)))
            CircleLayer(id="trip-destination",source=destination,radius=const(11.dp),color=const(Color(0xFFD93025)),strokeColor=const(Color.White),strokeWidth=const(3.dp))
            CircleLayer(id="trip-destination-dot",source=destination,radius=const(4.dp),color=const(Color.White))
            val inspection=rememberGeoJsonSource(GeoJsonData.JsonString(MapRouteGeoJson.endpoints(inspectPoint,null)))
            CircleLayer(id="inspected-maneuver",source=inspection,radius=const(10.dp),color=const(NavMapAccent),strokeColor=const(Color.White),strokeWidth=const(2.dp))
            val shownLocation=ui.displayLocation ?: ui.snapshot.userLocation
            val location=rememberGeoJsonSource(GeoJsonData.JsonString(MapRouteGeoJson.endpoints(if(permission)shownLocation else null,null)))
            val radius=((ui.displayAccuracy ?: accuracyMeters)?.div(camera.metersPerDpAtTarget))?.takeIf{it.isFinite()}?.coerceIn(7.0,120.0) ?: 7.0
            if(!(active&&motion!=null)){
                CircleLayer(id="location-uncertainty",source=location,radius=const(radius.dp),color=const(NavMapPosition.copy(alpha=.15f)))
                CircleLayer(id="current-position",source=location,radius=const(7.dp),color=const(NavMapPosition),strokeColor=const(Color.White),strokeWidth=const(2.dp))
            }
        }}
        if(active&&motion!=null){
            VehicleMarker(camera,motion,routeGeometry,trackInput,ui.travelProfile==TravelProfile.Driving,following,if(live)58.0 else guideTilt,if(dark)NavRouteDark else NavRouteLight,VehicleModel.of(ui.frontendPreferences.vehicleModel),VehicleColor.of(ui.frontendPreferences.vehicleColor).paint,if(live)0.52f else 0.34f)
        }
        // Guidance gets the full set of map buttons; the home map keeps only the 3D button; everywhere else the map is left clear.
        if((showControls||screenIsExplore)&&(controlsBottomDp==null || controlsBottomDp<viewport.screenHeightDp*0.6f))Column(Modifier.align(if(controlsBottomDp!=null)Alignment.BottomEnd else Alignment.TopEnd).let{if(controlsBottomDp!=null)it.navigationBarsPadding().padding(bottom=controlsBottomDp.dp,end=16.dp) else it.statusBarsPadding().padding(top=topInsetDp.dp,end=16.dp)},verticalArrangement=Arrangement.spacedBy(8.dp)){
            if(showControls)MapControlButton(onClick={val fix=ui.displayLocation ?: ui.snapshot.userLocation;if(!permission)onRequestLocation()else if(fix==null)onFeedback("Looking for your location… You can also choose a starting place manually.")else{following=true;scope.launch{move(camera.position.copy(target=Position(latitude=fix.latitude,longitude=fix.longitude),zoom=maxOf(camera.position.zoom,16.0),bearing=0.0))}}}){Icon(Icons.Rounded.MyLocation,if(following)"Following current location"else"Recenter on current location")}
            if(showControls)MapControlButton(onClick=onOpenLayers){Icon(Icons.Rounded.Layers,"Map details")}
            if(!showControls)MapControlButton(onClick=onToggle3d){Icon(if(flat)Icons.Rounded.LocationCity else Icons.Rounded.Map,if(flat)"Show 3D buildings" else "Show flat 2D map")}
            // (3D toggle removed: guidance tilts the map itself)
            if(showControls&&ui.snapshot.route!=null)MapControlButton(enabled=!loading&&!failed,onClick={fitRoute()}){Icon(Icons.Rounded.ZoomOutMap,"Fit route")}
        }
        if(!connection&&!loading&&!failed)Text(if(activePack!=null)"No connection. Drawing your saved offline map; local places are still available." else "No map connection. Cached tiles may remain visible; local places are still available.",Modifier.align(Alignment.TopStart).padding(top=132.dp,start=16.dp,end=80.dp).background(MaterialTheme.colorScheme.surface).padding(12.dp))
        if(loading)Text("Loading map…",Modifier.align(Alignment.Center).background(MaterialTheme.colorScheme.surface).padding(12.dp))
        if(failed)NavGateGlass(Modifier.align(Alignment.Center).padding(24.dp)){Text("Map unavailable. Search and saved places remain available.");Button(onClick={failed=false;loading=true;retry++}){Text("Retry map")}}
    }
}

