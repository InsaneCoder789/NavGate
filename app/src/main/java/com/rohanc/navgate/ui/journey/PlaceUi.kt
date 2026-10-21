package com.rohanc.navgate.ui.journey

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rohanc.navgate.core.DestinationAvailability
import com.rohanc.navgate.core.OpeningHours
import com.rohanc.navgate.data.search.PlaceSearchRanking
import com.rohanc.navgate.model.Coordinate
import com.rohanc.navgate.model.PlaceSearchResult
import com.rohanc.navgate.ui.readinessLabel

/** Place card: name, actions and facts, with the search pill above it showing what was found. */
@Composable
fun BoxScope.PlaceUi(place: PlaceSearchResult, saved: Boolean, userLocation: Coordinate?, availability: DestinationAvailability?,
    entranceLabels: Map<String, String>, tripActive: Boolean, onInsets: (Float, Float) -> Unit, a: JourneyActions) {
    val density = LocalDensity.current
    var topPx by remember { mutableIntStateOf(0) }
    var bottomPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(topPx, bottomPx) { with(density) { onInsets(topPx.toDp().value, bottomPx.toDp().value) } }
    Box(Modifier.align(Alignment.TopCenter).onSizeChanged { topPx = it.height }.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        GmSearchPill(place.title, false, { a.openSearch("place") }, leading = { Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.primary) },
            trailing = { IconButton(onClick = a.back) { Icon(Icons.Rounded.Close, "Close place") } })
    }
    GmSheet(Modifier.align(Alignment.BottomCenter).heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.62f).dp).onSizeChanged { bottomPx = it.height }) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(place.title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
                GmRoundButton(if (saved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, if (saved) "Remove from saved" else "Save place", { a.toggleSaved(place) },
                    tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                GmRoundButton(Icons.Rounded.Share, "Share place", { a.share(place) })
            }
            val kind = PlaceSearchRanking.featureLabel(place.category ?: place.type.name)
            val away = distanceFrom(userLocation, place)
            Text(listOfNotNull(kind, away?.let { "$it away" }).joinToString(" · "), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (place.subtitle.isNotBlank()) Text(place.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val status = remember(place.hours) { OpeningHours.status(place.hours, java.time.LocalDateTime.now()) }
            if (status != null) Text(status.label, style = MaterialTheme.typography.titleSmall, color = if (status.open) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
            else place.hours?.let { Text("Hours: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GmPrimaryButton("Directions", Icons.Rounded.Directions, { a.directions(place) }, enabled = !tripActive && place.coordinate != null)
                GmTonalButton(if (saved) "Saved" else "Save", if (saved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, { a.toggleSaved(place) })
            }
            if (place.phone != null || place.website != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                place.phone?.let { phone -> GmTonalButton("Call", Icons.Rounded.Call, { a.call(phone) }) }
                place.website?.let { site -> GmTonalButton("Website", Icons.Rounded.Language, { a.openWebsite(site) }) }
            }
            place.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (place.coordinate == null) Text("This result has no usable map coordinates.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            availability?.let { Text(readinessLabel(it.readiness), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (availability?.entranceIds?.isNotEmpty() == true) {
                Text("Reviewed entrances", style = MaterialTheme.typography.titleSmall)
                availability.entranceIds.forEach { id -> GmTonalButton("Use entrance: ${entranceLabels[id] ?: id}", Icons.Rounded.MeetingRoom, { a.directionsWithEntrance(place, id) }, Modifier.fillMaxWidth(), enabled = !tripActive) }
            }
            place.coordinate?.let { Text("%.5f, %.5f".format(it.latitude, it.longitude), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (place.id.startsWith("poi:")) Text("Place data © OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
