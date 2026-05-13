package com.rohanc.navgate.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rohanc.navgate.model.TravelProfile
import com.rohanc.navgate.ui.state.RoutePreview
import com.rohanc.navgate.ui.theme.NavPrimary

@Composable
fun GoogleRoutePreviewSheet(
    preview: RoutePreview,
    travelProfile: TravelProfile,
    onStartNavigation: () -> Unit,
    onTravelProfileChanged: (TravelProfile) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = Color(0xFF1D1F24),
        shadowElevation = 16.dp
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header: Destination and Mode
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "To",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                    Text(
                        text = preview.destinationTitle,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${preview.originTitle} to ${preview.destinationTitle}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.55f)
                    )
                    Text(
                        text = "Fastest route now due to traffic",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF81C995)
                    )
                }
                
                // Travel Profile Toggle
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.1f)
                ) {
                    Row(modifier = Modifier.padding(4.dp)) {
                        ModeIcon(
                            icon = Icons.AutoMirrored.Rounded.DirectionsWalk,
                            selected = travelProfile == TravelProfile.Walking,
                            onClick = { onTravelProfileChanged(TravelProfile.Walking) }
                        )
                        ModeIcon(
                            icon = Icons.Rounded.DirectionsCar,
                            selected = travelProfile == TravelProfile.Driving,
                            onClick = { onTravelProfileChanged(TravelProfile.Driving) }
                        )
                    }
                }
            }

            // ETA and Distance
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = preview.etaLabel,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color(0xFF81C995),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "(${preview.distanceLabel})",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }

            Divider(color = Color.White.copy(alpha = 0.1f))

            // First Instruction Preview
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Rounded.Navigation,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = preview.firstInstruction,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.9f)
                )
            }

            // Start Button
            Button(
                onClick = onStartNavigation,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NavPrimary),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                Icon(Icons.Rounded.Navigation, contentDescription = null, tint = Color.Black)
                Spacer(Modifier.width(8.dp))
                Text("Start", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ModeIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(36.dp)
            .background(if (selected) NavPrimary else Color.Transparent, CircleShape)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) Color.Black else Color.White.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp)
        )
    }
}
