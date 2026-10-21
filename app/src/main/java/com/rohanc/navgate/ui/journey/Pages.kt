package com.rohanc.navgate.ui.journey

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rohanc.navgate.core.RegionStatus
import com.rohanc.navgate.model.Coordinate
import com.rohanc.navgate.model.PlaceSearchResult
import com.rohanc.navgate.model.RouteHistoryEntry
import com.rohanc.navgate.ui.ar.VoiceAvailability
import com.rohanc.navgate.ui.state.CityMode
import com.rohanc.navgate.ui.state.NavGateUiState
import java.text.DateFormat
import java.util.Date

/** Full-screen glass page: the map stays visible, blurred, behind a frosted sheet. */
@Composable
fun GlassPage(title: String, onBack: (() -> Unit)?, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    GlassPanel(modifier.fillMaxSize(), shape = RectangleShape, weight = GlassWeight.Thick, shadow = false) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back") } else Spacer(Modifier.width(12.dp))
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
                actions()
            }
            content()
        }
    }
}

/** A page with the bottom bar underneath, used by Saved and Recents. */
@Composable
fun BoxScope.TabPage(selected: String, title: String, a: JourneyActions, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        GlassPage(title, null, Modifier.weight(1f), actions, content)
        GmBottomBar(selected, a.openTab)
    }
}

@Composable
fun BoxScope.SavedScreen(ui: NavGateUiState, userLocation: Coordinate?, a: JourneyActions) {
    var sorted by rememberSaveable { mutableStateOf(false) }
    TabPage("Saved", "Saved places", a, actions = { IconButton(onClick = { a.openSearch("saved") }) { Icon(Icons.Rounded.Search, "Search saved places") } }) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            FilterChip(sorted, { sorted = !sorted }, { Text(if (sorted) "Sorted A–Z" else "Sort A–Z") })
        }
        val places = if (sorted) ui.savedPlaces.sortedBy { it.title.lowercase() } else ui.savedPlaces
        if (places.isEmpty()) EmptyState(Icons.Rounded.BookmarkBorder, "Save places to find them here.", "Tap the bookmark on any place.")
        else Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            places.forEach { place ->
                PlaceListRow(place, userLocation, Icons.Rounded.Bookmark, { a.openPlace(place) }) {
                    IconButton(onClick = { a.directions(place) }) { Icon(Icons.Rounded.Directions, "Directions to ${place.title}", tint = MaterialTheme.colorScheme.primary) }
                    IconButton(onClick = { a.toggleSaved(place) }) { Icon(Icons.Rounded.Close, "Remove ${place.title} from saved") }
                }
            }
        }
    }
}

@Composable
fun BoxScope.RecentsScreen(ui: NavGateUiState, userLocation: Coordinate?, a: JourneyActions) {
    var trips by rememberSaveable { mutableStateOf(false) }
    TabPage("Recents", "Recent", a, actions = { TextButton(onClick = if (trips) a.clearHistory else a.clearRecents) { Text("Clear") } }) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!trips, { trips = false }, { Text("Places") }); FilterChip(trips, { trips = true }, { Text("Trips") })
        }
        if (trips) {
            if (ui.routeHistory.isEmpty()) EmptyState(Icons.Rounded.History, "Your trips will appear here.", "Completed and stopped trips are kept on this phone.")
            else Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) { ui.routeHistory.forEach { TripRow(it, a) } }
        } else {
            if (ui.recentPlaces.isEmpty()) EmptyState(Icons.Rounded.History, "Places you choose will appear here.", "Search for somewhere to get started.")
            else Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                ui.recentPlaces.forEach { place -> PlaceListRow(place, userLocation, Icons.Rounded.History, { a.openPlace(place) }) { IconButton(onClick = { a.directions(place) }) { Icon(Icons.Rounded.Directions, "Directions to ${place.title}", tint = MaterialTheme.colorScheme.primary) } } }
            }
        }
    }
}

@Composable
private fun TripRow(trip: RouteHistoryEntry, a: JourneyActions) {
    Row(Modifier.fillMaxWidth().clickable { a.planAgain(trip.destinationTitle) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(if (trip.travelMode == com.rohanc.navgate.model.TravelProfile.Driving) Icons.Rounded.DirectionsCar else Icons.Rounded.DirectionsWalk, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(trip.destinationTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${trip.status} · ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(trip.recordedAtEpochMillis))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Again", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, hint: String) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Everything the settings page shows, gathered so the page itself holds no app logic. */
data class SettingsModel(val prefs: com.rohanc.navgate.data.FrontendPreferences, val city: CityMode, val tripActive: Boolean, val voice: VoiceAvailability, val hasLocation: Boolean)

@Composable
fun BoxScope.SettingsScreen(model: SettingsModel, a: JourneyActions) {
    var level by remember(model.prefs.glassOpacity) { mutableFloatStateOf(model.prefs.glassOpacity) }
    // The page restyles live while the slider moves; the value is saved when the finger lifts.
    CompositionLocalProvider(LocalGlassOpacity provides level) { GlassPage("Settings", a.back) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsGroup("Appearance") {
                Row(Modifier.selectableGroup().fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (key, label) ->
                        FilterChip(model.prefs.appearance == key, { a.setPrefs(model.prefs.copy(appearance = key)) }, { Text(label) }, Modifier.weight(1f))
                    }
                }
                Column(Modifier.fillMaxWidth()) {
                    Text("Glass transparency", style = MaterialTheme.typography.bodyLarge)
                    Text("From clear see-through glass to solid panels.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(level, { level = it }, onValueChangeFinished = { a.setPrefs(model.prefs.copy(glassOpacity = level, reduceTransparency = false)) },
                        modifier = Modifier.semantics { contentDescription = "Glass transparency" })
                    Row(Modifier.fillMaxWidth()) {
                        Text("Clear", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Text("Solid", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                SwitchRow("Reduce motion", "Move the map without animation.", model.prefs.reduceMotion) { a.setPrefs(model.prefs.copy(reduceMotion = it)) }
            }
            SettingsGroup("Guidance") {
                SwitchRow("Spoken directions", when (model.voice) {
                    VoiceAvailability.Initializing -> "Checking the installed offline voice…"; VoiceAvailability.OfflineReady -> "Offline voice is ready."
                    VoiceAvailability.Unavailable -> "No offline voice installed. Written directions stay on screen." }, model.prefs.voiceEnabled) { a.setPrefs(model.prefs.copy(voiceEnabled = it)) }
                if (!model.hasLocation) GmPrimaryButton("Turn on location", Icons.Rounded.MyLocation, a.requestLocation, Modifier.fillMaxWidth())
                TextButton(onClick = a.openAppSettings) { Text("Permissions in Android settings") }
            }
            SettingsGroup("Region") {
                CityMode.entries.forEach { city ->
                    Row(Modifier.fillMaxWidth().selectable(city == model.city, enabled = !model.tripActive, role = Role.RadioButton) { a.setCity(city) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(city == model.city, null, enabled = !model.tripActive); Spacer(Modifier.width(12.dp)); Text(city.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (model.tripActive) Text("Stop the current trip to change region.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                NavRow(Icons.Rounded.Storage, "Offline & region data", a.openOfflineData)
            }
            SettingsGroup("Data") {
                NavRow(Icons.Rounded.DeleteSweep, "Clear recent places", a.clearRecents)
                NavRow(Icons.Rounded.History, "Clear trip history", a.clearHistory)
                NavRow(Icons.Rounded.Replay, "Show welcome again", a.reviewSetup)
            }
        }
    } }
}

@Composable private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 4.dp))
        GlassPanel(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), weight = GlassWeight.Thin, shadow = false) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
    }
}

@Composable private fun SwitchRow(label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.bodyLarge); Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked, onChange)
    }
}

@Composable private fun NavRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant); Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge); Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Region data page: what is installed, and installing a signed update from files. */
@Composable
fun BoxScope.OfflineScreen(region: RegionStatus?, updatesEnabled: Boolean, tripActive: Boolean, importing: Boolean, importVerifying: Boolean, a: JourneyActions) {
    GlassPage("Offline & region data", a.back) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsGroup("Installed data") {
                if (region == null) Text("Region status is not available yet.", style = MaterialTheme.typography.bodyMedium)
                else {
                    Text("Readiness: ${region.readiness}", style = MaterialTheme.typography.bodyLarge)
                    Text("${region.placeCount} places · ${region.missingCoordinateCount} without coordinates", style = MaterialTheme.typography.bodyMedium)
                    Text("${region.reviewedEdgeCount} reviewed paths · ${region.reviewedEntranceCount} reviewed entrances", style = MaterialTheme.typography.bodyMedium)
                    listOfNotNull(region.version?.let { "Version: $it" }, region.reviewedAt?.let { "Reviewed: $it" }, region.source?.let { "Source: $it" }, region.attribution, region.license?.let { "License: $it" })
                        .forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Text("Map tiles need a connection. Routing data and saved places are stored on this phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SettingsGroup("Updates") {
                GmPrimaryButton("Install signed region files", Icons.Rounded.FileOpen, a.installRegion, Modifier.fillMaxWidth(), enabled = updatesEnabled && !tripActive && !importing)
                if (!updatesEnabled) Text("External region updates are not enabled in this build. Your installed data remains available.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (tripActive) Text("Stop this trip before installing another region.", style = MaterialTheme.typography.bodySmall)
                if (importing) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(if (importVerifying) "Verifying signature and routing data…" else "Reading selected documents…", style = MaterialTheme.typography.bodyMedium); TextButton(onClick = a.cancelImport) { Text("Cancel import") } }
                OutlinedButton(onClick = a.refreshRegion, shape = CircleShape) { Text("Refresh status") }
            }
        }
    }
}

/** Small instruction chip shown while the user taps the map to place a pin. */
@Composable
fun BoxScope.PickPrompt(text: String, a: JourneyActions) {
    GlassPanel(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp), shape = CircleShape) {
        Row(Modifier.padding(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = a.cancelPick) { Text("Cancel") }
        }
    }
}
