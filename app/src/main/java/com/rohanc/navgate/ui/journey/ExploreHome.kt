package com.rohanc.navgate.ui.journey

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp


/** The home map: search pill, category chips, Directions button and a short "what's here" sheet. */
@Composable
fun BoxScope.ExploreHome(areaName: String?, onInsets: (Float, Float) -> Unit, a: JourneyActions, kiitMode: Boolean = false, userLocation: com.rohanc.navgate.model.Coordinate? = null, recentTrips: List<com.rohanc.navgate.model.RouteHistoryEntry> = emptyList(), reduceMotion: Boolean = false) {
    val density = LocalDensity.current
    var topPx by remember { mutableIntStateOf(0) }
    var bottomPx by remember { mutableIntStateOf(0) }
    var barPx by remember { mutableIntStateOf(0) }
    val screenDp = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.toFloat()
    val sheet = remember { androidx.compose.animation.core.Animatable(nearbyPeekDp(screenDp)) }
    val fabVisible by remember { derivedStateOf { sheet.value < screenDp * 0.5f } }
    LaunchedEffect(topPx, bottomPx) { with(density) { onInsets(topPx.toDp().value, bottomPx.toDp().value) } }
    Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { topPx = it.height }.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NgSearchPill("Where to?", true, { a.openSearch("place") }, leading = { Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.primary) },
            trailing = { Box(Modifier.size(44.dp).clickable(onClickLabel = "Settings", role = androidx.compose.ui.semantics.Role.Button, onClick = a.openSettings).semantics { contentDescription = "Settings" }.clip(androidx.compose.foundation.shape.CircleShape).background(com.rohanc.navgate.ui.theme.NavTokens.pulse()), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Person, null, Modifier.size(24.dp), tint = androidx.compose.ui.graphics.Color.White) } })
        NgChipRow {
            com.rohanc.navgate.data.search.PoiCategory.entries.forEach { c ->
                val (short, tint) = when (c) {
                    com.rohanc.navgate.data.search.PoiCategory.Food -> "Food" to androidx.compose.ui.graphics.Color(0xFFFF8A3D)
                    com.rohanc.navgate.data.search.PoiCategory.Fuel -> "Fuel" to androidx.compose.ui.graphics.Color(0xFF7C5CFF)
                    com.rohanc.navgate.data.search.PoiCategory.Health -> "Care" to androidx.compose.ui.graphics.Color(0xFFE5484D)
                    else -> c.label to null
                }
                NgChip(short, categoryIcon(c), tint) { a.openCategory(c) }
            }
            NgChip("My routes", Icons.Rounded.Route) { a.openMyRoutes() }
        }
    }
    Column(Modifier.align(Alignment.BottomCenter).onSizeChanged { bottomPx = it.height }) {
        // The Directions button rides on top of the sheet and steps aside once the sheet has been pulled up over the map.
        if (fabVisible) Row(Modifier.fillMaxWidth().padding(end = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.End) {
            NgDirectionsFab({ a.openSearch("to") })
        }
        val roomDp = screenDp - with(density) { (topPx + barPx).toDp().value } - 8f
        NearbySheet(sheet, screenDp, areaName, kiitMode, userLocation, recentTrips, a, { a.openPlace(it) }, maxDp = roomDp, reduceMotion = reduceMotion, suggestion = remember(recentTrips) { com.rohanc.navgate.ui.state.suggestFor(recentTrips, System.currentTimeMillis()) })
        Box(Modifier.onSizeChanged { barPx = it.height }) { NgBottomBar("Explore", a.openTab) }
    }
}

