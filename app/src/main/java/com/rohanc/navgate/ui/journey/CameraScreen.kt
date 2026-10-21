package com.rohanc.navgate.ui.journey

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import com.rohanc.navgate.ui.ar.AlignmentLevel
import com.rohanc.navgate.ui.ar.AlignmentStatus

/** Camera guidance: the live camera is drawn behind this by the app; this adds the direction cue and controls. */
@Composable
fun BoxScope.CameraScreen(hasCameraPermission: Boolean, cuesAvailable: Boolean, alignment: AlignmentStatus, instruction: String, distanceLabel: String,
    voiceOn: Boolean, a: JourneyActions) {
    if (!hasCameraPermission) {
        GlassPage("Camera guidance", a.closeCamera) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Allow camera access to see compass cues over the live view.", style = MaterialTheme.typography.bodyLarge)
                GmPrimaryButton("Allow camera", Icons.Rounded.CameraAlt, a.requestCamera, Modifier.fillMaxWidth())
                TextButton(onClick = a.openAppSettings) { Text("Camera permission settings") }
            }
        }
        return
    }
    GlassPanel(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp).fillMaxWidth(), shape = RoundedCornerShape(28.dp), weight = GlassWeight.Regular) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text(distanceLabel, style = MaterialTheme.typography.headlineMedium)
            Text(instruction, style = MaterialTheme.typography.titleMedium)
        }
    }
    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassPanel(shape = CircleShape, weight = GlassWeight.Thin) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
                Text(if (cuesAvailable) alignment.label else "Camera cues paused until location is accurate.", style = MaterialTheme.typography.titleMedium)
            }
        }
        if (cuesAvailable && alignment.level != AlignmentLevel.Recover) {
            Icon(Icons.Rounded.Navigation, "Direction of route", Modifier.size(88.dp).rotate(alignment.deltaDegrees.toFloat()), tint = MaterialTheme.colorScheme.primary)
        }
        Text("Compass guidance only; it does not detect obstacles.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
    GlassPanel(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp).fillMaxWidth(), shape = RoundedCornerShape(32.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedIconButton(onClick = a.requestStop, modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.Close, "Stop navigation") }
            GmPrimaryButton("Return to map", Icons.Rounded.Map, a.closeCamera, Modifier.weight(1f))
            OutlinedIconButton(onClick = a.toggleVoice, modifier = Modifier.size(52.dp)) { Icon(if (voiceOn) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff, if (voiceOn) "Mute spoken cues" else "Turn on spoken cues") }
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
