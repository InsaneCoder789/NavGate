package com.rohanc.navgate.ui.journey

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rohanc.navgate.ui.ar.ArBlue
import com.rohanc.navgate.ui.ar.ArTripCard
import com.rohanc.navgate.ui.ar.CameraPreview
import com.rohanc.navgate.ui.ar.turnIcon
import kotlin.math.roundToInt

/** The street the next turn leads to, floating where it lies ahead; [xFraction] places it across the picture from the phone's compass. */
data class LiveBubble(val title: String, val distance: String, val kind: String?, val xFraction: Float)

/** The camera picture with a round bite taken out of its lower part, where the live map shows through (used when ARCore is unavailable). */
private object DomeShape : Shape {
    fun oval(w: Float, h: Float): Rect { val top = h * DOME_TOP; val ry = (h - top) * 1.12f; return Rect(-w * 0.5f, top, w * 1.5f, top + 2 * ry) }
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val full = Path().apply { addRect(Rect(0f, 0f, size.width, size.height)) }
        val bite = Path().apply { addOval(oval(size.width, size.height)) }
        return Outline.Generic(Path.combine(PathOperation.Difference, full, bite))
    }
}

/** Live view: the road ahead through the camera, the map curving up from the bottom, and the next street hanging in the air. */
@Composable
fun BoxScope.LiveCameraLayer(bubble: LiveBubble?, tripEta: String?, tripLeft: String, tripDistance: String, ar: (@Composable (Modifier, Float) -> Unit)? = null, rawStatusMessage: String? = null) {
    // A heading that wobbles round a threshold must not make the card blink on and off.
    val statusMessage = com.rohanc.navgate.ui.ar.rememberStableStatus(rawStatusMessage)
    var box by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var statusBottom by remember { mutableStateOf(0) }
    var tripHeight by remember { mutableStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(statusMessage) { if (statusMessage == null) statusBottom = 0 }
    // The road ahead through the camera with its line drawn on it; the live 2D map curves up from the bottom, zoomed on you and the road you will take.
    Box(Modifier.matchParentSize().onSizeChanged { box = it }) {
        if (ar != null) ar(Modifier.fillMaxSize(), DOME_TOP) else CameraPreview(Modifier.fillMaxSize().clip(DomeShape))
        Canvas(Modifier.fillMaxSize()) {
            val o = DomeShape.oval(size.width, size.height)
            // A soft shadow under the rim lifts the map off the camera picture instead of cutting it with a hard edge.
            drawArc(Color.Black.copy(alpha = 0.10f), 180f, 180f, false, o.topLeft.copy(y = o.top + 3.dp.toPx()), o.size, style = Stroke(16.dp.toPx()))
            drawArc(Color.Black.copy(alpha = 0.16f), 180f, 180f, false, o.topLeft.copy(y = o.top + 2.dp.toPx()), o.size, style = Stroke(9.dp.toPx()))
            drawArc(Color.White, 180f, 180f, false, o.topLeft, o.size, style = Stroke(5.dp.toPx()))
        }
        if (statusMessage != null) com.rohanc.navgate.ui.ar.StatusCard(statusMessage,
            Modifier.align(Alignment.TopCenter).onSizeChanged { statusBottom = it.height }.statusBarsPadding().padding(top = 56.dp, start = 96.dp, end = 14.dp))
        val bubbleTop = maxOf((box.height * 0.24f).roundToInt(), statusBottom + with(density) { 12.dp.roundToPx() })
        val available = box.height - tripHeight - bubbleTop - with(density) { 12.dp.roundToPx() }
        // In a short viewport, tracking help and escape controls take precedence over a floating decoration.
        if (bubble != null && box.width > 0 && available >= with(density) { 80.dp.roundToPx() }) {
            var w by remember { mutableStateOf(0) }
            val density = androidx.compose.ui.platform.LocalDensity.current
            // Kept inside the picture with a real margin (16 dp each side; the right one also clears the AR badge's column), whatever the text size.
            val edge = with(density) { 16.dp.roundToPx() }; val maxW = with(density) { (box.width.toDp() - 32.dp) }
            Column(Modifier.widthIn(max = maxW).offset { IntOffset((bubble.xFraction * box.width - w / 2f).roundToInt().coerceIn(edge, (box.width - w - edge).coerceAtLeast(edge)), bubbleTop) }
                .heightIn(max = with(density) { available.toDp() }).onSizeChanged { w = it.width }.testTag("liveBubble"), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.clip(RoundedCornerShape(20.dp)).background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF3D8BFF), Color(0xFF2457D6)))).padding(horizontal = 16.dp, vertical = 10.dp).testTag("liveBubbleBody"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(turnIcon(bubble.kind), null, Modifier.size(28.dp).testTag("liveBubbleIcon"), tint = Color.White)
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(bubble.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 240.dp))
                        Text(bubble.distance, color = Color.White.copy(alpha = 0.88f), fontSize = 15.sp)
                    }
                }
                Canvas(Modifier.size(18.dp, 10.dp)) { drawPath(Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2, size.height); close() }, Color(0xFF2457D6)) }
            }
        }
        if (tripEta != null) ArTripCard(tripEta, tripLeft, tripDistance, Modifier.align(Alignment.BottomCenter).onSizeChanged { tripHeight = it.height }.navigationBarsPadding().padding(horizontal = 14.dp).padding(bottom = 104.dp).testTag("arTripCard"))
    }
}

private const val DOME_TOP = 0.56f

/** Persistent escape and voice actions: camera styling must not remove normal navigation controls. */
@Composable
internal fun BoxScope.LiveGuidanceControls(voiceOn: Boolean, alignment: com.rohanc.navgate.ui.ar.AlignmentStatus, a: JourneyActions) {
    Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 14.dp, vertical = 16.dp).testTag("liveNavigationControls"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        com.rohanc.navgate.ui.ar.ArControl(androidx.compose.material.icons.Icons.Rounded.Close, "Stop navigation", a.requestStop)
        com.rohanc.navgate.ui.ar.ArControl(androidx.compose.material.icons.Icons.Rounded.Map, "Return to map", a.closeCamera, primary = true)
        com.rohanc.navgate.ui.ar.ArControl(if (voiceOn) androidx.compose.material.icons.Icons.Rounded.VolumeUp else androidx.compose.material.icons.Icons.Rounded.VolumeOff,
            if (voiceOn) "Mute spoken cues" else "Turn on spoken cues", a.toggleVoice)
    }
}
