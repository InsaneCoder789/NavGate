package com.rohanc.navgate.ui.journey

import com.rohanc.navgate.ui.components.NavGlassSurface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rohanc.navgate.core.SessionSnapshot
import com.rohanc.navgate.core.SessionStatus
import com.rohanc.navgate.model.RouteStep
import com.rohanc.navgate.model.asEtaLabel
import com.rohanc.navgate.model.asMetersLabel
import java.text.DateFormat
import java.util.Date

private val GuidanceTeal = Color(0xFF265E66)

/** Turn-by-turn guidance: next manoeuvre on top, trip summary and exit at the bottom. */
@Composable
fun BoxScope.NavigationUi(session: SessionSnapshot, steps: List<RouteStep>, voiceOn: Boolean, driving: Boolean, motion: kotlinx.coroutines.flow.StateFlow<com.rohanc.navgate.core.MotionEstimate?>?, onInsets: (Float, Float) -> Unit, a: JourneyActions) {
    val density = LocalDensity.current
    var topPx by remember { mutableIntStateOf(0) }
    var bottomPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(topPx, bottomPx) { with(density) { onInsets(topPx.toDp().value, bottomPx.toDp().value) } }
    val current = steps.getOrNull(session.maneuverIndex)
    var showSensors by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(6_000); hint = false }
    val next = steps.getOrNull(session.maneuverIndex + 1)
    Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { topPx = it.height }.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NavGlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), baseColor = GuidanceTeal, contentColor = Color.White, dense = true, shadowElevation = 6.dp) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Icon(maneuverIcon(current?.maneuverType ?: com.rohanc.navgate.model.ManeuverType.Straight), null, Modifier.size(56.dp))
                Column(Modifier.weight(1f)) {
                    Text(session.distanceToManeuver.asMetersLabel(), style = MaterialTheme.typography.displaySmall)
                    Text(session.instruction, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (next != null && current?.maneuverType != com.rohanc.navgate.model.ManeuverType.Arrive) {
            NavGlassSurface(shape = RoundedCornerShape(20.dp), baseColor = GuidanceTeal, contentColor = Color.White, dense = true, shadowElevation = 3.dp) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Then", style = MaterialTheme.typography.titleMedium); Icon(maneuverIcon(next.maneuverType), null, Modifier.size(26.dp))
                }
            }
        }
        if (hint && driving) GlassPanel(shape = RoundedCornerShape(16.dp), weight = GlassWeight.Regular, shadow = false) {
            Text("Mount your phone safely. Spoken directions are on.", Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
        }
        val status = session.status
        if (status == SessionStatus.SignalLimited || status == SessionStatus.GuidanceUnavailable || status == SessionStatus.Rerouting || session.offRoute) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainerFallback(), shadowElevation = 4.dp) {
                Text(when {
                    status == SessionStatus.Rerouting -> "Finding a new route…"
                    else -> session.signalReason ?: "Waiting for a stronger location signal"
                }, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium, color = Color(0xFF3C1F00))
            }
        }
    }
    if (motion != null && showSensors) SensorHud(motion, Modifier.align(Alignment.CenterStart).padding(start = 12.dp))
    if (motion != null && driving && !showSensors) SpeedBubble(motion, { showSensors = true }, Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = with(density) { bottomPx.toDp() } + 14.dp))
    Column(Modifier.align(Alignment.CenterEnd).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GmMapButton(if (voiceOn) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff, if (voiceOn) "Mute spoken directions" else "Turn on spoken directions", a.toggleVoice)
        GmMapButton(Icons.Rounded.CameraAlt, "Camera guidance", a.openCamera)
        if (motion != null) GmMapButton(Icons.Rounded.Sensors, if (showSensors) "Hide sensor readings" else "Show sensor readings", { showSensors = !showSensors })
    }
    GmSheet(Modifier.align(Alignment.BottomCenter).onSizeChanged { bottomPx = it.height }) {
        Row(Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedIconButton(onClick = a.requestStop, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.Close, "Stop navigation") }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(session.remainingSeconds.asEtaLabel(), style = MaterialTheme.typography.headlineMedium)
                val arrival = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(System.currentTimeMillis() + (session.remainingSeconds * 1000).toLong()))
                Text("${session.remainingMeters.asMetersLabel()} · $arrival", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedIconButton(onClick = a.showSteps, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.FormatListBulleted, "Steps") }
        }
    }
}

@Composable
private fun ColorScheme.errorContainerFallback(): Color = Color(0xFFFFE0B2)

/** Shown once guidance reaches the destination. */
@Composable
fun BoxScope.ArrivalUi(destinationTitle: String, driving: Boolean, onInsets: (Float, Float) -> Unit, a: JourneyActions) {
    val density = LocalDensity.current
    GmSheet(Modifier.align(Alignment.BottomCenter).onSizeChanged { with(density) { onInsets(0f, it.height.toDp().value) } }) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Flag, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                Column { Text("You have arrived", style = MaterialTheme.typography.headlineSmall); Text(destinationTitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text(if (driving) "Your driving guidance is complete." else "Your walking guidance is complete.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            GmPrimaryButton("Done", Icons.Rounded.Check, a.finishArrival, Modifier.fillMaxWidth())
        }
    }
}
