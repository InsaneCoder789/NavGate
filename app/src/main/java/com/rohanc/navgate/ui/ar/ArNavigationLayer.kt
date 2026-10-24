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

private val Lime = Color(0xFFFF8A65)   // coral: the AR accent
private val Ink = Color(0xFF121B2E)    // deep navy glass

/**
 * The whole AR navigation layer. The road line itself is drawn inside the ARCore GL frame (see [ArSessionView]); this feeds it the
 * route-locked position, the short stretch of road ahead and the compass, and draws the text that rides above it: the distance
 * and action label over the turn, tracking help, and the live frame rates.
 */
@Composable
fun ArNavigationLayer(route: CampusRoute, geometry: RouteGeometry?, track: TrackInput?, snapped: GeoPoint?, gps: GeoPoint?, distanceToTurnMeters: Double, driving: Boolean, fpsMode: ArFpsMode, modifier: Modifier = Modifier, objectAwareness: Boolean = true, sessionTurnKind: String? = null, domeTop: Float = 0f) {
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
        inputs.objectAwareness = objectAwareness; inputs.path = path; inputs.roadEndMeters = roadEnd; inputs.turnKind = turnKind; inputs.geometry = geometry; inputs.driving = driving
        inputs.alongShapeMeters = track?.alongShapeMeters ?: 0.0; inputs.speedMps = track?.speedMps ?: 0.0; inputs.fixElapsedMillis = track?.fixElapsedMillis ?: 0L
        inputs.compassAzimuth = basis?.takeIf { kotlin.math.abs(it.pitchDegrees) < 55 }?.headingDegrees
        inputs.compassUpAzimuth = basis?.let { (Math.toDegrees(kotlin.math.atan2(it.up[0], it.up[1])) + 360.0) % 360.0 }
    }

    Box(modifier) {
        if (status is ArSessionStatus.Unavailable) {
            // No ARCore on this phone: AR needs it, so say so plainly over the plain camera instead of drawing a guess.
            CameraPreview(Modifier.fillMaxSize())
            Text("AR guidance needs Google Play Services for AR, which this phone does not support. Use the map to navigate.",
                Modifier.align(Alignment.Center).padding(32.dp).clip40().background(Ink.copy(alpha = 0.85f)).padding(horizontal = 20.dp, vertical = 14.dp), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        } else {
            ArSessionView(inputs, fpsMode, { status = it; if (it is ArSessionStatus.Running) Unit }, { hud = it; if (status !is ArSessionStatus.Running) status = ArSessionStatus.Running }, Modifier.fillMaxSize(), domeTop)
            ArTextLayer(hud, sessionTurnKind ?: turnKind, turnLabel, distanceToTurnMeters, fpsMode, showStatus = domeTop == 0f)
        }
    }
}

private fun Modifier.clip40() = this.then(Modifier.clip(RoundedCornerShape(40.dp)))
