package com.rohanc.navgate.ui.ar

import androidx.compose.ui.draw.alpha
import androidx.compose.animation.core.RepeatMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.testTag
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
internal fun BoxScope.ArTextLayer(hud: ArHud?, turnKind: String?, turnLabel: String?, distanceToTurnMeters: Double, @Suppress("UNUSED_PARAMETER") fpsMode: ArFpsMode, showStatus: Boolean = true, showBeacon: Boolean = true, statusTopDp: Float = 178f, statusBottomDp: Float = 0f) {
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var statusBottom by remember { mutableIntStateOf(0) }
    var pointerHeight by remember { mutableIntStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(Modifier.matchParentSize().onSizeChanged { size = it }) {
        val x = hud?.turnX; val y = hud?.turnY
        if (showBeacon && x != null && y != null && size.width > 0) {
            val exit = turnLabel?.let { Regex("(\\d+(?:st|nd|rd|th)) exit").find(it)?.groupValues?.get(1) }
            TurnBeacon(x * size.width, y * size.height, size, turnKind, turnTitle(turnKind, exit), distanceToTurnMeters)
        }
        val message = rememberStableStatus(hud?.tracking)
        // The road is where it is, not where the phone points: when it lies well to one side, say so at that edge of the screen.
        val roadAngle = hud?.roadAngleDeg
        val pointerBottom = size.height - with(density) { statusBottomDp.dp.roundToPx() }
        val pointerFits = !showStatus || message == null || statusBottom + pointerHeight + with(density) { 24.dp.roundToPx() } <= pointerBottom
        if (roadAngle != null && kotlin.math.abs(roadAngle) > 32f && size.height > 0 && pointerFits) {
            RoadPointer(toRight = roadAngle > 0, Modifier.align(if (roadAngle > 0) Alignment.TopEnd else Alignment.TopStart).onSizeChanged { pointerHeight=it.height }.offset {
                val gap=with(density){12.dp.roundToPx()}
                val desired=maxOf((size.height * 0.42f).roundToInt(),if(showStatus && message!=null)statusBottom+gap else 0)
                IntOffset(0, desired.coerceAtMost((pointerBottom-pointerHeight-gap).coerceAtLeast(0)))
            })
        }
        if (message != null && showStatus) StatusCard(message, Modifier.align(Alignment.TopCenter).onSizeChanged { statusBottom=it.height }.padding(top = statusTopDp.dp).padding(start = 92.dp, end = 16.dp)
            .heightIn(max = (with(density){size.height.toDp().value}-statusTopDp-statusBottomDp-12f).coerceAtLeast(0f).dp).verticalScroll(rememberScrollState()))
    }
}

/** A light beam standing on the turn with its label floating on top, so it reads as a place in the world. */
@Composable
internal fun TurnBeacon(px: Float, py: Float, size: androidx.compose.ui.unit.IntSize, kind: String?, title: String, meters: Double) {
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
    Row(Modifier.offset { val edge = 12.dp.roundToPx(); IntOffset((x - pill.width / 2f).roundToInt().coerceIn(edge, (size.width - pill.width - edge).coerceAtLeast(edge)), (y - beam - pill.height).roundToInt().coerceAtLeast(edge)) }
        .onSizeChanged { pill = it }.testTag("turnBeacon").clip(CircleShape).background(ArNavy.copy(alpha = 0.62f)).border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape).padding(5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(ArBlue), contentAlignment = Alignment.Center) { Icon(turnIcon(kind), null, Modifier.size(24.dp), tint = Color.White) }
    }
}

/** "The road is that way": a chevron on the edge of the screen the road lies toward, shown only while the line is out of view. */
@Composable
internal fun RoadPointer(toRight: Boolean, modifier: Modifier = Modifier) {

    Row(modifier.padding(horizontal = 10.dp).testTag("roadPointer").clip(CircleShape).background(ArNavy.copy(alpha = 0.78f)).border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!toRight) Icon(Icons.Rounded.ChevronLeft, null, Modifier.size(22.dp), tint = Color.White)
        Text("Road", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (toRight) Icon(Icons.Rounded.ChevronRight, null, Modifier.size(22.dp), tint = Color.White)
    }
}

/** Tracking help in the same language as the direction card above it: a blue badge, a short title and one line of what to do. */
@Composable
internal fun StatusCard(message: String, modifier: Modifier = Modifier) {
    val view = statusView(message); val title = view.title; val hint = view.hint
    Row(modifier.testTag("arStatus").fillMaxWidth().widthIn(max = 420.dp).clip(RoundedCornerShape(22.dp)).background(ArNavy.copy(alpha = 0.72f)).border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(22.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(ArBlue), contentAlignment = Alignment.Center) {
            // The spinner is centred in its own square, the same size as the icon it replaces, so the card does not shift when the state changes.
            if (view.spinner) androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.5.dp)
            else androidx.compose.material3.Icon(view.icon, null, Modifier.size(24.dp), tint = Color.White)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (hint.isNotEmpty()) Text(hint, color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp)
        }
    }
}
