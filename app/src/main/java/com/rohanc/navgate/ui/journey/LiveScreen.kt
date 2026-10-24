package com.rohanc.navgate.ui.journey

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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
fun BoxScope.LiveCameraLayer(bubble: LiveBubble?, tripEta: String?, tripLeft: String, tripDistance: String, ar: (@Composable (Modifier, Float) -> Unit)? = null) {
    var box by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // The road ahead through the camera with its line drawn on it; the live 2D map curves up from the bottom, zoomed on you and the road you will take.
    Box(Modifier.matchParentSize().onSizeChanged { box = it }) {
        if (ar != null) ar(Modifier.fillMaxSize(), DOME_TOP) else CameraPreview(Modifier.fillMaxSize().clip(DomeShape))
        Canvas(Modifier.fillMaxSize()) {
            val o = DomeShape.oval(size.width, size.height)
            drawArc(Color.White, 180f, 180f, false, o.topLeft, o.size, style = Stroke(5.dp.toPx()))
        }
        if (bubble != null && box.width > 0) {
            var w by remember { mutableStateOf(0) }
            Column(Modifier.offset { IntOffset((bubble.xFraction * box.width - w / 2f).roundToInt().coerceIn(12, (box.width - w - 12).coerceAtLeast(12)), (box.height * 0.24f).roundToInt()) }
                .onSizeChanged { w = it.width }, horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.clip(RoundedCornerShape(20.dp)).background(ArBlue).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(turnIcon(bubble.kind), null, Modifier.size(28.dp), tint = Color.White)
                    Column {
                        Text(bubble.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.widthIn(max = 240.dp))
                        Text(bubble.distance, color = Color.White.copy(alpha = 0.88f), fontSize = 15.sp)
                    }
                }
                Canvas(Modifier.size(18.dp, 10.dp)) { drawPath(Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2, size.height); close() }, ArBlue) }
            }
        }
        if (tripEta != null) ArTripCard(tripEta, tripLeft, tripDistance, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 14.dp, vertical = 12.dp))
    }
}

private const val DOME_TOP = 0.56f
