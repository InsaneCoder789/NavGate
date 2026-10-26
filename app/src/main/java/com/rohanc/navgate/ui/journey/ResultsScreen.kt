package com.rohanc.navgate.ui.journey

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.rohanc.navgate.model.tidy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rohanc.navgate.core.OpeningHours
import com.rohanc.navgate.data.search.PoiCategory
import com.rohanc.navgate.model.Coordinate
import com.rohanc.navgate.model.PlaceSearchResult
import com.rohanc.navgate.ui.state.NavGateUiState
import java.time.LocalDateTime

/** One icon per kind of place, used on chips, result rows and the place card. */
fun categoryIcon(category: PoiCategory): ImageVector = when (category) {
    PoiCategory.Food -> Icons.Rounded.Restaurant; PoiCategory.Fuel -> Icons.Rounded.LocalGasStation; PoiCategory.Health -> Icons.Rounded.LocalHospital
    PoiCategory.Pharmacy -> Icons.Rounded.LocalPharmacy; PoiCategory.Lodging -> Icons.Rounded.Hotel; PoiCategory.Atm -> Icons.Rounded.LocalAtm
    PoiCategory.Bank -> Icons.Rounded.AccountBalance; PoiCategory.Grocery -> Icons.Rounded.ShoppingCart; PoiCategory.Shopping -> Icons.Rounded.LocalMall
    PoiCategory.Education -> Icons.Rounded.School; PoiCategory.Transit -> Icons.Rounded.Train; PoiCategory.Worship -> Icons.Rounded.TempleHindu
    PoiCategory.Park -> Icons.Rounded.Park; PoiCategory.Fitness -> Icons.Rounded.FitnessCenter; PoiCategory.Attraction -> Icons.Rounded.Attractions
    PoiCategory.Entertainment -> Icons.Rounded.Movie; PoiCategory.Ev -> Icons.Rounded.EvStation; PoiCategory.Services -> Icons.Rounded.LocalPolice
    PoiCategory.Toilets -> Icons.Rounded.Wc; PoiCategory.Parking -> Icons.Rounded.LocalParking
}

/** Results of a category or a typed search, listed under the map with a pin for each place. */
@Composable
fun BoxScope.ResultsScreen(ui: NavGateUiState, userLocation: Coordinate?, onInsets: (Float, Float) -> Unit, a: JourneyActions) {
    val density = LocalDensity.current
    var topPx by remember { mutableIntStateOf(0) }
    var bottomPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(topPx, bottomPx) { with(density) { onInsets(topPx.toDp().value, bottomPx.toDp().value) } }
    val category = PoiCategory.fromQuery(ui.searchQuery)
    Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { topPx = it.height }.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NgSearchPill(category?.label ?: ui.searchQuery, false, { a.openSearch("place") }, leading = { Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.primary) },
            trailing = { IconButton(onClick = a.closeResults) { Icon(Icons.Rounded.Close, "Close results") } })
        NgChipRow { PoiCategory.entries.forEach { c -> NgChip(c.label, categoryIcon(c)) { a.openCategory(c) } } }
    }
    NgSheet(Modifier.align(Alignment.BottomCenter).heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.5f).dp).onSizeChanged { bottomPx = it.height }) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Text(if (ui.isLoadingPlaces) "Searching…" else "${ui.places.size} ${if (ui.places.size == 1) "place" else "places"}", Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (ui.isLoadingPlaces) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
            ui.searchNotice?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                if (!ui.isLoadingPlaces && ui.places.isEmpty()) Text("No places found here. Try another category or move the map.", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ui.places.forEach { place -> ResultRow(place, userLocation, { a.choose(place) }) }
            }
        }
    }
}

@Composable
internal fun ResultRow(place: PlaceSearchResult, userLocation: Coordinate?, onClick: () -> Unit) {
    val status = remember(place.hours) { OpeningHours.status(place.hours, LocalDateTime.now()) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        PlaceBadge(place, 42.dp)
        Column(Modifier.weight(1f)) {
            Text(place.title.tidy(), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(place.subtitle.tidy(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            status?.let { Text(it.label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (it.open) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error) }
        }
        distanceFrom(userLocation, place)?.let { Text(it, style = MaterialTheme.typography.labelLarge, softWrap = false, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** The round icon of a place in a list: the same icon and colour the map uses for its kind, so a hospital looks like a hospital everywhere. */
@Composable
internal fun PlaceBadge(place: PlaceSearchResult, size: androidx.compose.ui.unit.Dp) {
    val kind = remember(place.category) { com.rohanc.navgate.data.search.PinKind.of("", place.category.orEmpty()) }
    if (kind == com.rohanc.navgate.data.search.PinKind.Other) {
        Box(Modifier.size(size).background(MaterialTheme.colorScheme.primaryContainer, CircleShape).testTag("place-badge"), contentAlignment = Alignment.Center) {
            Icon(PoiCategory.fromSlug(place.category.orEmpty())?.let { categoryIcon(it) } ?: placeIcon(place), null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    } else {
        val tint = com.rohanc.navgate.ui.map.MapIcons.color(kind)
        Box(Modifier.size(size).background(tint.copy(alpha = 0.14f), CircleShape).testTag("place-badge"), contentAlignment = Alignment.Center) {
            Icon(com.rohanc.navgate.ui.map.MapIcons.icon(kind), null, Modifier.size(size * 0.55f), tint = tint)
        }
    }
}
