package com.rohanc.navgate.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rohanc.navgate.model.PlaceSearchResult
import com.rohanc.navgate.ui.theme.NavPrimary

@Composable
fun GoogleCollectionsSheet(
    title: String,
    subtitle: String,
    places: List<PlaceSearchResult>,
    onPlaceClick: (PlaceSearchResult) -> Unit,
    onDirectionsClick: (PlaceSearchResult) -> Unit,
    isHistory: Boolean = false,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xFF1D1F24),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        shadowElevation = 16.dp
    ) {
        Column(modifier = Modifier.padding(top = 12.dp)) {
            // Drag Handle
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .background(Color.White.copy(alpha = 0.2f), CircleShape)
                    .align(Alignment.CenterHorizontally)
            )

            PaddingValues(16.dp).let {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = NavPrimary, strokeWidth = 2.dp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Loading places",
                            color = Color.White.copy(alpha = 0.55f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else if (places.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No items yet",
                        color = Color.White.copy(alpha = 0.4f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = if (footer != null) 12.dp else 24.dp)
                ) {
                    items(places) { place ->
                        CollectionItem(
                            place = place,
                            isHistory = isHistory,
                            onClick = { onPlaceClick(place) },
                            onDirectionsClick = { onDirectionsClick(place) }
                        )
                        Divider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = Color.White.copy(alpha = 0.05f)
                        )
                    }
                }
            }

            if (footer != null) {
                Divider(color = Color.White.copy(alpha = 0.06f))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    footer()
                }
            }
        }
    }
}

@Composable
private fun CollectionItem(
    place: PlaceSearchResult,
    isHistory: Boolean,
    onClick: () -> Unit,
    onDirectionsClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.1f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (isHistory) Icons.Rounded.History else Icons.Rounded.Bookmark,
                    contentDescription = null,
                    tint = if (isHistory) Color.White.copy(alpha = 0.6f) else NavPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = place.title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = place.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(
            onClick = onDirectionsClick,
            modifier = Modifier.background(Color.White.copy(alpha = 0.05f), CircleShape)
        ) {
            Icon(
                Icons.Rounded.Directions,
                contentDescription = "Directions",
                tint = NavPrimary
            )
        }
    }
}
