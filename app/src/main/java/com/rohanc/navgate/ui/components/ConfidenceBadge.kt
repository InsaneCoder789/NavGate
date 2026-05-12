package com.rohanc.navgate.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rohanc.navgate.navigation.GuidanceConfidence
import com.rohanc.navgate.navigation.SignalConfidence

@Composable
fun ConfidenceBadge(confidence: SignalConfidence, modifier: Modifier = Modifier) {
    val background = when (confidence.label) {
        GuidanceConfidence.High -> Color(0xFF133E31)
        GuidanceConfidence.Medium -> Color(0xFF5C4610)
        GuidanceConfidence.Low -> Color(0xFF5A1821)
    }
    val accent = when (confidence.label) {
        GuidanceConfidence.High -> Color(0xFF75F0C6)
        GuidanceConfidence.Medium -> Color(0xFFFFD166)
        GuidanceConfidence.Low -> Color(0xFFFF8B9A)
    }
    Row(
        modifier =
            modifier
                .background(background, RoundedCornerShape(999.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = confidence.label.name,
            color = accent,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = confidence.reason,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
