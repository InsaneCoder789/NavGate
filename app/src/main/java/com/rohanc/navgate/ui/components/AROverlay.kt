package com.rohanc.navgate.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rohanc.navgate.ui.ar.AlignmentLevel
import com.rohanc.navgate.ui.ar.AlignmentStatus
import com.rohanc.navgate.ui.theme.NavHighConfidence
import com.rohanc.navgate.ui.theme.NavLowConfidence
import com.rohanc.navgate.ui.theme.NavMediumConfidence

@Composable
fun GlassAlignmentRing(alignment: AlignmentStatus) {
    val color = when (alignment.level) {
        AlignmentLevel.Aligned -> NavHighConfidence
        AlignmentLevel.Adjust -> NavMediumConfidence
        AlignmentLevel.Recover -> NavLowConfidence
    }
    
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(240.dp)) {
        // Outer Glass Ring
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawArc(
                color = Color.White.copy(alpha = 0.1f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = 12f, cap = StrokeCap.Round)
            )
            
            val clamped = alignment.deltaDegrees.coerceIn(-90.0, 90.0).toFloat()
            drawArc(
                color = color,
                startAngle = 270f + clamped - 26f,
                sweepAngle = 52f,
                useCenter = false,
                style = Stroke(width = 14f, cap = StrokeCap.Round)
            )
        }
        
        // Inner Glass Circle
        Surface(
            modifier = Modifier.size(140.dp),
            shape = CircleShape,
            color = Color(0x33000000),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Rounded.Explore,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(42.dp)
                )
                Text(
                    text = "${alignment.deltaDegrees.toInt()}°",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun RouteRibbon(
    modifier: Modifier = Modifier,
    alignment: AlignmentStatus,
    distanceMeters: Double
) {
    val ribbonColor = when (alignment.level) {
        AlignmentLevel.Aligned -> NavHighConfidence
        AlignmentLevel.Adjust -> NavMediumConfidence
        AlignmentLevel.Recover -> NavLowConfidence
    }
    
    Canvas(modifier = modifier) {
        val centerX = size.width / 2f
        val startY = size.height
        val endY = size.height * 0.1f
        
        val horizontalOffset = (alignment.deltaDegrees.coerceIn(-60.0, 60.0) / 60.0 * (size.width * 0.3)).toFloat()
        
        // Background path
        drawLine(
            color = Color.White.copy(alpha = 0.05f),
            start = androidx.compose.ui.geometry.Offset(centerX, startY),
            end = androidx.compose.ui.geometry.Offset(centerX, endY),
            strokeWidth = 40f,
            cap = StrokeCap.Round
        )
        
        // Active path
        drawLine(
            color = ribbonColor.copy(alpha = 0.8f),
            start = androidx.compose.ui.geometry.Offset(centerX, startY),
            end = androidx.compose.ui.geometry.Offset(centerX + horizontalOffset, endY),
            strokeWidth = 24f,
            cap = StrokeCap.Round
        )
    }
}
