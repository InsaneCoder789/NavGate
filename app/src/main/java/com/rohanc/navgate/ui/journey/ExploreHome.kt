package com.rohanc.navgate.ui.journey

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

private val Categories = listOf(
    Triple("Restaurants", Icons.Rounded.Restaurant, "restaurants"),
    Triple("Petrol", Icons.Rounded.LocalGasStation, "petrol pump"),
    Triple("Hospitals", Icons.Rounded.LocalHospital, "hospitals"),
    Triple("Hotels", Icons.Rounded.Hotel, "hotels"),
    Triple("ATMs", Icons.Rounded.LocalAtm, "atm"),
    Triple("Pharmacies", Icons.Rounded.LocalPharmacy, "pharmacy"),
)

/** The home map: search pill, category chips, Directions button and a short "what's here" sheet. */
@Composable
fun BoxScope.ExploreHome(areaName: String?, onInsets: (Float, Float) -> Unit, a: JourneyActions, kiitMode: Boolean = false, userLocation: com.rohanc.navgate.model.Coordinate? = null, recentTrips: List<com.rohanc.navgate.model.RouteHistoryEntry> = emptyList()) {
    val density = LocalDensity.current
    var topPx by remember { mutableIntStateOf(0) }
    var bottomPx by remember { mutableIntStateOf(0) }
    var barPx by remember { mutableIntStateOf(0) }
    val screenDp = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.toFloat()
    val sheet = remember { androidx.compose.animation.core.Animatable(COLLAPSED_DP) }
    val fabVisible by remember { derivedStateOf { sheet.value < screenDp * 0.3f } }
    LaunchedEffect(topPx, bottomPx) { with(density) { onInsets(topPx.toDp().value, bottomPx.toDp().value) } }
    Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { topPx = it.height }.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NgSearchPill("Search here", true, { a.openSearch("place") }, leading = { Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.primary) },
            trailing = { IconButton(onClick = a.openSettings, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.AccountCircle, "Settings", tint = MaterialTheme.colorScheme.primary) } })
        NgChipRow {
            com.rohanc.navgate.data.search.PoiCategory.entries.forEach { c -> NgChip(c.label, categoryIcon(c)) { a.openCategory(c) } }
            NgChip("My routes", Icons.Rounded.Route) { a.openMyRoutes() }
        }
    }
    Column(Modifier.align(Alignment.BottomCenter).onSizeChanged { if (sheet.value <= COLLAPSED_DP + 1f) bottomPx = it.height }) {
        // The Directions button rides on top of the sheet and steps aside once the sheet has been pulled up over the map.
        if (fabVisible) Row(Modifier.fillMaxWidth().padding(end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.End) {
            NgDirectionsFab({ a.openSearch("to") })
        }
        val roomDp = screenDp - with(density) { (topPx + barPx).toDp().value } - 8f
        NearbySheet(sheet, screenDp, areaName, kiitMode, userLocation, recentTrips, a, { a.openPlace(it) }, maxDp = roomDp)
        Box(Modifier.onSizeChanged { barPx = it.height }) { NgBottomBar("Explore", a.openTab) }
    }
}

private const val COLLAPSED_DP = 104f
