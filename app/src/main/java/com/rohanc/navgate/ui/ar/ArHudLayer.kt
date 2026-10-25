package com.rohanc.navgate.ui.ar

import androidx.compose.ui.draw.alpha
import androidx.compose.animation.core.RepeatMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

internal val ArBlue = Color(0xFF3D8BFF)
internal val ArIce = Color(0xFFA9CCFF)
internal val ArNavy = Color(0xFF0B1426)

internal fun turnIcon(kind: String?): ImageVector = when (kind) {
    "Left" -> Icons.Rounded.TurnLeft; "SlightLeft" -> Icons.Rounded.TurnSlightLeft; "SharpLeft" -> Icons.Rounded.TurnSharpLeft; "KeepLeft" -> Icons.Rounded.ForkLeft
    "Right" -> Icons.Rounded.TurnRight; "SlightRight" -> Icons.Rounded.TurnSlightRight; "SharpRight" -> Icons.Rounded.TurnSharpRight; "KeepRight" -> Icons.Rounded.ForkRight
    "UTurn" -> Icons.Rounded.UTurnLeft; "Roundabout", "RoundaboutExit" -> Icons.Rounded.RoundaboutRight; "Merge" -> Icons.Rounded.Merge; "Arrive" -> Icons.Rounded.Flag
    else -> Icons.Rounded.Straight
}

private fun turnTitle(kind: String?, exit: String?) = when (kind) {
    "Left" -> "Turn left"; "SlightLeft" -> "Bear left"; "SharpLeft" -> "Sharp left"; "KeepLeft" -> "Keep left"
    "Right" -> "Turn right"; "SlightRight" -> "Bear right"; "SharpRight" -> "Sharp right"; "KeepRight" -> "Keep right"
    "UTurn" -> "U-turn"; "Merge" -> "Merge"; "RoundaboutExit" -> "Leave roundabout"; "Arrive" -> "Arrive"
    "Roundabout" -> if (exit != null) "$exit exit" else "Roundabout"
    else -> "Continue"
}

/** Everything drawn over the camera in 2D: the scanning reticle while the ground is found, and the beacon standing on the turn. */
@Composable
internal fun BoxScope.ArTextLayer(hud: ArHud?, turnKind: String?, turnLabel: String?, distanceToTurnMeters: Double, @Suppress("UNUSED_PARAMETER") fpsMode: ArFpsMode, showStatus: Boolean = true, showBeacon: Boolean = true) {
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    Box(Modifier.matchParentSize().onSizeChanged { size = it }) {
        val x = hud?.turnX; val y = hud?.turnY
        if (showBeacon && x != null && y != null && size.width > 0) {
            val exit = turnLabel?.let { Regex("(\\d+(?:st|nd|rd|th)) exit").find(it)?.groupValues?.get(1) }
            TurnBeacon(x * size.width, y * size.height, size, turnKind, turnTitle(turnKind, exit), distanceToTurnMeters)
        }
        val message = hud?.tracking
        // The road is where it is, not where the phone points: when it lies well to one side, say so at that edge of the screen.
        val roadAngle = hud?.roadAngleDeg
        if (roadAngle != null && kotlin.math.abs(roadAngle) > 32f && size.height > 0) {
            RoadPointer(toRight = roadAngle > 0, Modifier.align(if (roadAngle > 0) Alignment.TopEnd else Alignment.TopStart).offset { IntOffset(0, (size.height * 0.42f).roundToInt()) })
        }
        if (message != null && showStatus) StatusCard(message, Modifier.align(Alignment.TopCenter).padding(top = 178.dp).padding(horizontal = 16.dp))
    }
}

/** A light beam standing on the turn with its label floating on top, so it reads as a place in the world. */
@Composable
private fun TurnBeacon(px: Float, py: Float, size: androidx.compose.ui.unit.IntSize, kind: String?, title: String, meters: Double) {
    val transition = rememberInfiniteTransition(label = "beacon")
    val pulse by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "pulse")
    val density = androidx.compose.ui.platform.LocalDensity.current
    val beam = with(density) { 110.dp.toPx() }
    val x = px.coerceIn(0f, size.width.toFloat()); val y = py.coerceIn(0f, size.height.toFloat())
    Canvas(Modifier.fillMaxSize()) {
        drawLine(Brush.verticalGradient(listOf(ArBlue.copy(alpha = 0f), ArBlue.copy(alpha = 0.85f)), startY = y - beam, endY = y), Offset(x, y - beam), Offset(x, y), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        val r = 10.dp.toPx() + 22.dp.toPx() * pulse
        drawOval(ArBlue.copy(alpha = 0.55f * (1f - pulse)), Offset(x - r, y - r * 0.42f), Size(r * 2, r * 0.84f), style = Stroke(2.dp.toPx()))
        drawOval(Color.White.copy(alpha = 0.95f), Offset(x - 7.dp.toPx(), y - 3.dp.toPx()), Size(14.dp.toPx(), 6.dp.toPx()))
    }
    var pill by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    Row(Modifier.offset { IntOffset((x - pill.width / 2f).roundToInt().coerceIn(8, (size.width - pill.width - 8).coerceAtLeast(8)), (y - beam - pill.height).roundToInt().coerceAtLeast(8)) }
        .onSizeChanged { pill = it }.clip(CircleShape).background(ArNavy.copy(alpha = 0.62f)).border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape).padding(5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(ArBlue), contentAlignment = Alignment.Center) { Icon(turnIcon(kind), null, Modifier.size(24.dp), tint = Color.White) }
    }
}

/** "The road is that way": a chevron on the edge of the screen the road lies toward, shown only while the line is out of view. */
@Composable
private fun RoadPointer(toRight: Boolean, modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "road").animateFloat(0.55f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "alpha")
    Row(modifier.padding(horizontal = 10.dp).alpha(pulse).clip(CircleShape).background(ArNavy.copy(alpha = 0.78f)).border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!toRight) Icon(Icons.Rounded.ChevronLeft, null, Modifier.size(22.dp), tint = Color.White)
        Text("Road", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (toRight) Icon(Icons.Rounded.ChevronRight, null, Modifier.size(22.dp), tint = Color.White)
    }
}

/** Tracking help in the same language as the direction card above it: a blue badge, a short title and one line of what to do. */
@Composable
private fun StatusCard(message: String, modifier: Modifier = Modifier) {
    val (title, hint) = when {
        message.startsWith("Aligning") -> "Finding the road" to "Hold the phone up and look along it"
        message.startsWith("Lining") -> "Lining up with the road" to "Walk a few steps forward"
        message.startsWith("Too dark") -> "Too dark for AR" to "Add light, or use the map"
        message.startsWith("Moving too fast") -> "Hold steady" to "Move the phone more slowly"
        message.startsWith("Point the camera") -> "Not enough to see" to "Point at the road and its surroundings"
        message.startsWith("Getting your bearings") -> "Getting your bearings" to "Move the phone slowly"
        message.startsWith("The camera is in use") -> "Camera busy" to "Close the other app using it"
        else -> message to ""
    }
    Row(modifier.fillMaxWidth().widthIn(max = 420.dp).clip(RoundedCornerShape(22.dp)).background(ArNavy.copy(alpha = 0.72f)).border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(22.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(ArBlue), contentAlignment = Alignment.Center) {
            if (message.startsWith("Aligning") || message.startsWith("Lining") || message.startsWith("Getting")) androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.5.dp)
            else androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.LightMode, null, Modifier.size(24.dp), tint = Color.White)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (hint.isNotEmpty()) Text(hint, color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp, maxLines = 2)
        }
    }
}
