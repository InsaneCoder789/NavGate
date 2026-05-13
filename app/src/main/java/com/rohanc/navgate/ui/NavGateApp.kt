package com.rohanc.navgate.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rohanc.navgate.ar.ArAvailabilityState
import com.rohanc.navgate.ar.ArCoreSupport
import com.rohanc.navgate.model.Coordinate
import com.rohanc.navgate.model.PlaceSearchResult
import com.rohanc.navgate.model.TravelProfile
import com.rohanc.navgate.navigation.PresentationMode
import com.rohanc.navgate.ui.ar.*
import com.rohanc.navgate.ui.components.*
import com.rohanc.navgate.ui.location.BindLocationTracking
import com.rohanc.navgate.ui.location.hasLocationPermission
import com.rohanc.navgate.ui.map.MapRouteGeoJson
import com.rohanc.navgate.ui.state.AppTab
import com.rohanc.navgate.ui.state.CityMode
import com.rohanc.navgate.ui.state.NavGateUiState
import com.rohanc.navgate.ui.state.NavGateViewModel
import com.rohanc.navgate.ui.theme.NavGateTheme
import com.rohanc.navgate.ui.theme.NavPrimary
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import io.github.dellisd.spatialk.geojson.Position

@Composable
fun NavGateApp() {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val application = context.applicationContext as Application
    val viewModel: NavGateViewModel = viewModel(factory = NavGateViewModel.factory(application))
    val arCoreSupport = remember { ArCoreSupport() }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var arState by remember { mutableStateOf<ArAvailabilityState>(ArAvailabilityState.Checking) }
    var hasLocationPermissionState by remember { mutableStateOf(hasLocationPermission(context)) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        hasLocationPermissionState = grants.values.any { it }
    }

    LaunchedEffect(activity) {
        if (activity != null) {
            arCoreSupport.checkAvailability(activity) { state ->
                arState = state
            }
        }
    }

    BindLocationTracking(enabled = hasLocationPermissionState) { coordinate, heading, fixAgeMillis ->
        viewModel.onLocationSample(coordinate, heading, fixAgeMillis)
    }

    NavGateTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (uiState.showOnboarding) {
                GoogleOnboardingScreen(onContinue = viewModel::dismissOnboarding)
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    VoiceGuidance(
                        enabled = uiState.snapshot.isNavigating,
                        utteranceKey = "${uiState.snapshot.currentStepIndex}-${uiState.snapshot.isRerouting}",
                        message = when {
                            uiState.snapshot.isRerouting -> "Rerouting. ${uiState.snapshot.currentInstruction}"
                            uiState.snapshot.shouldSpeakInstruction -> uiState.snapshot.currentInstruction
                            else -> null
                        },
                    )

                    NavGateHome(
                        uiState = uiState,
                        arState = arState,
                        hasCameraPermission = hasCameraPermission,
                        onSearchChanged = viewModel::updateSearch,
                        onSelectOrigin = viewModel::selectOrigin,
                        onSelectDestination = viewModel::selectDestination,
                        onFocusPlace = viewModel::focusPlace,
                        onDismissFocusedPlace = viewModel::dismissFocusedPlace,
                        onStartNavigation = viewModel::startNavigation,
                        onStopNavigation = viewModel::stopNavigation,
                        onTabSelected = viewModel::selectTab,
                        onTravelProfileChanged = viewModel::setTravelProfile,
                        onToggleSaved = viewModel::toggleSaved,
                        onSwitchToAr = {
                            if (!hasCameraPermission) {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            } else {
                                viewModel.switchMode(PresentationMode.AR_ASSIST)
                            }
                        },
                        onRequestLocationPermission = {
                            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }
                    )

                    // AR Overlay (Full Screen when active)
                    AnimatedVisibility(
                        visible = uiState.snapshot.presentationMode == PresentationMode.AR_ASSIST && hasCameraPermission,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        ArAssistScreen(
                            uiState = uiState,
                            onSwitchToMap = { viewModel.switchMode(PresentationMode.MAP) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NavGateHome(
    uiState: NavGateUiState,
    arState: ArAvailabilityState,
    hasCameraPermission: Boolean,
    onSearchChanged: (String) -> Unit,
    onSelectOrigin: (PlaceSearchResult) -> Unit,
    onSelectDestination: (PlaceSearchResult) -> Unit,
    onFocusPlace: (PlaceSearchResult) -> Unit,
    onDismissFocusedPlace: () -> Unit,
    onStartNavigation: () -> Unit,
    onStopNavigation: () -> Unit,
    onTabSelected: (AppTab) -> Unit,
    onTravelProfileChanged: (TravelProfile) -> Unit,
    onToggleSaved: (PlaceSearchResult) -> Unit,
    onSwitchToAr: () -> Unit,
    onRequestLocationPermission: () -> Unit,
) {
    val target =
        when {
            uiState.snapshot.isNavigating -> uiState.snapshot.userLocation ?: uiState.selectedDestination?.coordinate ?: uiState.selectedOrigin?.coordinate
            uiState.isPreviewVisible -> uiState.selectedDestination?.coordinate ?: uiState.selectedOrigin?.coordinate ?: uiState.snapshot.userLocation
            uiState.focusedPlace != null -> uiState.focusedPlace.coordinate
            uiState.selectedDestination != null -> uiState.selectedDestination.coordinate
            uiState.snapshot.userLocation != null -> uiState.snapshot.userLocation
            else -> defaultCoordinateFor(uiState.cityMode)
        } ?: defaultCoordinateFor(uiState.cityMode)
    val zoom =
        when {
            uiState.snapshot.isNavigating -> 17.0
            uiState.isPreviewVisible -> 14.8
            uiState.focusedPlace != null || uiState.selectedDestination != null -> 15.7
            else -> 12.8
        }
    val cameraState = key(
        target.latitude,
        target.longitude,
        zoom,
        uiState.focusedPlace?.id,
        uiState.selectedDestination?.id,
        uiState.snapshot.isNavigating,
        uiState.isPreviewVisible,
    ) {
        rememberCameraState(
            firstPosition = CameraPosition(
                target = Position(latitude = target.latitude, longitude = target.longitude),
                zoom = zoom,
            ),
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            cameraState = cameraState,
            baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty"),
            options = MapOptions(
                renderOptions = RenderOptions(renderMode = RenderOptions.RenderMode.TextureView),
                ornamentOptions = OrnamentOptions(padding = PaddingValues(top = 120.dp, bottom = 220.dp)),
            ),
        ) {
            val routeSource = rememberGeoJsonSource(GeoJsonData.JsonString(MapRouteGeoJson.lineString(uiState.snapshot.route)))
            val endpointSource = rememberGeoJsonSource(
                GeoJsonData.JsonString(
                    MapRouteGeoJson.endpoints(uiState.snapshot.origin, uiState.snapshot.destination),
                ),
            )
            LineLayer(
                id = "active-route",
                source = routeSource,
                color = const(NavPrimary),
                width = const(6.dp),
            )
            CircleLayer(
                id = "route-endpoints",
                source = endpointSource,
                radius = const(8.dp),
                color = const(NavPrimary),
                strokeColor = const(Color.White),
                strokeWidth = const(2.dp),
            )
        }

        // Top HUD
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
        ) {
            if (uiState.snapshot.isNavigating) {
                GoogleTurnBanner(
                    distanceLabel = formatDistance(uiState.snapshot.distanceToNextStep),
                    instruction = uiState.snapshot.currentInstruction,
                    nextHint = uiState.snapshot.nextInstructionHint
                )
            } else {
                GoogleSearchBox(
                    query = uiState.searchQuery,
                    onQueryChange = onSearchChanged,
                    onMenuClick = { /* TODO */ },
                    onProfileClick = { /* TODO */ },
                    modifier = Modifier.padding(top = 8.dp)
                )
                RoutePlannerRow(
                    originTitle = uiState.selectedOrigin?.title ?: if (uiState.snapshot.userLocation != null) "Your location" else defaultOriginLabelFor(uiState.cityMode),
                    destinationTitle = uiState.selectedDestination?.title ?: "Choose destination",
                    onOriginClick = { onTabSelected(AppTab.Go) },
                    onDestinationClick = { onTabSelected(AppTab.Go) },
                )
                QuickAccessChips(
                    selectedCategory = null,
                    onCategoryClick = { onSearchChanged(it) }
                )
            }
        }

        // Right side controls
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MapControlIconButton(icon = Icons.Rounded.Layers)
            MapControlIconButton(icon = Icons.Rounded.Explore)
            if (uiState.snapshot.isNavigating && arState == ArAvailabilityState.Supported) {
                MapControlIconButton(
                    icon = Icons.Rounded.CameraAlt,
                    containerColor = NavPrimary,
                    contentColor = Color.Black,
                    onClick = onSwitchToAr
                )
            }
            MapControlIconButton(
                icon = Icons.Rounded.MyLocation,
                contentColor = NavPrimary,
                onClick = onRequestLocationPermission
            )
        }

        // Bottom HUD
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
        ) {
            AnimatedContent(
                targetState = uiState,
                transitionSpec = {
                    (slideInVertically { it } + fadeIn()).togetherWith(slideOutVertically { it } + fadeOut())
                },
                label = "BottomHUD"
            ) { state ->
                when {
                    state.snapshot.isNavigating -> {
                        GoogleNavigationBottomBar(
                            etaLabel = formatEta(state.snapshot.etaSeconds),
                            distanceLabel = formatDistance(state.snapshot.distanceToNextStep),
                            arrivalLabel = formatArrivalTime(state.snapshot.etaSeconds),
                            onStopNavigation = onStopNavigation
                        )
                    }
                    state.searchQuery.isNotBlank() && state.focusedPlace == null && !state.isPreviewVisible -> {
                        GoogleSearchResultsList(
                            places = state.places,
                            onPlaceClick = onFocusPlace,
                            onDirectionsClick = onSelectDestination,
                            isLoading = state.isLoadingPlaces,
                            modifier = Modifier.fillMaxHeight(0.56f)
                        )
                    }
                    state.isPreviewVisible && state.preview != null -> {
                        GoogleRoutePreviewSheet(
                            preview = state.preview,
                            travelProfile = state.travelProfile,
                            onStartNavigation = onStartNavigation,
                            onTravelProfileChanged = onTravelProfileChanged,
                            modifier = Modifier.fillMaxHeight(0.38f),
                        )
                    }
                    state.focusedPlace != null -> {
                        GooglePlaceProfileSheet(
                            place = state.focusedPlace,
                            isSaved = state.savedPlaces.any { it.id == state.focusedPlace.id },
                            onDismiss = onDismissFocusedPlace,
                            onToggleSaved = { onToggleSaved(state.focusedPlace) },
                            onSelectOrigin = { onSelectOrigin(state.focusedPlace) },
                            onSelectDestination = { onSelectDestination(state.focusedPlace) },
                            modifier = Modifier.fillMaxHeight(0.48f),
                        )
                    }
                    state.activeTab == AppTab.Saved -> {
                        GoogleCollectionsSheet(
                            title = "Saved Places",
                            subtitle = "Places you've bookmarked for later",
                            places = state.savedPlaces,
                            onPlaceClick = onFocusPlace,
                            onDirectionsClick = onSelectDestination,
                            isLoading = state.isLoadingPlaces,
                            modifier = Modifier.fillMaxHeight(0.56f),
                        )
                    }
                    state.activeTab == AppTab.Recents -> {
                        GoogleCollectionsSheet(
                            title = "Recent Activity",
                            subtitle = "Places you've visited or searched for",
                            places = state.recentPlaces,
                            onPlaceClick = onFocusPlace,
                            onDirectionsClick = onSelectDestination,
                            isHistory = true
                            ,
                            isLoading = state.isLoadingPlaces,
                            modifier = Modifier.fillMaxHeight(0.56f),
                        )
                    }
                    else -> {
                        GoogleCollectionsSheet(
                            title = if (state.activeTab == AppTab.Go) "Choose a destination" else if (state.cityMode == CityMode.Mumbai) "Explore Mumbai" else "Explore Bhubaneswar",
                            subtitle = if (state.activeTab == AppTab.Go) "Pick a place to preview a route before starting." else "Tap a place to open details or preview directions.",
                            places = state.places.take(12),
                            onPlaceClick = onFocusPlace,
                            onDirectionsClick = onSelectDestination,
                            isLoading = state.isLoadingPlaces,
                            modifier = Modifier.fillMaxHeight(0.56f),
                            footer = {
                                ExploreBottomNav(
                                    activeTab = state.activeTab,
                                    onTabSelected = onTabSelected
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArAssistScreen(
    uiState: NavGateUiState,
    onSwitchToMap: () -> Unit,
) {
    val headingState = rememberHeadingState()
    val alignment = alignmentStatus(uiState.snapshot, headingState.value)

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(modifier = Modifier.fillMaxSize())
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent, Color.Black.copy(alpha = 0.7f))
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.6f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "AR Guidance",
                            style = MaterialTheme.typography.labelSmall,
                            color = NavPrimary
                        )
                        Text(
                            uiState.snapshot.currentInstruction,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                    }
                    IconButton(
                        onClick = onSwitchToMap,
                        modifier = Modifier.background(Color.White.copy(alpha = 0.1f), CircleShape)
                    ) {
                        Icon(Icons.Rounded.Map, contentDescription = "Map", tint = Color.White)
                    }
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                RouteRibbon(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    alignment = alignment,
                    distanceMeters = uiState.snapshot.distanceToNextStep
                )
                Spacer(Modifier.height(24.dp))
                GlassAlignmentRing(alignment = alignment)
                Spacer(Modifier.height(16.dp))
                Text(
                    alignment.label,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.6f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ConfidenceBadge(uiState.snapshot.guidanceConfidence)
                    Text(
                        formatDistance(uiState.snapshot.distanceToNextStep),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun MapControlIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    containerColor: Color = Color(0xCC1D1F24),
    contentColor: Color = Color.White.copy(alpha = 0.8f),
    onClick: () -> Unit = {}
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
        color = containerColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
        shadowElevation = 4.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun ExploreBottomNav(
    activeTab: AppTab,
    onTabSelected: (AppTab) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF1D1F24),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        shadowElevation = 16.dp
    ) {
        NavigationBar(
            containerColor = Color.Transparent,
            tonalElevation = 0.dp
        ) {
            AppTab.entries.forEach { tab ->
                val selected = activeTab == tab
                NavigationBarItem(
                    selected = selected,
                    onClick = { onTabSelected(tab) },
                    icon = {
                        Icon(
                            if (selected) tab.iconSelected() else tab.iconUnselected(),
                            contentDescription = tab.name
                        )
                    },
                    label = { Text(tab.name) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = NavPrimary,
                        selectedTextColor = NavPrimary,
                        indicatorColor = NavPrimary.copy(alpha = 0.1f),
                        unselectedIconColor = Color.White.copy(alpha = 0.6f),
                        unselectedTextColor = Color.White.copy(alpha = 0.6f)
                    )
                )
            }
        }
    }
}

private fun AppTab.iconSelected() = when (this) {
    AppTab.Explore -> Icons.Rounded.Explore
    AppTab.Go -> Icons.Rounded.Navigation
    AppTab.Saved -> Icons.Rounded.Bookmark
    AppTab.Recents -> Icons.Rounded.History
}

private fun AppTab.iconUnselected() = when (this) {
    AppTab.Explore -> Icons.Rounded.Explore
    AppTab.Go -> Icons.Rounded.Navigation
    AppTab.Saved -> Icons.Rounded.BookmarkBorder
    AppTab.Recents -> Icons.Rounded.History
}

private fun formatDistance(distanceMeters: Double): String =
    if (distanceMeters >= 1000) "%.1f km".format(distanceMeters / 1000.0) else "${distanceMeters.toInt()} m"

private fun formatEta(seconds: Double): String {
    val minutes = kotlin.math.ceil(seconds / 60.0).toInt().coerceAtLeast(1)
    return if (minutes < 60) "$minutes min" else "${minutes / 60} hr ${minutes % 60} min"
}

private fun formatArrivalTime(seconds: Double): String {
    val totalMinutes = kotlin.math.ceil(seconds / 60.0).toInt()
    val calendar = java.util.Calendar.getInstance().apply { add(java.util.Calendar.MINUTE, totalMinutes) }
    val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
    val minute = calendar.get(java.util.Calendar.MINUTE).toString().padStart(2, '0')
    return "$hour:$minute"
}

private fun defaultCoordinateFor(cityMode: CityMode): Coordinate =
    when (cityMode) {
        CityMode.Mumbai -> Coordinate(19.0760, 72.8777)
        CityMode.KiitBeta -> Coordinate(20.3534, 85.8195)
    }

private fun defaultOriginLabelFor(cityMode: CityMode): String =
    when (cityMode) {
        CityMode.Mumbai -> "Mumbai center"
        CityMode.KiitBeta -> "Patia campus"
    }
