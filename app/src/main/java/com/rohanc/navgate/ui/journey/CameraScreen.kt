package com.rohanc.navgate.ui.journey

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rohanc.navgate.ui.ar.AlignmentLevel
import com.rohanc.navgate.ui.ar.AlignmentStatus

/** Camera guidance: the live camera is drawn behind this by the app; this adds the direction cue and controls. */
@Composable
fun BoxScope.CameraScreen(hasCameraPermission: Boolean, cuesAvailable: Boolean, alignment: AlignmentStatus, instruction: String, distanceLabel: String,
    voiceOn: Boolean, turnKind: String?, a: JourneyActions, nextKind: String? = null, tripEta: String? = null, tripLeft: String = "", tripDistance: String = "") {
    if (!hasCameraPermission) {
        GlassPage("Camera guidance", a.closeCamera) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Allow camera access to see compass cues over the live view.", style = MaterialTheme.typography.bodyLarge)
                NgPrimaryButton("Allow camera", Icons.Rounded.CameraAlt, a.requestCamera, Modifier.fillMaxWidth())
                TextButton(onClick = a.openAppSettings) { Text("Camera permission settings") }
            }
        }
        return
    }
    // The reminder is shown once, then fades away so it never sits over the road.
    var reminder by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(7_000); reminder = false }
    Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        com.rohanc.navgate.ui.ar.ArTurnCard(turnKind, distanceLabel, instruction, nextKind)
        androidx.compose.animation.AnimatedVisibility(reminder, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
            Text("Guidance only. Keep watching where you walk.", Modifier.clip(RoundedCornerShape(14.dp)).background(com.rohanc.navgate.ui.ar.ArNavy.copy(alpha = 0.5f)).padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f))
        }
    }
    Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 14.dp).padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (tripEta != null) com.rohanc.navgate.ui.ar.ArTripCard(tripEta, tripLeft, tripDistance)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            com.rohanc.navgate.ui.ar.ArControl(Icons.Rounded.Close, "Stop navigation", a.requestStop)
            com.rohanc.navgate.ui.ar.ArControl(Icons.Rounded.Map, "Return to map", a.closeCamera, primary = true)
            com.rohanc.navgate.ui.ar.ArControl(if (voiceOn) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff, if (voiceOn) "Mute spoken cues" else "Turn on spoken cues", a.toggleVoice)
        }
    }
}

/** The full list of steps for the current route, opened from the navigation or directions screens. */
@Composable
fun BoxScope.StepsScreen(steps: List<com.rohanc.navgate.model.RouteStep>, currentIndex: Int, originLabel: String, a: JourneyActions) {
    GlassPage("Steps", a.back) {
        if (steps.isEmpty()) Text("Plan a route to see its steps.", Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
        else Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) { StepsList(steps, originLabel, currentIndex) }
    }
}
