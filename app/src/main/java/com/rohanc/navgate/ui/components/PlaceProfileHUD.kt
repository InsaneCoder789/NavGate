package com.rohanc.navgate.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rohanc.navgate.model.PlaceSearchResult
import com.rohanc.navgate.ui.theme.NavPrimary

@Composable
fun GooglePlaceProfileSheet(
    place: PlaceSearchResult,
    isSaved: Boolean,
    onDismiss: () -> Unit,
    onToggleSaved: () -> Unit,
    onSelectOrigin: () -> Unit,
    onSelectDestination: () -> Unit,
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
                .padding(bottom = 24.dp)
                .navigationBarsPadding()
        ) {
            // Drag Handle
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .background(Color.White.copy(alpha = 0.2f), CircleShape)
                    .align(Alignment.CenterHorizontally)
            )

            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = place.title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "4.8",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NavPrimary
                            )
                            Icon(
                                Icons.Rounded.Star,
                                contentDescription = null,
                                tint = NavPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "• ${place.type.name} • ${place.city ?: "Nearby"}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }
                    }
                    
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.1f), CircleShape)
                            .size(32.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Close",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onSelectDestination,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = NavPrimary)
                    ) {
                        Icon(Icons.Rounded.Directions, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Directions", color = Color.Black)
                    }

                    OutlinedButton(
                        onClick = onSelectOrigin,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                    ) {
                        Icon(Icons.Rounded.MyLocation, contentDescription = null, tint = NavPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text("Set start", color = Color.White)
                    }

                    IconButton(
                        onClick = onToggleSaved,
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                            .size(48.dp)
                    ) {
                        Icon(
                            if (isSaved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                            contentDescription = "Save",
                            tint = if (isSaved) NavPrimary else Color.White
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                // Place Details Preview
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Surface(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        color = Color.White.copy(alpha = 0.05f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Image, contentDescription = null, tint = Color.White.copy(alpha = 0.2f))
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = place.subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.8f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Open • Closes 10 PM",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF81C995)
                        )
                    }
                }
            }
        }
    }
}
