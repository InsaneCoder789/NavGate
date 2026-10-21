package com.rohanc.navgate.ui.journey

import com.rohanc.navgate.ui.components.NavGlassSurface

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rohanc.navgate.model.Coordinate
import com.rohanc.navgate.model.ManeuverType
import com.rohanc.navgate.model.PlaceSearchResult
import com.rohanc.navgate.model.PlaceType
import kotlin.math.roundToInt

/** Rounded search field floating over the map: the entry point of every journey. */
@Composable
fun GmSearchPill(text: String, placeholder: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    leading: @Composable () -> Unit, trailing: @Composable () -> Unit = {}) {
    NavGlassSurface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = CircleShape, shadowElevation = 4.dp) {
        Row(Modifier.heightIn(min = 56.dp).padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            leading()
            Text(text, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium,
                color = if (placeholder) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            trailing()
        }
    }
}

@Composable
fun GmRoundButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    container: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceVariant,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface, size: Dp = 44.dp, enabled: Boolean = true) {
    Surface(modifier, shape = CircleShape, color = container) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(size)) { Icon(icon, description, tint = tint) }
    }
}

/** White map control such as recenter or compass. */
@Composable
fun GmMapButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    NavGlassSurface(modifier, shape = CircleShape, shadowElevation = 3.dp) {
        IconButton(onClick = onClick, modifier = Modifier.size(52.dp)) { Icon(icon, description, tint = MaterialTheme.colorScheme.onSurface) }
    }
}

/** The large coloured Directions button that sits above the map controls. */
@Composable
fun GmDirectionsFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, modifier = modifier.semantics { contentDescription = "Directions" }, shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, shadowElevation = 6.dp) {
        Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Directions, null, Modifier.size(30.dp)) }
    }
}

@Composable
fun GmChip(label: String, icon: ImageVector?, onClick: () -> Unit) {
    NavGlassSurface(onClick = onClick, shape = CircleShape, shadowElevation = 2.dp) {
        Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun GmChipRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

/** Bottom sheet surface with the grabber used by every sheet in the app. */
@Composable
fun GmSheet(modifier: Modifier = Modifier, onGrabber: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    NavGlassSurface(modifier.fillMaxWidth(), shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), dense = true, shadowElevation = 8.dp) {
        Column {
            Box(Modifier.fillMaxWidth().height(24.dp).let { if (onGrabber != null) it.clickable(onClickLabel = "Resize panel", onClick = onGrabber) else it }, contentAlignment = Alignment.Center) {
                Box(Modifier.width(36.dp).height(4.dp).background(MaterialTheme.colorScheme.outlineVariant, CircleShape))
            }
            content()
        }
    }
}

@Composable
fun GmPrimaryButton(label: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 52.dp), shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun GmTonalButton(label: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 52.dp), shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
    }
}

/** Bottom navigation bar, full width like the Android system pattern. */
@Composable
fun GmBottomBar(selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    NavGlassSurface(modifier.fillMaxWidth(), shape = androidx.compose.ui.graphics.RectangleShape, baseColor = MaterialTheme.colorScheme.surfaceVariant, dense = true, shadowElevation = 0.dp) {
        Row(Modifier.navigationBarsPadding().selectableGroup().padding(horizontal = 8.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf(Triple("Explore", Icons.Rounded.Place, "Explore"), Triple("Saved", Icons.Rounded.BookmarkBorder, "Saved"), Triple("Recents", Icons.Rounded.History, "Recents")).forEach { (key, icon, label) ->
                val chosen = key == selected
                Column(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).selectable(chosen, role = Role.Tab, onClick = { onSelect(key) }).padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box(Modifier.clip(CircleShape).background(if (chosen) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent).padding(horizontal = 22.dp, vertical = 4.dp)) {
                        Icon(icon, null, Modifier.size(24.dp), tint = if (chosen) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (chosen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

fun placeIcon(place: PlaceSearchResult): ImageVector = when {
    place.type == PlaceType.Food -> Icons.Rounded.Restaurant
    place.type == PlaceType.Medical -> Icons.Rounded.LocalHospital
    place.type == PlaceType.Transit -> Icons.Rounded.DirectionsTransit
    place.type == PlaceType.Hospitality -> Icons.Rounded.Hotel
    place.type == PlaceType.Academic -> Icons.Rounded.School
    else -> Icons.Rounded.Place
}

fun maneuverIcon(type: ManeuverType): ImageVector = when (type) {
    ManeuverType.Left -> Icons.Rounded.TurnLeft
    ManeuverType.Right -> Icons.Rounded.TurnRight
    ManeuverType.SlightLeft -> Icons.Rounded.TurnSlightLeft
    ManeuverType.SlightRight -> Icons.Rounded.TurnSlightRight
    ManeuverType.UTurn -> Icons.Rounded.UTurnLeft
    ManeuverType.Roundabout -> Icons.Rounded.RoundaboutRight
    ManeuverType.Arrive -> Icons.Rounded.Flag
    ManeuverType.Start, ManeuverType.Straight -> Icons.Rounded.Straight
}

/** "31 km", "850 m": how far a place is from the user, shown beside search results. */
fun distanceFrom(origin: Coordinate?, place: PlaceSearchResult): String? {
    val to = place.coordinate ?: return null
    val meters = origin?.distanceTo(to) ?: return null
    return if (meters >= 1000) (if (meters >= 10_000) "%,d km".format((meters / 1000).roundToInt()) else "%.1f km".format(meters / 1000)) else "${(meters / 10).roundToInt() * 10} m"
}
