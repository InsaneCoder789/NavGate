package com.rohanc.navgate.ui.ar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rohanc.navgate.core.CampusRoute
import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.core.RouteGeometry
import com.rohanc.navgate.ui.map.TrackInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val Lime = Color(0xFFC6FF00)
private val Ink = Color(0xFF0B1220)

/**
 * The whole AR navigation layer. The road line itself is drawn inside the ARCore GL frame (see [ArSessionView]); this feeds it the
 * route-locked position, the short stretch of road ahead and the compass, and draws the text that rides above it: the distance
 * and action label over the turn, tracking help, and the live frame rates.
 */
@Composable
fun ArNavigationLayer(route: CampusRoute, geometry: RouteGeometry?, track: TrackInput?, snapped: GeoPoint?, gps: GeoPoint?, distanceToTurnMeters: Double, driving: Boolean, fpsMode: ArFpsMode, modifier: Modifier = Modifier) {
    val inputs = remember { ArInputs() }
    var status by remember { mutableStateOf<ArSessionStatus>(ArSessionStatus.Starting) }
    var hud by remember { mutableStateOf<ArHud?>(null) }
    val basis by rememberCameraBasis(true) { gps }
    val here = snapped ?: gps

    // Checkpoints: only the road you are on is loaded; the track hands the renderer a short path ahead.
    val checkpoints = remember(route.id) { CheckpointTrack(route) }
    var path by remember(route.id) { mutableStateOf<List<GeoPoint>>(emptyList()) }
    var roadEnd by remember(route.id) { mutableStateOf(0.0) }
    var turnKind by remember(route.id) { mutableStateOf<String?>(null) }
    var turnLabel by remember(route.id) { mutableStateOf<String?>(null) }
    val stepKey = here?.let { (it.latitude * 1e5).toLong() to (it.longitude * 1e5).toLong() }
    LaunchedEffect(checkpoints, stepKey) {
        val position = here ?: return@LaunchedEffect
        val result = withContext(Dispatchers.Default) { checkpoints.update(position); Triple(checkpoints.pathAhead(), checkpoints.metersToStageEnd, checkpoints.stageEnd) }
        path = result.first; roadEnd = result.second
        turnKind = result.third?.turnKind ?: if (result.third?.kind == GateKind.Finish) "Arrive" else null
        turnLabel = result.third?.label
    }
    // The render thread reads these; they are plain volatile fields, so updating them costs nothing per frame.
    SideEffect {
        inputs.path = path; inputs.roadEndMeters = roadEnd; inputs.turnKind = turnKind; inputs.geometry = geometry; inputs.driving = driving
        inputs.alongShapeMeters = track?.alongShapeMeters ?: 0.0; inputs.speedMps = track?.speedMps ?: 0.0; inputs.fixElapsedMillis = track?.fixElapsedMillis ?: 0L
        inputs.compassAzimuth = basis?.takeIf { kotlin.math.abs(it.pitchDegrees) < 55 }?.headingDegrees
        inputs.compassUpAzimuth = basis?.let { (Math.toDegrees(kotlin.math.atan2(it.up[0], it.up[1])) + 360.0) % 360.0 }
    }

    Box(modifier) {
        if (status is ArSessionStatus.Unavailable) {
            // No ARCore on this phone: the plain camera with the compass-based line.
            CameraPreview(Modifier.fillMaxSize())
            ArRoadOverlay(path, roadEnd, gps, basis, turnKind, turnLabel, roadEnd, Modifier.fillMaxSize())
        } else {
            ArSessionView(inputs, fpsMode, { status = it; if (it is ArSessionStatus.Running) Unit }, { hud = it; if (status !is ArSessionStatus.Running) status = ArSessionStatus.Running }, Modifier.fillMaxSize())
            ArTextLayer(hud, turnKind, turnLabel, distanceToTurnMeters, fpsMode)
        }
    }
}

@Composable
private fun BoxScope.ArTextLayer(hud: ArHud?, turnKind: String?, turnLabel: String?, distanceToTurnMeters: Double, fpsMode: ArFpsMode) {
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    Box(Modifier.matchParentSize().onSizeChanged { size = it }) {
        val x = hud?.turnX; val y = hud?.turnY
        if (x != null && y != null && size.width > 0) {
            val exit = turnLabel?.let { Regex("(\\d+(?:st|nd|rd|th)) exit").find(it)?.groupValues?.get(1) }
            val title = when (turnKind) {
                "Left" -> "Turn left"; "SlightLeft" -> "Bear left"; "SharpLeft" -> "Sharp left"; "KeepLeft" -> "Keep left"
                "Right" -> "Turn right"; "SlightRight" -> "Bear right"; "SharpRight" -> "Sharp right"; "KeepRight" -> "Keep right"
                "UTurn" -> "U-turn"; "Merge" -> "Merge"; "RoundaboutExit" -> "Leave roundabout"; "Arrive" -> "Arrive"
                "Roundabout" -> if (exit != null) "Roundabout · $exit exit" else "Roundabout"
                else -> "Continue"
            }
            val density = LocalDensity.current
            var pill by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
            Row(Modifier.offset { IntOffset((x * size.width - pill.width / 2f).roundToInt().coerceIn(8, (size.width - pill.width - 8).coerceAtLeast(8)), (y * size.height - pill.height).roundToInt().coerceAtLeast(8)) }
                .onSizeChanged { pill = it }.clip40().background(Ink.copy(alpha = 0.92f)).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.clip40().background(Lime).padding(horizontal = 14.dp, vertical = 8.dp), color = Ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                Text("${distanceToTurnMeters.toInt().coerceAtLeast(0)} m", Modifier.padding(horizontal = 12.dp), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }
        hud?.tracking?.let { message ->
            Text(message, Modifier.align(Alignment.Center).clip40().background(Ink.copy(alpha = 0.85f)).padding(horizontal = 18.dp, vertical = 12.dp), color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
        if (hud != null) Text(
            "${fpsMode.name} · camera ${hud.cameraFps} fps · overlay ${hud.overlayFps} fps${if (hud.groundFound) "" else " · finding ground"}",
            Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 150.dp).clip40().background(Ink.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 4.dp),
            color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp,
        )
    }
}

private fun Modifier.clip40() = this.then(Modifier.clip(RoundedCornerShape(40.dp)))
