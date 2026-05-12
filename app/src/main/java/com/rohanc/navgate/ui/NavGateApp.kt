package com.rohanc.navgate.ui

import android.app.Application
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rohanc.navgate.R
import com.rohanc.navgate.ar.ArAvailabilityState
import com.rohanc.navgate.ar.ArCoreSupport
import com.rohanc.navgate.model.PlaceSearchResult
import com.rohanc.navgate.model.PlaceType
import com.rohanc.navgate.model.RouteHistoryEntry
import com.rohanc.navgate.navigation.GuidanceConfidence
import com.rohanc.navgate.navigation.PresentationMode
import com.rohanc.navgate.ui.ar.AlignmentLevel
import com.rohanc.navgate.ui.ar.CameraPreview
import com.rohanc.navgate.ui.ar.VoiceGuidance
import com.rohanc.navgate.ui.ar.alignmentStatus
import com.rohanc.navgate.ui.ar.rememberHeadingState
import com.rohanc.navgate.ui.components.ConfidenceBadge
import com.rohanc.navgate.ui.location.BindLocationTracking
import com.rohanc.navgate.ui.location.hasLocationPermission
import com.rohanc.navgate.ui.map.MapRouteGeoJson
import com.rohanc.navgate.ui.state.AppTab
import com.rohanc.navgate.ui.state.CityMode
import com.rohanc.navgate.ui.state.NavGateUiState
import com.rohanc.navgate.ui.state.NavGateViewModel
import com.rohanc.navgate.ui.state.RoutePreview
import com.rohanc.navgate.ui.theme.NavBackground
import com.rohanc.navgate.ui.theme.NavGateTheme
import com.rohanc.navgate.ui.theme.NavGlassStroke
import com.rohanc.navgate.ui.theme.NavHighConfidence
import com.rohanc.navgate.ui.theme.NavLowConfidence
import com.rohanc.navgate.ui.theme.NavMapGlow
import com.rohanc.navgate.ui.theme.NavMediumConfidence
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
import org.maplibre.spatialk.geojson.Position

private val QuickCategories =
    listOf(
        QuickCategory("Restaurants", Icons.Rounded.Restaurant, "cafeteria"),
        QuickCategory("Gas", Icons.Rounded.LocalGasStation, "commercial"),
        QuickCategory("Coffee", Icons.Rounded.Coffee, "cafe"),
        QuickCategory("Campus", Icons.Rounded.School, "kiit"),
    )

private data class QuickCategory(
    val label: String,
    val icon: ImageVector,
    val query: String,
)

@Composable
fun NavGateApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? ComponentActivity
    val application = context.applicationContext as Application
    val viewModel: NavGateViewModel = viewModel(factory = NavGateViewModel.factory(application))
    val arCoreSupport = remember { ArCoreSupport() }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var arState by remember { mutableStateOf<ArAvailabilityState>(ArAvailabilityState.Checking) }
    var hasLocationPermissionState by remember { mutableStateOf(hasLocationPermission(context)) }
    var showMenuSheet by remember { mutableStateOf(false) }
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
        } else {
            arState = ArAvailabilityState.Error("NavGate requires a ComponentActivity host.")
        }
    }

    LaunchedEffect(arState) {
        viewModel.updateProgress()
    }

    BindLocationTracking(enabled = hasLocationPermissionState) { coordinate, heading, fixAgeMillis ->
        viewModel.onLocationSample(coordinate, heading, fixAgeMillis)
    }

    NavGateTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            VoiceGuidance(
                enabled = uiState.snapshot.isNavigating,
                utteranceKey = "${uiState.snapshot.currentStepIndex}-${uiState.snapshot.isRerouting}",
                message =
                    when {
                        uiState.snapshot.isRerouting -> "Rerouting. ${uiState.snapshot.currentInstruction}"
                        uiState.snapshot.shouldSpeakInstruction -> uiState.snapshot.currentInstruction
                        else -> null
                    },
            )
            NavGateHome(
                uiState = uiState,
                arState = arState,
                hasCameraPermission = hasCameraPermission,
                hasLocationPermission = hasLocationPermissionState,
                showMenuSheet = showMenuSheet,
                onSearchChanged = viewModel::updateSearch,
                onOpenMenu = { showMenuSheet = true },
                onDismissMenu = { showMenuSheet = false },
                onSelectOrigin = viewModel::selectOrigin,
                onSelectDestination = viewModel::selectDestination,
                onStartNavigation = viewModel::startNavigation,
                onDismissOnboarding = viewModel::dismissOnboarding,
                onToggleSaved = viewModel::toggleSaved,
                onTabSelected = viewModel::selectTab,
                onTravelProfileChanged = viewModel::setTravelProfile,
                onCityModeChanged = viewModel::setCityMode,
                onKiitBetaAccessChanged = viewModel::setKiitBetaAccess,
                onSwitchToAr = {
                    if (!hasCameraPermission) {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    } else {
                        viewModel.switchMode(PresentationMode.AR_ASSIST)
                    }
                },
                onSwitchToMap = { viewModel.switchMode(PresentationMode.MAP) },
                onRequestLocationPermission = {
                    locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                },
            )
        }
    }
}

@Composable
private fun NavGateHome(
    uiState: NavGateUiState,
    arState: ArAvailabilityState,
    hasCameraPermission: Boolean,
    hasLocationPermission: Boolean,
    showMenuSheet: Boolean,
    onSearchChanged: (String) -> Unit,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onSelectOrigin: (PlaceSearchResult) -> Unit,
    onSelectDestination: (PlaceSearchResult) -> Unit,
    onStartNavigation: () -> Unit,
    onDismissOnboarding: () -> Unit,
    onToggleSaved: (PlaceSearchResult) -> Unit,
    onTabSelected: (AppTab) -> Unit,
    onTravelProfileChanged: (com.rohanc.navgate.model.TravelProfile) -> Unit,
    onCityModeChanged: (CityMode) -> Unit,
    onKiitBetaAccessChanged: (Boolean) -> Unit,
    onSwitchToAr: () -> Unit,
    onSwitchToMap: () -> Unit,
    onRequestLocationPermission: () -> Unit,
) {
    if (uiState.showOnboarding) {
        OnboardingScreen(onContinue = onDismissOnboarding)
        return
    }

    val canUseArBeta = uiState.cityMode == CityMode.KiitBeta && uiState.kiitBetaAccess
    if (uiState.snapshot.presentationMode == PresentationMode.AR_ASSIST && hasCameraPermission && arState == ArAvailabilityState.Supported) {
        ArAssistScreen(uiState = uiState, hasLocationPermission = hasLocationPermission, onSwitchToMap = onSwitchToMap)
        return
    }

    val target = uiState.snapshot.userLocation ?: uiState.selectedOrigin?.coordinate ?: uiState.selectedDestination?.coordinate
    val cameraState =
        rememberCameraState(
            firstPosition =
                CameraPosition(
                    target = Position(latitude = target?.latitude ?: 20.349884, longitude = target?.longitude ?: 85.807529),
                    zoom = if (uiState.snapshot.route == null) 15.5 else 16.6,
                ),
        )

    Box(modifier = Modifier.fillMaxSize().background(NavBackground)) {
        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            cameraState = cameraState,
            baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty"),
            options =
                MapOptions(
                    renderOptions = RenderOptions(renderMode = RenderOptions.RenderMode.TextureView),
                    ornamentOptions = OrnamentOptions(padding = PaddingValues(top = 120.dp, bottom = 220.dp)),
                ),
        ) {
            val routeSource = rememberGeoJsonSource(GeoJsonData.JsonString(MapRouteGeoJson.lineString(uiState.snapshot.route)))
            val endpointSource =
                rememberGeoJsonSource(
                    GeoJsonData.JsonString(
                        MapRouteGeoJson.endpoints(uiState.snapshot.origin, uiState.snapshot.destination),
                    ),
                )
            LineLayer(
                id = "active-route",
                source = routeSource,
                color = const(MaterialTheme.colorScheme.tertiary),
                width = const(6.dp),
            )
            CircleLayer(
                id = "route-endpoints",
                source = endpointSource,
                radius = const(8.dp),
                color = const(MaterialTheme.colorScheme.primary),
                strokeColor = const(MaterialTheme.colorScheme.surface),
                strokeWidth = const(2.dp),
            )
        }

        MapAtmosphere()

        if (showMenuSheet) {
            NavGateMenuSheet(
                cityMode = uiState.cityMode,
                kiitBetaAccess = uiState.kiitBetaAccess,
                onDismiss = onDismissMenu,
                onCityModeChanged = onCityModeChanged,
                onKiitBetaAccessChanged = onKiitBetaAccessChanged,
            )
        }

        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 18.dp)) {
            Column(
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppTitleRow()
                TopSearchShell(
                    query = uiState.searchQuery,
                    onSearchChanged = onSearchChanged,
                    onOpenMenu = onOpenMenu,
                )
                PlannerStatusRow(
                    origin = uiState.selectedOrigin?.title,
                    destination = uiState.selectedDestination?.title,
                )
                QuickCategoryRow(cityMode = uiState.cityMode, onCategorySelected = onSearchChanged)
                SearchResultsPanel(
                    query = uiState.searchQuery,
                    places = uiState.places,
                    onSelectOrigin = onSelectOrigin,
                    onSelectDestination = onSelectDestination,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ConfidenceBadge(confidence = uiState.snapshot.guidanceConfidence)
                    if (!hasLocationPermission) {
                        MiniPermissionPill(onRequestLocationPermission = onRequestLocationPermission)
                    }
                }
            }

            FloatingActionRail(
                modifier = Modifier.align(Alignment.BottomEnd).offset(y = (-128).dp),
                onCenterOnUser = onRequestLocationPermission,
            )

            Column(
                modifier = Modifier.align(Alignment.BottomCenter),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (uiState.isLoadingRoute) {
                    LoadingRouteCard()
                } else if (uiState.preview != null && uiState.isPreviewVisible) {
                    RoutePreviewSheet(
                        preview = uiState.preview,
                        travelProfile = uiState.travelProfile,
                        confidence = uiState.snapshot.guidanceConfidence.reason,
                        onStartNavigation = onStartNavigation,
                        onTravelProfileChanged = onTravelProfileChanged,
                        onOpenAr = onSwitchToAr,
                        canUseAr = canUseArBeta,
                    )
                } else if (uiState.snapshot.isNavigating) {
                    LiveGuidanceSheet(
                        instruction = uiState.snapshot.currentInstruction,
                        distanceLabel = formatDistance(uiState.snapshot.distanceToNextStep),
                        etaLabel = formatEta(uiState.snapshot.etaSeconds),
                        travelProfile = uiState.travelProfile,
                        isArrived = uiState.snapshot.isArrived,
                        onTravelProfileChanged = onTravelProfileChanged,
                        onOpenAr = onSwitchToAr,
                        canUseAr = canUseArBeta,
                    )
                } else if (uiState.snapshot.isArrived) {
                    ArrivalSheet(destination = uiState.selectedDestination?.title)
                } else if (uiState.activeTab == AppTab.Go) {
                    GoPlanningSheet(
                        travelProfile = uiState.travelProfile,
                        selectedOrigin = uiState.selectedOrigin?.title,
                        selectedDestination = uiState.selectedDestination?.title,
                        cityMode = uiState.cityMode,
                        kiitBetaAccess = uiState.kiitBetaAccess,
                        onTravelProfileChanged = onTravelProfileChanged,
                    )
                }

                val showDiscoveryCards = uiState.searchQuery.isBlank()
                when (uiState.activeTab) {
                    AppTab.Explore, AppTab.Go ->
                        if (showDiscoveryCards) {
                            PlaceCarousel(
                                places = uiState.places,
                                selectedOriginId = uiState.selectedOrigin?.id,
                                selectedDestinationId = uiState.selectedDestination?.id,
                                savedPlaceIds = uiState.savedPlaces.map { it.id }.toSet(),
                                onSelectOrigin = onSelectOrigin,
                                onSelectDestination = onSelectDestination,
                                onToggleSaved = onToggleSaved,
                            )
                        }

                    AppTab.Saved ->
                        PlacesShelfPanel(
                            title = "Saved places",
                            caption = "Pinned places stay ready for one-tap routing.",
                            places = uiState.savedPlaces,
                            savedPlaceIds = uiState.savedPlaces.map { it.id }.toSet(),
                            selectedOriginId = uiState.selectedOrigin?.id,
                            selectedDestinationId = uiState.selectedDestination?.id,
                            onSelectOrigin = onSelectOrigin,
                            onSelectDestination = onSelectDestination,
                            onToggleSaved = onToggleSaved,
                        )

                    AppTab.Recents ->
                        RecentsPanel(
                            places = uiState.recentPlaces,
                            history = uiState.routeHistory,
                            savedPlaceIds = uiState.savedPlaces.map { it.id }.toSet(),
                            selectedOriginId = uiState.selectedOrigin?.id,
                            selectedDestinationId = uiState.selectedDestination?.id,
                            onSelectOrigin = onSelectOrigin,
                            onSelectDestination = onSelectDestination,
                            onToggleSaved = onToggleSaved,
                        )
                }

                HomeBottomBar(selectedTab = uiState.activeTab, onTabSelected = onTabSelected)
            }
        }
    }
}

@Composable
private fun AppTitleRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Route,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = "NavGate",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PlannerStatusRow(origin: String?, destination: String?) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricPill("From", origin ?: "Live location")
        MetricPill("To", destination ?: "Choose place")
    }
}

@Composable
private fun OnboardingScreen(onContinue: () -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF09111F), Color(0xFF101A2E), Color(0xFF0B1326)),
                    ),
                )
                .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(32.dp), containerColor = Color(0xD9192237)) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = RoundedCornerShape(24.dp), color = Color(0xFF111C31), modifier = Modifier.size(72.dp)) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_navgate),
                                contentDescription = "NavGate logo",
                                tint = Color.Unspecified,
                                modifier = Modifier.size(56.dp),
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("NavGate", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text("Map-first navigation with KIIT 3D beta rollout", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    "Use Mumbai mode for daily route testing and KIIT Beta for Bhubaneswar campus search, assigned spreadsheet locations, and student-only 3D navigation trials.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("1. Search any place and set it as From or To.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("2. Preview the route on the 2D map before starting.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("3. Unlock KIIT Beta in the menu when students need the 3D AR pilot.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = onContinue, shape = RoundedCornerShape(999.dp), modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 14.dp)) {
                    Text("Enter NavGate")
                }
            }
        }
    }
}

@Composable
private fun TopSearchShell(
    query: String,
    onSearchChanged: (String) -> Unit,
    onOpenMenu: () -> Unit,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        containerColor = Color(0xCC171F33),
        borderColor = Color(0xB9C7DBFF),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlassIconButton(icon = Icons.Rounded.Menu, contentDescription = "Menu", onClick = onOpenMenu)
            SearchPill(
                modifier = Modifier.weight(1f),
                query = query,
                onSearchChanged = onSearchChanged,
            )
            ProfileOrb()
        }
    }
}

@Composable
private fun SearchPill(
    modifier: Modifier = Modifier,
    query: String,
    onSearchChanged: (String) -> Unit,
) {
    TextField(
        value = query,
        onValueChange = onSearchChanged,
        modifier = modifier,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        placeholder = {
            Text(
                text = "Search here",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingIcon = {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingIcon = {
            Icon(Icons.Rounded.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        shape = RoundedCornerShape(26.dp),
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = Color(0x66252E42),
                unfocusedContainerColor = Color(0x66252E42),
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
    )
}

@Composable
private fun ProfileOrb() {
    Surface(
        modifier = Modifier.size(44.dp),
        shape = CircleShape,
        color = Color(0xFF1E2A43),
        border = androidx.compose.foundation.BorderStroke(1.dp, NavGlassStroke),
    ) {
        Box(
            modifier =
                Modifier
                    .background(
                        brush = Brush.radialGradient(listOf(Color(0xFF35507E), Color(0xFF172237))),
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "RG",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun QuickCategoryRow(cityMode: CityMode, onCategorySelected: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
        item {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = Color(0xA51B2841),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x24FFFFFF)),
            ) {
                Text(
                    text = if (cityMode == CityMode.KiitBeta) "KIIT beta" else "Mumbai live",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        items(QuickCategories) { category ->
            Surface(
                onClick = { onCategorySelected(category.query) },
                shape = RoundedCornerShape(999.dp),
                color = Color(0xB3171F33),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x24FFFFFF)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(category.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Text(category.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun SearchResultsPanel(
    query: String,
    places: List<PlaceSearchResult>,
    onSelectOrigin: (PlaceSearchResult) -> Unit,
    onSelectDestination: (PlaceSearchResult) -> Unit,
) {
    if (query.isBlank()) return
    GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), containerColor = Color(0xD5192134)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Search results", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (places.isEmpty()) {
                Text(
                    "No locations matched yet. Try a broader query or switch city mode.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                places.take(4).forEach { place ->
                    SearchResultRow(
                        place = place,
                        onSelectOrigin = { onSelectOrigin(place) },
                        onSelectDestination = { onSelectDestination(place) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    place: PlaceSearchResult,
    onSelectOrigin: () -> Unit,
    onSelectDestination: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(place.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            place.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onSelectOrigin, shape = RoundedCornerShape(999.dp)) { Text("Set as from") }
            Button(onClick = onSelectDestination, shape = RoundedCornerShape(999.dp)) { Text("Set as to") }
        }
    }
}

@Composable
private fun MiniPermissionPill(onRequestLocationPermission: () -> Unit) {
    Surface(
        onClick = onRequestLocationPermission,
        shape = RoundedCornerShape(999.dp),
        color = Color(0xAA1A2843),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x28FFFFFF)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.MyLocation, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text("Enable live GPS", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun FloatingActionRail(
    modifier: Modifier = Modifier,
    onCenterOnUser: () -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.End) {
        Surface(
            shape = CircleShape,
            color = Color(0xCC222A3D),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x28FFFFFF)),
        ) {
            IconButton(onClick = {}) {
                Icon(Icons.Rounded.Layers, contentDescription = "Layers", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            shadowElevation = 14.dp,
        ) {
            IconButton(onClick = onCenterOnUser) {
                Icon(Icons.Rounded.MyLocation, contentDescription = "My location", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun PlaceCarousel(
    places: List<PlaceSearchResult>,
    selectedOriginId: String?,
    selectedDestinationId: String?,
    savedPlaceIds: Set<String>,
    onSelectOrigin: (PlaceSearchResult) -> Unit,
    onSelectDestination: (PlaceSearchResult) -> Unit,
    onToggleSaved: (PlaceSearchResult) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(places, key = { it.id }) { place ->
            FeaturedPlaceCard(
                place = place,
                isOrigin = selectedOriginId == place.id,
                isDestination = selectedDestinationId == place.id,
                isSaved = savedPlaceIds.contains(place.id),
                onSelectOrigin = { onSelectOrigin(place) },
                onSelectDestination = { onSelectDestination(place) },
                onToggleSaved = { onToggleSaved(place) },
            )
        }
    }
}

@Composable
private fun FeaturedPlaceCard(
    place: PlaceSearchResult,
    isOrigin: Boolean,
    isDestination: Boolean,
    isSaved: Boolean,
    onSelectOrigin: () -> Unit,
    onSelectDestination: () -> Unit,
    onToggleSaved: () -> Unit,
) {
    val icon = placeIcon(place.type)
    GlassCard(
        modifier = Modifier.width(314.dp),
        shape = RoundedCornerShape(30.dp),
        containerColor = Color(0xD9222A3D),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(place.title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        text = "${place.subtitle} • ${place.type.name.lowercase().replaceFirstChar { it.uppercase() }}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                PlaceThumbnail(icon = icon, label = place.title)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onSelectDestination,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (isDestination) "Destination set" else "Directions")
                }
                Surface(
                    onClick = onToggleSaved,
                    shape = CircleShape,
                    color = if (isSaved) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x2EFFFFFF)),
                ) {
                    Box(modifier = Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.BookmarkBorder,
                            contentDescription = null,
                            tint = if (isSaved) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            TextButton(onClick = onSelectOrigin) {
                Text(if (isOrigin) "Start point locked" else "Use as start")
            }
            OriginDestinationState(place = place, isOrigin = isOrigin, isDestination = isDestination)
        }
    }
}

@Composable
private fun PlaceThumbnail(icon: ImageVector, label: String) {
    Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFF1B2437), modifier = Modifier.size(58.dp)) {
        Box(
            modifier =
                Modifier
                    .background(
                        brush = Brush.linearGradient(
                            listOf(Color(0xFF1D2C45), Color(0xFF2B3E63), Color(0xFF77511E)),
                        ),
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun OriginDestinationState(
    place: PlaceSearchResult,
    isOrigin: Boolean,
    isDestination: Boolean,
) {
    val message = when {
        isOrigin && isDestination -> "This point anchors both start and end"
        isOrigin -> "Start point locked here"
        isDestination -> "Destination locked here"
        else -> "Tap Directions or bookmark this point as your start"
    }
    Text(
        text = message,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun RoutePreviewSheet(
    preview: RoutePreview,
    travelProfile: com.rohanc.navgate.model.TravelProfile,
    confidence: String,
    onStartNavigation: () -> Unit,
    onTravelProfileChanged: (com.rohanc.navgate.model.TravelProfile) -> Unit,
    onOpenAr: () -> Unit,
    canUseAr: Boolean,
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(32.dp), containerColor = Color(0xE61A2236)) {
        Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Route preview", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "${preview.originTitle} to ${preview.destinationTitle}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricPill("Distance", preview.distanceLabel)
                MetricPill("ETA", preview.etaLabel)
            }
            TravelModeSelector(selected = travelProfile, onSelected = onTravelProfileChanged)
            Text(preview.firstInstruction, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(confidence, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onStartNavigation,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(vertical = 14.dp),
                ) {
                    Text("Start navigation")
                }
                FilledTonalButton(
                    onClick = onOpenAr,
                    enabled = canUseAr,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(vertical = 14.dp),
                ) {
                    Icon(Icons.Rounded.CameraAlt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (canUseAr) "AR assist" else "KIIT beta only")
                }
            }
        }
    }
}

@Composable
private fun LiveGuidanceSheet(
    instruction: String,
    distanceLabel: String,
    etaLabel: String,
    travelProfile: com.rohanc.navgate.model.TravelProfile,
    isArrived: Boolean,
    onTravelProfileChanged: (com.rohanc.navgate.model.TravelProfile) -> Unit,
    onOpenAr: () -> Unit,
    canUseAr: Boolean,
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(32.dp), containerColor = Color(0xE6192134)) {
        Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Navigation, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(if (isArrived) "Arrival" else "Live guidance", style = MaterialTheme.typography.titleLarge)
            }
            Text(instruction, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricPill("Next", distanceLabel)
                MetricPill("ETA", etaLabel)
            }
            TravelModeSelector(selected = travelProfile, onSelected = onTravelProfileChanged)
            FilledTonalButton(onClick = onOpenAr, enabled = canUseAr, shape = RoundedCornerShape(999.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
                Icon(Icons.Rounded.CameraAlt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (canUseAr) "Switch to AR assist" else "3D locked to KIIT beta")
            }
        }
    }
}

@Composable
private fun ArrivalSheet(destination: String?) {
    GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(32.dp), containerColor = Color(0xE6192134)) {
        Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.ArrowOutward, contentDescription = null, tint = NavHighConfidence)
                Text("Destination reached", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            }
            Text(
                destination?.let { "You have arrived near $it. You can switch back to Explore or pick the next stop." }
                    ?: "You have arrived at your destination.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LoadingRouteCard() {
    GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), containerColor = Color(0xD6192134)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            Text("Building the premium walking route preview...", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun GoPlanningSheet(
    travelProfile: com.rohanc.navgate.model.TravelProfile,
    selectedOrigin: String?,
    selectedDestination: String?,
    cityMode: CityMode,
    kiitBetaAccess: Boolean,
    onTravelProfileChanged: (com.rohanc.navgate.model.TravelProfile) -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), containerColor = Color(0xD6192134)) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Go", style = MaterialTheme.typography.titleLarge)
            Text(
                "Choose a destination from Explore, Saved, or Recents. Live GPS will act as the origin when available.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (cityMode == CityMode.KiitBeta && kiitBetaAccess) {
                    "KIIT students can trial the 3D AR guidance in this mode and report bugs during the pilot."
                } else {
                    "3D AR is reserved for the KIIT beta right now. Mumbai stays available for 2D route testing."
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
            )
            TravelModeSelector(selected = travelProfile, onSelected = onTravelProfileChanged)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricPill("Origin", selectedOrigin ?: "Live GPS")
                MetricPill("Destination", selectedDestination ?: "Not set")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NavGateMenuSheet(
    cityMode: CityMode,
    kiitBetaAccess: Boolean,
    onDismiss: () -> Unit,
    onCityModeChanged: (CityMode) -> Unit,
    onKiitBetaAccessChanged: (Boolean) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF10192C)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("NavGate", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Use Mumbai as the daily live map mode. Enable KIIT beta access to let students trial 3D routing first.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("City mode", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CityMode.entries.forEach { mode ->
                    Surface(
                        onClick = { onCityModeChanged(mode) },
                        shape = RoundedCornerShape(999.dp),
                        color = if (cityMode == mode) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f) else Color(0x7A25304A),
                    ) {
                        Text(
                            text = mode.label,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (cityMode == mode) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), containerColor = Color(0xCC171F33)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("KIIT student 3D beta", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "Unlock AR-first navigation only for the KIIT pilot so students can test and report issues.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = kiitBetaAccess,
                        onCheckedChange = onKiitBetaAccessChanged,
                    )
                }
            }
            Text(
                "Supabase and production backend deployment still need your project credentials. I can wire the code paths, but I can’t truthfully activate your hosted stack without those secrets.",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun PlacesShelfPanel(
    title: String,
    caption: String,
    places: List<PlaceSearchResult>,
    savedPlaceIds: Set<String>,
    selectedOriginId: String?,
    selectedDestinationId: String?,
    onSelectOrigin: (PlaceSearchResult) -> Unit,
    onSelectDestination: (PlaceSearchResult) -> Unit,
    onToggleSaved: (PlaceSearchResult) -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), containerColor = Color(0xD6192134)) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(caption, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (places.isEmpty()) {
                Text("Nothing here yet. Search a place or save a destination to build this list.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                PlaceCarousel(
                    places = places,
                    selectedOriginId = selectedOriginId,
                    selectedDestinationId = selectedDestinationId,
                    savedPlaceIds = savedPlaceIds,
                    onSelectOrigin = onSelectOrigin,
                    onSelectDestination = onSelectDestination,
                    onToggleSaved = onToggleSaved,
                )
            }
        }
    }
}

@Composable
private fun RecentsPanel(
    places: List<PlaceSearchResult>,
    history: List<RouteHistoryEntry>,
    savedPlaceIds: Set<String>,
    selectedOriginId: String?,
    selectedDestinationId: String?,
    onSelectOrigin: (PlaceSearchResult) -> Unit,
    onSelectDestination: (PlaceSearchResult) -> Unit,
    onToggleSaved: (PlaceSearchResult) -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), containerColor = Color(0xD6192134)) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Recent activity", style = MaterialTheme.typography.titleLarge)
            Text(
                "Your latest searches and route launches stay ready here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (history.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(history, key = { "${it.destinationId}-${it.recordedAtEpochMillis}" }) { entry ->
                        RouteHistoryCard(entry)
                    }
                }
            }
            if (places.isEmpty()) {
                Text("Nothing here yet. Search or start a route to build your recent activity.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                PlaceCarousel(
                    places = places,
                    selectedOriginId = selectedOriginId,
                    selectedDestinationId = selectedDestinationId,
                    savedPlaceIds = savedPlaceIds,
                    onSelectOrigin = onSelectOrigin,
                    onSelectDestination = onSelectDestination,
                    onToggleSaved = onToggleSaved,
                )
            }
        }
    }
}

@Composable
private fun RouteHistoryCard(entry: RouteHistoryEntry) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color(0x7A25304A),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x24FFFFFF)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(entry.destinationTitle, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "${entry.travelMode.name.lowercase().replaceFirstChar { it.uppercase() }} • ${formatEta(entry.etaSeconds)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TravelModeSelector(
    selected: com.rohanc.navgate.model.TravelProfile,
    onSelected: (com.rohanc.navgate.model.TravelProfile) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TravelModeChip(
            label = "Walking",
            selected = selected == com.rohanc.navgate.model.TravelProfile.Walking,
            onClick = { onSelected(com.rohanc.navgate.model.TravelProfile.Walking) },
        )
        TravelModeChip(
            label = "Driving",
            selected = selected == com.rohanc.navgate.model.TravelProfile.Driving,
            onClick = { onSelected(com.rohanc.navgate.model.TravelProfile.Driving) },
        )
    }
}

@Composable
private fun TravelModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f) else Color(0x7A25304A),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun HomeBottomBar(selectedTab: AppTab, onTabSelected: (AppTab) -> Unit) {
    NavigationBar(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)),
        containerColor = Color(0xE6111828),
        tonalElevation = 0.dp,
        windowInsets = WindowInsets.navigationBars,
    ) {
        AppTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selectedTab,
                onClick = { onTabSelected(tab) },
                icon = { Icon(tab.icon(), contentDescription = tab.label()) },
                label = { Text(tab.label()) },
                colors =
                    NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        indicatorColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
            )
        }
    }
}

@Composable
private fun GlassCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(28.dp),
    containerColor: Color = Color(0xCC171F33),
    borderColor: Color = NavGlassStroke,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.border(1.dp, borderColor, shape),
        shape = shape,
    ) {
        Column(
            modifier =
                Modifier
                    .background(containerColor)
                    .padding(0.dp),
            content = content,
        )
    }
}

@Composable
private fun GlassIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Surface(shape = CircleShape, color = Color(0x66242D42)) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = contentDescription, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun MetricPill(label: String, value: String) {
    Surface(shape = RoundedCornerShape(999.dp), color = Color(0x7A25304A)) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            Text(value, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun BoxScope.MapAtmosphere() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xA60B1326), Color(0x350B1326), Color(0xCC0B1326)),
                    ),
                ),
    )
    Box(
        modifier =
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxSize()
                .blur(90.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(NavMapGlow.copy(alpha = 0.44f), Color.Transparent),
                        radius = 720f,
                    ),
                ),
    )
}

private fun formatDistance(distanceMeters: Double): String =
    if (distanceMeters >= 1000) {
        "%.1f km".format(distanceMeters / 1000.0)
    } else {
        "${distanceMeters.toInt()} m"
    }

private fun formatEta(seconds: Double): String {
    val minutes = kotlin.math.ceil(seconds / 60.0).toInt().coerceAtLeast(1)
    return if (minutes < 60) "$minutes min" else "${minutes / 60} hr ${minutes % 60} min"
}

@Composable
private fun ArAssistScreen(
    uiState: NavGateUiState,
    hasLocationPermission: Boolean,
    onSwitchToMap: () -> Unit,
) {
    val headingState = rememberHeadingState()
    val alignment = alignmentStatus(uiState.snapshot, headingState.value)

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(modifier = Modifier.fillMaxSize())
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xCC09111F), Color(0x5509111F), Color(0xD909111F)),
                        ),
                    ),
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(30.dp), containerColor = Color(0xCC161D31)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("AR assist", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(uiState.snapshot.currentInstruction, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FilledTonalButton(onClick = onSwitchToMap, shape = RoundedCornerShape(999.dp)) {
                        Icon(Icons.Rounded.Map, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Map")
                    }
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                RouteRibbonPreview(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    alignment = alignment,
                    distanceMeters = uiState.snapshot.distanceToNextStep,
                )
                Spacer(Modifier.height(18.dp))
                AlignmentRing(alignment = alignment)
                Spacer(Modifier.height(18.dp))
                Text(alignment.label, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "Next turn in ${formatDistance(uiState.snapshot.distanceToNextStep)}",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(30.dp), containerColor = Color(0xCC161D31)) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ConfidenceBadge(uiState.snapshot.guidanceConfidence)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricPill("ETA", formatEta(uiState.snapshot.etaSeconds))
                        MetricPill("Mode", if (hasLocationPermission) "AR Assist" else "Map fallback")
                    }
                    Text(
                        "Map truth stays active underneath this camera view. If alignment confidence drops, return to the map and continue safely.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun AlignmentRing(alignment: com.rohanc.navgate.ui.ar.AlignmentStatus) {
    val color = when (alignment.level) {
        AlignmentLevel.Aligned -> NavHighConfidence
        AlignmentLevel.Adjust -> NavMediumConfidence
        AlignmentLevel.Recover -> NavLowConfidence
    }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(240.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawArc(
                color = Color.White.copy(alpha = 0.16f),
                startAngle = 140f,
                sweepAngle = 260f,
                useCenter = false,
                style = Stroke(width = 22f, cap = StrokeCap.Round),
            )
            val clamped = alignment.deltaDegrees.coerceIn(-90.0, 90.0).toFloat()
            drawArc(
                color = color,
                startAngle = 270f + clamped,
                sweepAngle = 52f,
                useCenter = false,
                style = Stroke(width = 22f, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Explore, contentDescription = null, tint = color, modifier = Modifier.size(58.dp))
            Text(
                text = "${alignment.deltaDegrees.toInt()}°",
                color = Color.White,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun RouteRibbonPreview(
    modifier: Modifier = Modifier,
    alignment: com.rohanc.navgate.ui.ar.AlignmentStatus,
    distanceMeters: Double,
) {
    val ribbonColor = when (alignment.level) {
        AlignmentLevel.Aligned -> NavHighConfidence
        AlignmentLevel.Adjust -> NavMediumConfidence
        AlignmentLevel.Recover -> NavLowConfidence
    }
    Canvas(modifier = modifier) {
        val centerX = size.width / 2f
        val startY = size.height
        val endY = size.height * 0.12f
        val horizontalOffset = (alignment.deltaDegrees.coerceIn(-70.0, 70.0) / 70.0 * (size.width * 0.28)).toFloat()
        drawLine(
            color = Color.White.copy(alpha = 0.10f),
            start = androidx.compose.ui.geometry.Offset(centerX, startY),
            end = androidx.compose.ui.geometry.Offset(centerX, endY),
            strokeWidth = 34f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = ribbonColor.copy(alpha = 0.92f),
            start = androidx.compose.ui.geometry.Offset(centerX, startY),
            end = androidx.compose.ui.geometry.Offset(centerX + horizontalOffset, endY),
            strokeWidth = 22f,
            cap = StrokeCap.Round,
        )
        val markerY = size.height * 0.28f
        drawCircle(
            color = ribbonColor,
            radius = 11f,
            center = androidx.compose.ui.geometry.Offset(centerX + horizontalOffset, markerY),
        )
    }
}

private fun placeIcon(type: PlaceType): ImageVector =
    when (type) {
        PlaceType.Gate -> Icons.Rounded.Route
        PlaceType.Academic -> Icons.Rounded.School
        PlaceType.Sports -> Icons.Rounded.Explore
        PlaceType.Residential -> Icons.Rounded.BookmarkBorder
        PlaceType.Food -> Icons.Rounded.Restaurant
        PlaceType.Transit -> Icons.Rounded.LocalGasStation
        PlaceType.Commercial -> Icons.Rounded.LocalGasStation
        PlaceType.Hospitality -> Icons.Rounded.BookmarkBorder
        PlaceType.Medical -> Icons.Rounded.Route
        PlaceType.Landmark -> Icons.Rounded.Explore
    }

private fun AppTab.label(): String =
    when (this) {
        AppTab.Explore -> "Explore"
        AppTab.Go -> "Go"
        AppTab.Saved -> "Saved"
        AppTab.Recents -> "Recents"
    }

private fun AppTab.icon(): ImageVector =
    when (this) {
        AppTab.Explore -> Icons.Rounded.Explore
        AppTab.Go -> Icons.Rounded.Navigation
        AppTab.Saved -> Icons.Rounded.BookmarkBorder
        AppTab.Recents -> Icons.Rounded.History
    }
