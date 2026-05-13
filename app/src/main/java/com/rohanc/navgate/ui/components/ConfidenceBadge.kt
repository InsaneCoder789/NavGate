package com.rohanc.navgate.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rohanc.navgate.navigation.GuidanceConfidence
import com.rohanc.navgate.navigation.SignalConfidence
import com.rohanc.navgate.ui.theme.NavHighConfidence
import com.rohanc.navgate.ui.theme.NavLowConfidence
import com.rohanc.navgate.ui.theme.NavMediumConfidence

@Composable
fun ConfidenceBadge(confidence: SignalConfidence, modifier: Modifier = Modifier) {
    val accent = when (confidence.label) {
        GuidanceConfidence.High -> NavHighConfidence
        GuidanceConfidence.Medium -> NavMediumConfidence
        GuidanceConfidence.Low -> NavLowConfidence
    }
    Row(
        modifier =
            modifier
                .clip(CircleShape)
                .background(Color(0x991D1F24))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent),
        )
        Text(
            text = confidence.label.name,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = "•",
            color = Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = confidence.reason,
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
