package com.rohanc.navgate.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rohanc.navgate.ui.theme.GlassStroke
import com.rohanc.navgate.ui.theme.NavPrimary

@Composable
fun RoutePlannerRow(
    originTitle: String,
    destinationTitle: String,
    onOriginClick: () -> Unit,
    onDestinationClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PlannerPill(
            label = "From",
            value = originTitle,
            icon = Icons.Rounded.MyLocation,
            modifier = Modifier.weight(1f),
            onClick = onOriginClick,
        )
        PlannerPill(
            label = "To",
            value = destinationTitle,
            icon = Icons.Rounded.Flag,
            modifier = Modifier.weight(1f),
            onClick = onDestinationClick,
        )
    }
}

@Composable
private fun PlannerPill(
    label: String,
    value: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = Color(0xCC1D1F24),
        border = androidx.compose.foundation.BorderStroke(1.dp, GlassStroke),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = label, tint = NavPrimary, modifier = Modifier.size(18.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.56f),
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    maxLines = 1,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoogleSearchBox(
    query: String,
    onQueryChange: (String) -> Unit,
    onMenuClick: () -> Unit,
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        shape = CircleShape,
        color = Color(0xCC1D1F24), // Google Maps dark search bar color
        border = androidx.compose.foundation.BorderStroke(1.dp, GlassStroke),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onMenuClick) {
                Icon(
                    Icons.Rounded.Menu,
                    contentDescription = "Menu",
                    tint = Color.White.copy(alpha = 0.8f)
                )
            }
            
            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        "Search here",
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = NavPrimary
                ),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White)
            )

            IconButton(onClick = { /* Voice Search */ }) {
                Icon(
                    Icons.Rounded.Mic,
                    contentDescription = "Voice Search",
                    tint = Color.White.copy(alpha = 0.8f)
                )
            }

            Spacer(Modifier.width(4.dp))
            
            Surface(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable { onProfileClick() },
                color = NavPrimary
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "R", // Initial
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun QuickAccessChips(
    selectedCategory: String?,
    onCategoryClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val categories = listOf(
        Triple("Home", Icons.Rounded.Home, "home"),
        Triple("Restaurants", Icons.Rounded.Restaurant, "restaurant"),
        Triple("Gas", Icons.Rounded.LocalGasStation, "gas"),
        Triple("Groceries", Icons.Rounded.LocalGroceryStore, "grocery"),
        Triple("Coffee", Icons.Rounded.Coffee, "coffee")
    )

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(categories) { (label, icon, query) ->
            Surface(
                onClick = { onCategoryClick(query) },
                shape = CircleShape,
                color = Color(0xAA2D2F33),
                border = androidx.compose.foundation.BorderStroke(1.dp, GlassStroke)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        icon,
                        contentDescription = label,
                        modifier = Modifier.size(18.dp),
                        tint = Color.White.copy(alpha = 0.9f)
                    )
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }
            }
        }
    }
}
