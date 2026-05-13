package com.rohanc.navgate.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Star
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
fun GoogleSearchResultsList(
    places: List<PlaceSearchResult>,
    onPlaceClick: (PlaceSearchResult) -> Unit,
    onDirectionsClick: (PlaceSearchResult) -> Unit,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier
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
            
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = NavPrimary, strokeWidth = 2.dp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Searching places",
                            color = Color.White.copy(alpha = 0.55f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(places) { place ->
                        SearchResultItem(
                            place = place,
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
        }
    }
}

@Composable
private fun SearchResultItem(
    place: PlaceSearchResult,
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
                    Icons.Rounded.LocationOn,
                    contentDescription = null,
                    tint = NavPrimary,
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
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "4.5",
                    style = MaterialTheme.typography.labelSmall,
                    color = NavPrimary
                )
                Icon(
                    Icons.Rounded.Star,
                    contentDescription = null,
                    tint = NavPrimary,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = "• 12 min",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.4f)
                )
            }
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
