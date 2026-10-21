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
fun BoxScope.ExploreHome(areaName: String?, onInsets: (Float, Float) -> Unit, a: JourneyActions) {
    val density = LocalDensity.current
    var topPx by remember { mutableIntStateOf(0) }
    var bottomPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(topPx, bottomPx) { with(density) { onInsets(topPx.toDp().value, bottomPx.toDp().value) } }
    Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { topPx = it.height }.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GmSearchPill("Search here", true, { a.openSearch("place") }, leading = { Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.primary) },
            trailing = { GmRoundButton(Icons.Rounded.AccountCircle, "Settings", a.openSettings, container = androidx.compose.ui.graphics.Color.Transparent, tint = MaterialTheme.colorScheme.primary, size = 48.dp) })
        GmChipRow {
            Categories.forEach { (label, icon, query) -> GmChip(label, icon) { a.openSearch("place"); a.query(query); a.submitSearch() } }
            GmChip("My routes", Icons.Rounded.Route) { a.openMyRoutes() }
        }
    }
    Column(Modifier.align(Alignment.BottomCenter).onSizeChanged { bottomPx = it.height }) {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.End) {
            GmDirectionsFab({ a.openSearch("to") })
        }
        GmSheet {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(areaName ?: "Explore nearby", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                Text("Search for a place, or tap one on the map for directions.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        GmBottomBar("Explore", a.openTab)
    }
}
