package com.rohanc.navgate.ui.journey

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
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
 * The footer of the 2D and 3D guidance: the minutes left as the headline (Sora), arrival time and distance under it, speed beside it when driving,
 * then End and Steps. Light is the porcelain gradient and dark the clear glass, like every other panel; each figure shrinks before it would be cut.
 */
@Composable
fun TripDock(motion: StateFlow<MotionEstimate?>?, arrive: String, left: String, distance: String, onStop: () -> Unit, onSteps: () -> Unit, modifier: Modifier = Modifier) {
    GlassPanel(modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), weight = GlassWeight.Thick, minOpacity = 0.8f) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    DockHeadline(left)
                    Text("Arrive $arrive  \u00B7  $distance", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                if (motion != null) DockSpeed(motion, Modifier.width(72.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DockButton(Icons.Rounded.Close, "End", "Stop navigation", onStop, Modifier.weight(1f), end = true)
                DockButton(Icons.Rounded.FormatListBulleted, "Steps", "Steps", onSteps, Modifier.weight(1f), end = false)
            }
        }
    }
}

/** The time left, as large as the space allows (Sora), shrinking a point at a time before it would be cut. */
@Composable private fun DockHeadline(text: String) {
    var size by remember(text) { mutableIntStateOf(30) }
    Text(text, color = MaterialTheme.colorScheme.onSurface, fontSize = size.sp, fontFamily = com.rohanc.navgate.ui.theme.NavDisplay, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
        onTextLayout = { if (it.hasVisualOverflow && size > 16) size -= 1 })
}

@Composable private fun DockSpeed(motion: StateFlow<MotionEstimate?>, modifier: Modifier) {
    val e by motion.collectAsStateWithLifecycle()
    val kmh = ((e?.speedMps ?: 0.0) * 3.6).roundToInt()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        FitText("$kmh", 26, 15, FontWeight.Bold)
        Text("KM/H", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)
    }
}


/** One line of text that gets smaller, a point at a time, until it fits its space. */
@Composable private fun FitText(text: String, maxSp: Int, minSp: Int, weight: FontWeight) {
    var size by remember(text) { mutableIntStateOf(maxSp) }
    Text(text, color = MaterialTheme.colorScheme.onSurface, fontSize = size.sp, fontWeight = weight, maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
        onTextLayout = { if (it.hasVisualOverflow && size > minSp) size -= 1 })
}


@Composable private fun DockButton(icon: ImageVector, label: String, description: String, onClick: () -> Unit, modifier: Modifier, end: Boolean) {
    val dark = com.rohanc.navgate.ui.theme.NavTokens.isDark()
    val coral = com.rohanc.navgate.ui.theme.NavTokens.coral()
    val face = when {
        end -> Modifier.background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(coral, Color(0xFFE5484D))))
        dark -> Modifier.background(Color.White.copy(alpha = 0.12f))
        else -> Modifier.background(com.rohanc.navgate.ui.theme.NavTokens.porcelain()).border(1.dp, com.rohanc.navgate.ui.theme.NavTokens.PorcelainEdge, RoundedCornerShape(24.dp))
    }
    val ink = if (end) Color.White else MaterialTheme.colorScheme.onSurface
    Row(modifier.height(48.dp).semantics(mergeDescendants = true) { contentDescription = description; role = Role.Button }.clip(RoundedCornerShape(24.dp)).then(face).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(20.dp), tint = ink); Spacer(Modifier.width(8.dp))
        Text(label, color = ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
    }
}
