package com.rohanc.navgate.ui.journey

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.rohanc.navgate.model.tidy
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rohanc.navgate.model.Coordinate
import com.rohanc.navgate.model.PlaceSearchResult

/** Full-screen search: one field, recent places, then results with distance from the user. */
@Composable
fun SearchPage(query: String, searchFor: String, results: List<PlaceSearchResult>, recents: List<PlaceSearchResult>, loading: Boolean,
    notice: String?, hasLocationPermission: Boolean, userLocation: Coordinate?, a: JourneyActions) {
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val visibleResults = remember(results) { distinctSearchPlaces(results) }
    // A changed query is a new list; keyboard resizing must not leave its best matches off screen.
    LaunchedEffect(query, searchFor, visibleResults.firstOrNull()?.id) {
        // Results arrive after the query changes. Request the next layout at index zero so
        // LazyColumn does not preserve the credit footer as its visible keyed item.
        listState.requestScrollToItem(0)
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    GlassPanel(Modifier.fillMaxSize(), shape = androidx.compose.ui.graphics.RectangleShape, weight = GlassWeight.Thick, shadow = false, minOpacity = 0.78f, solidInLight = true) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            GlassPanel(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(), shape = CircleShape, weight = GlassWeight.Thin, shadow = false, minOpacity = 0.6f) {
                Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = a.back) { Icon(Icons.Rounded.ArrowBack, "Back") }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text(when (searchFor) { "from" -> "Choose start"; "to" -> "Choose destination"; "saved" -> "Search saved places"; else -> "Where to?" },
                            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        BasicTextField(query, a.query, Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Search" }, singleLine = true,
                            textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { a.submitSearch() }))
                    }
                    if (query.isNotEmpty()) IconButton(onClick = { a.query("") }) { Icon(Icons.Rounded.Cancel, "Clear query", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                if (searchFor == "from" || searchFor == "to") {
                    if (searchFor == "from") item("current") { ActionRow(Icons.Rounded.MyLocation, "Your location", if (hasLocationPermission) null else "Location is off. Tap to turn it on.", a.useCurrentLocation) }
                    item("map") { ActionRow(Icons.Rounded.Place, "Choose on map", null) { a.chooseOnMap(searchFor) } }
                }
                if (query.isBlank() && searchFor != "saved") {
                    if (recents.isNotEmpty()) {
                        item("recent-header") {
                            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Recent", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                TextButton(onClick = a.clearRecents) { Text("Clear") }
                            }
                        }
                        items(recents.take(8), key = { "r-" + it.id }) { SearchRow(it, userLocation, Icons.Rounded.History, { a.choose(it) }, null) }
                    } else item("empty-hint") {
                        Text("Search for a place, an address or a landmark anywhere in the world.", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    items(visibleResults, key = { "p-" + it.id }) { place -> SearchRow(place, userLocation, placeIcon(place), { a.choose(place) }, { a.query(place.title) }) }
                    if (!loading && visibleResults.isEmpty() && query.isNotBlank()) item("none") {
                        Text("No places found. Try a different name.", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                notice?.takeIf { it.startsWith("Type at least") || it.contains("unavailable") }?.let { item("notice") { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                item("credit") { Text("© OpenStreetMap contributors · Photon · GeoNames (CC BY 4.0)", Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(44.dp).background(com.rohanc.navgate.ui.theme.NavTokens.pulse(), androidx.compose.foundation.shape.RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = androidx.compose.ui.graphics.Color.White) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SearchRow(place: PlaceSearchResult, userLocation: Coordinate?, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, onFill: (() -> Unit)?) = PlaceListRow(place, userLocation, icon, onClick) { if (onFill != null) IconButton(onClick = onFill) { Icon(Icons.Rounded.NorthWest, "Use this name in the search box", tint = MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable
fun PlaceListRow(place: PlaceSearchResult, userLocation: Coordinate?, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, trailing: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (com.rohanc.navgate.data.search.PinKind.of("", place.category.orEmpty()) != com.rohanc.navgate.data.search.PinKind.Other) PlaceBadge(place, 44.dp)
        else Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceVariant, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Column(Modifier.weight(1f)) {
            Text(place.title.tidy(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(place.subtitle.tidy(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        distanceFrom(userLocation, place)?.let { Text(it, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false) }
        trailing()
    }
    HorizontalDivider(Modifier.padding(start = 76.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
}

/** Keep source ranking, removing only same-ID entries or nearby representations of a named monument.
 * Businesses with the same name and neighbouring entrances remain separate. Unknown/distant names
 * must not be merged simply because their rounded distance happens to match. */
internal fun distinctSearchPlaces(places: List<PlaceSearchResult>): List<PlaceSearchResult> {
    val byName = mutableMapOf<String, MutableList<PlaceSearchResult>>()
    val ids = HashSet<String>()
    val out = ArrayList<PlaceSearchResult>(places.size)
    for (place in places) {
        if (!ids.add(place.id)) continue
        val name = com.rohanc.navgate.data.search.PlaceSearchRanking.normalize(place.title)
        val peers = byName.getOrPut(name) { mutableListOf() }
        val monument = isMonument(place)
        val duplicate = monument && name.isNotEmpty() && peers.any { peer ->
            isMonument(peer) &&
                place.coordinate?.let { point -> peer.coordinate?.let { point.distanceTo(it) <= 60.0 } } == true
        }
        if (!duplicate) { out += place; peers += place }
    }
    return out
}

internal fun isMonument(place: PlaceSearchResult): Boolean =
    com.rohanc.navgate.data.search.PlaceSearchRanking.featureLabel(place.category).equals("Monument", true) ||
        (place.category.isNullOrBlank() && place.subtitle.substringBefore('·').trim().equals("Monument", true))
