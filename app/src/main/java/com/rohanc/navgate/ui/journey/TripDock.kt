package com.rohanc.navgate.ui.journey

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rohanc.navgate.core.MotionEstimate
import com.rohanc.navgate.ui.ar.ArCard
import com.rohanc.navgate.ui.ar.ArTokens
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/**
 * The footer of the 2D and 3D guidance, in the same glass as the AR and VR views: speed, arrival time, time left and distance in one row
 * that always fits (each figure shrinks before it would be cut), with Stop and Steps underneath.
 */
@Composable
fun TripDock(motion: StateFlow<MotionEstimate?>?, arrive: String, left: String, distance: String, onStop: () -> Unit, onSteps: () -> Unit, modifier: Modifier = Modifier) {
    ArCard(modifier.fillMaxWidth(), radius = 26.dp, body = 0.78f) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (motion != null) { DockSpeed(motion, Modifier.weight(1.1f)); DockDivider() }
                DockMetric("ARRIVE", arrive, Modifier.weight(1f)); DockDivider()
                DockMetric("LEFT", left, Modifier.weight(1f)); DockDivider()
                DockMetric("DISTANCE", distance, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DockButton(Icons.Rounded.Close, "End", "Stop navigation", onStop, Modifier.weight(1f))
                DockButton(Icons.Rounded.FormatListBulleted, "Steps", "Steps", onSteps, Modifier.weight(1f))
            }
        }
    }
}

@Composable private fun DockSpeed(motion: StateFlow<MotionEstimate?>, modifier: Modifier) {
    val e by motion.collectAsStateWithLifecycle()
    val kmh = ((e?.speedMps ?: 0.0) * 3.6).roundToInt()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        FitText("$kmh", 26, 15, FontWeight.Bold)
        Text("KM/H", color = ArTokens.onGlassMuted, fontSize = 10.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable private fun DockMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier.padding(horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = ArTokens.onGlassMuted, fontSize = 10.sp, letterSpacing = 1.0.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        FitText(value, 18, 11, FontWeight.SemiBold)
    }
}

/** One line of text that gets smaller, a point at a time, until it fits its space. */
@Composable private fun FitText(text: String, maxSp: Int, minSp: Int, weight: FontWeight) {
    var size by remember(text) { mutableIntStateOf(maxSp) }
    Text(text, color = ArTokens.onGlass, fontSize = size.sp, fontWeight = weight, maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
        onTextLayout = { if (it.hasVisualOverflow && size > minSp) size -= 1 })
}

@Composable private fun DockDivider() = Box(Modifier.width(1.dp).height(30.dp).background(ArTokens.hairline))

@Composable private fun DockButton(icon: ImageVector, label: String, description: String, onClick: () -> Unit, modifier: Modifier) {
    Row(modifier.height(44.dp).semantics(mergeDescendants = true) { contentDescription = description; role = Role.Button }.clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = 0.14f)).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(20.dp), tint = Color.White); Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
