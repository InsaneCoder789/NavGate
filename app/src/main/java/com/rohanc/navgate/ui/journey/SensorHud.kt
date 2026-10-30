package com.rohanc.navgate.ui.journey

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rohanc.navgate.core.CalibrationState
import com.rohanc.navgate.core.MotionEstimate
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

private const val G = 9.80665

/** Live speed, acceleration (as a g-force ring) and heading from the fused sensors, with the calibration state. */
@Composable
fun SensorHud(motion: StateFlow<MotionEstimate?>, modifier: Modifier = Modifier) {
    val e by motion.collectAsStateWithLifecycle()
    val m = e ?: return
    GlassPanel(modifier.width(112.dp), shape = RoundedCornerShape(24.dp), weight = GlassWeight.Regular) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("${(m.speedMps * 3.6).roundToInt()}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text("km/h", Modifier.padding(bottom = 5.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            GForceRing(m.forwardAccel / G, m.lateralAccel / G)
            Text("%.2f g".format(kotlin.math.hypot(m.forwardAccel, m.lateralAccel) / G), style = MaterialTheme.typography.labelMedium)
            Text(m.headingDegrees?.let { "${compass(it)} ${it.roundToInt()}°" } ?: "— —", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val (label, color) = when (m.calibration) {
                CalibrationState.Calibrating -> "Calibrating…" to MaterialTheme.colorScheme.onSurfaceVariant
                CalibrationState.Good -> "Sensors OK" to MaterialTheme.colorScheme.tertiary
                CalibrationState.CompassNeedsFigureEight -> "Wave phone in a figure 8" to MaterialTheme.colorScheme.error
            }
            Text(label, style = MaterialTheme.typography.labelSmall, color = color)
            if (m.usingDeadReckoning) Text("GPS lost · using motion", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun GForceRing(forwardG: Double, lateralG: Double) {
    val ring = MaterialTheme.colorScheme.outlineVariant
    val dot = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(64.dp)) {
        val c = Offset(size.width / 2, size.height / 2); val r = size.minDimension / 2 - 4f
        drawCircle(ring, r, c, style = Stroke(2f)); drawCircle(ring.copy(alpha = 0.5f), r / 2, c, style = Stroke(1.5f))
        drawLine(ring.copy(alpha = 0.6f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.5f); drawLine(ring.copy(alpha = 0.6f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.5f)
        // Full ring is 0.5 g: acceleration pushes the dot forward (up), braking back, a right turn to the right.
        val x = (lateralG / 0.5).coerceIn(-1.0, 1.0).toFloat() * r; val y = (-forwardG / 0.5).coerceIn(-1.0, 1.0).toFloat() * r
        drawCircle(dot.copy(alpha = 0.25f), 9f, Offset(c.x + x, c.y + y)); drawCircle(dot, 5.5f, Offset(c.x + x, c.y + y)); drawCircle(Color.White, 5.5f, Offset(c.x + x, c.y + y), style = Stroke(1.5f))
    }
}

private fun compass(degrees: Double): String = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((degrees % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]
