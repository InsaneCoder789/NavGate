package com.rohanc.navgate.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rohanc.navgate.ar.ArAvailabilityState
import com.rohanc.navgate.ar.ArCoreSupport
import com.rohanc.navgate.model.PlaceSearchResult
import com.rohanc.navgate.navigation.PresentationMode
import com.rohanc.navgate.ui.ar.AlignmentLevel
import com.rohanc.navgate.ui.ar.CameraPreview
import com.rohanc.navgate.ui.ar.alignmentStatus
import com.rohanc.navgate.ui.ar.rememberHeadingState
import com.rohanc.navgate.ui.components.ConfidenceBadge
import com.rohanc.navgate.ui.map.MapRouteGeoJson
import com.rohanc.navgate.ui.state.NavGateViewModel
import com.rohanc.navgate.ui.state.RoutePreview
import com.rohanc.navgate.ui.theme.NavGateTheme
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

@Composable
fun NavGateApp(viewModel: NavGateViewModel = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? ComponentActivity
    val arCoreSupport = remember { ArCoreSupport() }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var arState by remember { mutableStateOf<ArAvailabilityState>(ArAvailabilityState.Checking) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
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

    Surface(modifier = Modifier.fillMaxSize()) {
        NavGateHome(
            uiState = uiState,
            arState = arState,
            hasCameraPermission = hasCameraPermission,
            onSearchChanged = viewModel::updateSearch,
            onSelectOrigin = {
                viewModel.selectOrigin(it)
                viewModel.useDemoUserLocation(it)
            },
            onSelectDestination = viewModel::selectDestination,
            onStartNavigation = viewModel::startNavigation,
            onSwitchToAr = {
                if (!hasCameraPermission) {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                } else {
                    viewModel.switchMode(PresentationMode.AR_ASSIST)
                }
            },
            onSwitchToMap = { viewModel.switchMode(PresentationMode.MAP) },
        )
    }
}

@Composable
private fun NavGateHome(
    uiState: com.rohanc.navgate.ui.state.NavGateUiState,
    arState: ArAvailabilityState,
    hasCameraPermission: Boolean,
    onSearchChanged: (String) -> Unit,
    onSelectOrigin: (PlaceSearchResult) -> Unit,
    onSelectDestination: (PlaceSearchResult) -> Unit,
    onStartNavigation: () -> Unit,
    onSwitchToAr: () -> Unit,
    onSwitchToMap: () -> Unit,
) {
    if (uiState.snapshot.presentationMode == PresentationMode.AR_ASSIST && hasCameraPermission && arState == ArAvailabilityState.Supported) {
        ArAssistScreen(uiState = uiState, onSwitchToMap = onSwitchToMap)
        return
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF06101C), Color(0xFF0E2740), Color(0xFF12907C)),
                    ),
                )
                .padding(16.dp),
    ) {
        val target = uiState.snapshot.userLocation ?: uiState.selectedOrigin?.coordinate ?: uiState.selectedDestination?.coordinate
        val cameraState =
            rememberCameraState(
                firstPosition =
                    CameraPosition(
                        target =
                            Position(
                                latitude = target?.latitude ?: 20.349884,
                                longitude = target?.longitude ?: 85.807529,
                            ),
                        zoom = if (uiState.snapshot.route == null) 15.5 else 16.6,
                    ),
            )
        val routeSource = rememberGeoJsonSource(GeoJsonData.JsonString(MapRouteGeoJson.lineString(uiState.snapshot.route)))
        val endpointSource =
            rememberGeoJsonSource(
                GeoJsonData.JsonString(
                    MapRouteGeoJson.endpoints(uiState.snapshot.origin, uiState.snapshot.destination),
                ),
            )

        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            cameraState = cameraState,
            baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty"),
            options = MapOptions(ornamentOptions = OrnamentOptions(padding = PaddingValues(top = 90.dp, bottom = 140.dp))),
        ) {
            LineLayer(
                id = "active-route",
                source = routeSource,
                color = const(Color(0xFF58E8C1)),
                width = const(6.dp),
            )
            CircleLayer(
                id = "route-endpoints",
                source = endpointSource,
                radius = const(7.dp),
                color = const(Color(0xFFFFD166)),
                strokeColor = const(Color(0xFF0D121F)),
                strokeWidth = const(2.dp),
            )
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HeaderCard(
                    query = uiState.searchQuery,
                    onSearchChanged = onSearchChanged,
                    onSwitchToMap = onSwitchToMap,
                    isArMode = uiState.snapshot.presentationMode == PresentationMode.AR_ASSIST,
                )
                ConfidenceBadge(uiState.snapshot.guidanceConfidence)
                if (uiState.snapshot.presentationMode == PresentationMode.AR_ASSIST) {
                    ArAssistBanner(arState = arState, hasCameraPermission = hasCameraPermission, onSwitchToMap = onSwitchToMap)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PlacesPanel(
                    places = uiState.places,
                    selectedOriginId = uiState.selectedOrigin?.id,
                    selectedDestinationId = uiState.selectedDestination?.id,
                    onSelectOrigin = onSelectOrigin,
                    onSelectDestination = onSelectDestination,
                )
                if (uiState.isLoadingRoute) {
                    LoadingCard()
                }
                if (uiState.preview != null && uiState.isPreviewVisible) {
                    RoutePreviewCard(
                        preview = uiState.preview,
                        confidence = uiState.snapshot.guidanceConfidence.reason,
                        onStartNavigation = onStartNavigation,
                        onOpenAr = onSwitchToAr,
                    )
                } else if (uiState.snapshot.isNavigating) {
                    LiveHudCard(
                        instruction = uiState.snapshot.currentInstruction,
                        distanceLabel = formatDistance(uiState.snapshot.distanceToNextStep),
                        etaLabel = formatEta(uiState.snapshot.etaSeconds),
                        onOpenAr = onSwitchToAr,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderCard(
    query: String,
    onSearchChanged: (String) -> Unit,
    onSwitchToMap: () -> Unit,
    isArMode: Boolean,
) {
    Card(shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("NavGate", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Campus walking with map truth and AR assist", color = Color(0xFF496273))
                }
                if (isArMode) {
                    FilledTonalButton(onClick = onSwitchToMap) {
                        Icon(Icons.Rounded.Map, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Return to map")
                    }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = onSearchChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search campus places") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Route, contentDescription = null) },
            )
        }
    }
}

@Composable
private fun PlacesPanel(
    places: List<PlaceSearchResult>,
    selectedOriginId: String?,
    selectedDestinationId: String?,
    onSelectOrigin: (PlaceSearchResult) -> Unit,
    onSelectDestination: (PlaceSearchResult) -> Unit,
) {
    Card(shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Choose origin and destination", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            LazyColumn(modifier = Modifier.height(180.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(places, key = { it.id }) { place ->
                    PlaceRow(
                        place = place,
                        isOrigin = selectedOriginId == place.id,
                        isDestination = selectedDestinationId == place.id,
                        onSelectOrigin = { onSelectOrigin(place) },
                        onSelectDestination = { onSelectDestination(place) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(
    place: PlaceSearchResult,
    isOrigin: Boolean,
    isDestination: Boolean,
    onSelectOrigin: () -> Unit,
    onSelectDestination: () -> Unit,
) {
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(place.title, fontWeight = FontWeight.SemiBold)
                    Text(place.subtitle, color = Color(0xFF61788A), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(place.type.name, color = Color(0xFF1B8A74), style = MaterialTheme.typography.labelMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onSelectOrigin) {
                    Icon(Icons.Rounded.MyLocation, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(if (isOrigin) "Origin set" else "Use as start")
                }
                OutlinedButton(onClick = onSelectDestination) {
                    Icon(Icons.Rounded.ArrowOutward, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(if (isDestination) "Destination set" else "Use as end")
                }
            }
        }
    }
}

@Composable
private fun RoutePreviewCard(
    preview: RoutePreview,
    confidence: String,
    onStartNavigation: () -> Unit,
    onOpenAr: () -> Unit,
) {
    Card(shape = RoundedCornerShape(32.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Route preview", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("${preview.originTitle} to ${preview.destinationTitle}", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricPill("Distance", preview.distanceLabel)
                MetricPill("ETA", preview.etaLabel)
            }
            HorizontalDivider()
            Text(preview.firstInstruction, style = MaterialTheme.typography.bodyLarge)
            Text(confidence, color = Color(0xFF60798C))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onStartNavigation, modifier = Modifier.weight(1f)) {
                    Text("Start navigation")
                }
                FilledTonalButton(onClick = onOpenAr, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.CameraAlt, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("AR assist")
                }
            }
        }
    }
}

@Composable
private fun LiveHudCard(
    instruction: String,
    distanceLabel: String,
    etaLabel: String,
    onOpenAr: () -> Unit,
) {
    Card(shape = RoundedCornerShape(32.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Live guidance", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(instruction, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricPill("Next", distanceLabel)
                MetricPill("ETA", etaLabel)
            }
            FilledTonalButton(onClick = onOpenAr) {
                Icon(Icons.Rounded.CameraAlt, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Switch to AR assist")
            }
        }
    }
}

@Composable
private fun ArAssistBanner(
    arState: ArAvailabilityState,
    hasCameraPermission: Boolean,
    onSwitchToMap: () -> Unit,
) {
    Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AR assist preview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    !hasCameraPermission -> "Grant camera access to enter the camera overlay."
                    arState == ArAvailabilityState.Supported -> "AR mode uses the shared route and will prioritize map truth if heading confidence drops."
                    else -> "AR is not fully available on this device, so map mode remains the authority."
                },
            )
            OutlinedButton(onClick = onSwitchToMap) { Text("Keep navigating on map") }
        }
    }
}

@Composable
private fun LoadingCard() {
    Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("Building the walking route preview...")
        }
    }
}

@Composable
private fun MetricPill(label: String, value: String) {
    Surface(
        color = Color(0xFFE9F8F4),
        shape = RoundedCornerShape(999.dp),
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, color = Color(0xFF46606D), style = MaterialTheme.typography.labelMedium)
            Text(value, color = Color(0xFF0D715B), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
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
    uiState: com.rohanc.navgate.ui.state.NavGateUiState,
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
                    .background(Color(0x4406111A)),
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Card(shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("AR assist", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(uiState.snapshot.currentInstruction, style = MaterialTheme.typography.bodyLarge)
                    }
                    FilledTonalButton(onClick = onSwitchToMap) {
                        Icon(Icons.Rounded.Map, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Map")
                    }
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                AlignmentRing(alignment = alignment)
                Spacer(Modifier.height(18.dp))
                Text(
                    text = alignment.label,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Next turn in ${formatDistance(uiState.snapshot.distanceToNextStep)}",
                    color = Color(0xFFD8EEF8),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            Card(shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ConfidenceBadge(uiState.snapshot.guidanceConfidence)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricPill("ETA", formatEta(uiState.snapshot.etaSeconds))
                        MetricPill("Mode", "AR Assist")
                    }
                    Text(
                        "Map truth stays active underneath this camera view. If alignment confidence drops, return to the map and continue safely.",
                        color = Color(0xFF5E7586),
                    )
                }
            }
        }
    }
}

@Composable
private fun AlignmentRing(alignment: com.rohanc.navgate.ui.ar.AlignmentStatus) {
    val color = when (alignment.level) {
        AlignmentLevel.Aligned -> Color(0xFF75F0C6)
        AlignmentLevel.Adjust -> Color(0xFFFFD166)
        AlignmentLevel.Recover -> Color(0xFFFF8B9A)
    }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(220.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawArc(
                color = Color.White.copy(alpha = 0.18f),
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
            Icon(Icons.Rounded.Explore, contentDescription = null, tint = color, modifier = Modifier.size(56.dp))
            Text(
                text = "${alignment.deltaDegrees.toInt()}°",
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
