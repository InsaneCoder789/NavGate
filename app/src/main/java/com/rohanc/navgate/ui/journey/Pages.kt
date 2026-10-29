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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rohanc.navgate.core.RegionStatus
import com.rohanc.navgate.model.Coordinate
import com.rohanc.navgate.model.RouteHistoryEntry
import com.rohanc.navgate.ui.ar.VoiceAvailability
import com.rohanc.navgate.model.asMetersLabel
import com.rohanc.navgate.model.tidy
import com.rohanc.navgate.ui.state.CityMode
import com.rohanc.navgate.ui.state.NavGateUiState
import java.text.DateFormat
import java.util.Date

/** Full-screen glass page: the map stays visible, blurred, behind a frosted sheet. */
@Composable
fun GlassPage(title: String, onBack: (() -> Unit)?, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    GlassPanel(modifier.fillMaxSize(), shape = RectangleShape, weight = GlassWeight.Thick, shadow = false, minOpacity = 0.62f, solidInLight = true) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onBack != null) com.rohanc.navgate.ui.components.MapControlButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back") } else Spacer(Modifier.width(6.dp))
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
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
        NgBottomBar(selected, a.openTab)
    }
}

@Composable
fun BoxScope.SavedScreen(ui: NavGateUiState, userLocation: Coordinate?, a: JourneyActions) {
    var sorted by rememberSaveable { mutableStateOf(false) }
    TabPage("Saved", "Saved places", a, actions = { IconButton(onClick = { a.openSearch("saved") }) { Icon(Icons.Rounded.Search, "Search saved places") } }) {
        if (ui.savedPlaces.size > 1) Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
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
    TabPage("Recents", "Recents", a, actions = { TextButton(onClick = if (trips) a.clearHistory else a.clearRecents) { Text("Clear") } }) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!trips, { trips = false }, { Text("Places") }); FilterChip(trips, { trips = true }, { Text("Trips") })
        }
        if (trips) {
            if (ui.routeHistory.isEmpty()) EmptyState(Icons.Rounded.History, "Your trips will appear here.", "Completed and stopped trips are kept on this phone.")
            else Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) { TripSections(ui.routeHistory, a) }
        } else {
            if (ui.recentPlaces.isEmpty()) EmptyState(Icons.Rounded.History, "Places you choose will appear here.", "Search for somewhere to get started.")
            else Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                ui.recentPlaces.forEach { place -> PlaceListRow(place, userLocation, Icons.Rounded.History, { a.openPlace(place) }) { IconButton(onClick = { a.directions(place) }) { Icon(Icons.Rounded.Directions, "Directions to ${place.title}", tint = MaterialTheme.colorScheme.primary) } } }
            }
        }
    }
}

@Composable
internal fun TripRow(trip: RouteHistoryEntry, a: JourneyActions) {
    Row(Modifier.fillMaxWidth().clickable { a.planAgain(trip.destinationTitle) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(44.dp).background(com.rohanc.navgate.ui.theme.NavTokens.pulse(), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Icon(if (trip.travelMode == com.rohanc.navgate.model.TravelProfile.Driving) Icons.Rounded.DirectionsCar else Icons.Rounded.DirectionsWalk, null, tint = androidx.compose.ui.graphics.Color.White)
        }
        Column(Modifier.weight(1f)) {
            Text(trip.destinationTitle.tidy(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${tripStatusLabel(trip.status)} · ${tripWhen(trip.recordedAtEpochMillis)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trip.estimatedDistanceMeters?.takeIf { it > 0 }?.let { Text(it.asMetersLabel(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false) }
            ?: Text("Again", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, hint: String) {
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
    var showVehicles by remember { mutableStateOf(false) }
    // The page restyles live while the slider moves; the value is saved when the finger lifts.
    if (showVehicles) VehiclePickerSheet(model.prefs, a.setPrefs) { showVehicles = false }
    CompositionLocalProvider(LocalGlassOpacity provides level) { GlassPage("Settings", a.back) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsGroup("Appearance") {
                SegmentedRow(listOf("system" to "System", "light" to "Light", "dark" to "Dark"), model.prefs.appearance) { a.setPrefs(model.prefs.copy(appearance = it)) }
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
            SettingsGroup("AR smoothness") {
                Text("Frames per second. How smoothly the AR road line moves. ARCore's camera tops out at 60 frames a second; Max also redraws the line at your screen's own rate (up to 120 Hz) by predicting the camera between frames, which uses more battery.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SegmentedRow(listOf("auto" to "Auto", "saver" to "30", "smooth" to "60", "max" to "Max"), model.prefs.arFps) { a.setPrefs(model.prefs.copy(arFps = it)) }
                                NgTonalButton("Try AR here (practice path)", Icons.Rounded.ViewInAr, a.practiceAr, Modifier.fillMaxWidth())
                NgTonalButton("Share AR diagnostics", Icons.Rounded.Share, a.exportArDiagnostics, Modifier.fillMaxWidth())
            }
            if (com.rohanc.navgate.BuildConfig.CRASH_REPORT_URL.isNotBlank()) SettingsGroup("Help improve NavGate") {
                SwitchRow("Send crash reports", "If the app closes unexpectedly, send the technical details automatically next time. They contain no places, routes or personal data.", model.prefs.sendCrashReports) { a.setPrefs(model.prefs.copy(sendCrashReports = it)) }
            }
            SettingsGroup("Driving") {
                VehicleRow(model.prefs) { showVehicles = true }
                SwitchRow("Avoid tolls", "Prefer roads without tolls when a sensible route exists. Applies to the next route you plan.", model.prefs.avoidTolls) { a.setPrefs(model.prefs.copy(avoidTolls = it)) }
                SwitchRow("Avoid highways", "Prefer city streets over motorways. Routes may take longer.", model.prefs.avoidHighways) { a.setPrefs(model.prefs.copy(avoidHighways = it)) }
            }
            SettingsGroup("Guidance") {
                SwitchRow("Spoken directions", when (model.voice) {
                    VoiceAvailability.Initializing -> "Checking the installed offline voice…"; VoiceAvailability.OfflineReady -> "Offline voice is ready."
                    VoiceAvailability.Unavailable -> "No offline voice installed. Written directions stay on screen." }, model.prefs.voiceEnabled) { a.setPrefs(model.prefs.copy(voiceEnabled = it)) }
                if (!model.hasLocation) NgPrimaryButton("Turn on location", Icons.Rounded.MyLocation, a.requestLocation, Modifier.fillMaxWidth())
                TextButton(onClick = a.openAppSettings) { Text("Permissions in Android settings") }
            }
            SettingsGroup("Map data") {
                // The map's own info icon is gone; the required credits live here and on the directions screen.
                Text("Map data © OpenStreetMap contributors (ODbL). Map tiles by OpenFreeMap. Routing by Valhalla. Offline places for Mumbai and Odisha from OpenStreetMap and the Overture Maps Foundation. Places also from Photon and GeoNames.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SettingsGroup("Privacy") {
                listOf(
                    "Live GPS tracking is processed on this phone for guidance. Online search and routing share the location information described below.",
                    "Searches send the words you type and a rough area to Photon.",
                    "Routes send the start and destination to Valhalla (OpenStreetMap Germany).",
                    "Map tiles come from OpenFreeMap.",
                    "The camera is used only for AR and Live view; its pictures are never recorded or uploaded.",
                    "Crash reports are sent only if you turn that on.",
                ).forEach { line -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Text("•", color = MaterialTheme.colorScheme.primary); Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            }
            SettingsGroup("Region") {
                CityMode.entries.forEach { city ->
                    Row(Modifier.fillMaxWidth().selectable(city == model.city, enabled = !model.tripActive, role = Role.RadioButton) { a.setCity(city) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(city == model.city, null, enabled = !model.tripActive); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(city.label, style = MaterialTheme.typography.bodyLarge)
                            Text(city.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
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
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, letterSpacing = androidx.compose.ui.unit.TextUnit(1f, androidx.compose.ui.unit.TextUnitType.Sp), fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        GlassPanel(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), weight = GlassWeight.Thin, shadow = false, minOpacity = 0.8f) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
    }
}

@Composable private fun SwitchRow(label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.ExtraBold); Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedThumbColor = androidx.compose.ui.graphics.Color.White, checkedTrackColor = MaterialTheme.colorScheme.primary))
    }
}

@Composable private fun NavRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant); Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge); Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Offline data page: the test-place catalogue, offline maps and offline routing. */
@Composable
fun BoxScope.OfflineScreen(region: RegionStatus?, tripActive: Boolean, a: JourneyActions) {
    GlassPage("Offline & region data", a.back) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsGroup("Regional catalogue") {
                if (region == null) Text("Region status is not available yet.", style = MaterialTheme.typography.bodyMedium)
                else {
                    Text("${region.placeCount} catalogue places · ${region.missingCoordinateCount} without coordinates", style = MaterialTheme.typography.bodyMedium)
                    listOfNotNull(region.version?.let { "Version: $it" }, region.reviewedAt?.let { "Reviewed: $it" }, region.source?.let { "Source: $it" }, region.attribution, region.license?.let { "License: $it" })
                        .forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Text("These counts cover the selected regional catalogue, not the separate India, Mumbai or Odisha search indexes. Saved places stay on this phone. Map tiles need a connection unless you have saved an offline map.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OfflineMapAndRoutingGroups(tripActive)
        }
    }
}

/** Small instruction chip shown while the user taps the map to place a pin. */
@Composable
fun BoxScope.PickPrompt(text: String, a: JourneyActions) {
    GlassPanel(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp), shape = CircleShape) {
        Row(Modifier.padding(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Place, null, Modifier.size(20.dp).align(Alignment.CenterVertically), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp))
            Text(text, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.ExtraBold)
            TextButton(onClick = a.cancelPick) { Text("Cancel", fontWeight = FontWeight.ExtraBold) }
        }
    }
}

/** What happened to a trip, in words: the raw session states are for the code, not for people. */
internal fun tripStatusLabel(status: String): String = when (status) {
    "Arrived" -> "Arrived"
    "Stopped" -> "Ended early"
    else -> "Interrupted"
}

/** "Today, 22:12", "Yesterday, 18:05" or "26 Oct, 22:12". */
internal fun tripWhen(millis: Long): String {
    val then = java.util.Calendar.getInstance().apply { timeInMillis = millis }; val now = java.util.Calendar.getInstance()
    val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(Date(millis))
    val sameYear = then.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR)
    val dayDiff = now.get(java.util.Calendar.DAY_OF_YEAR) - then.get(java.util.Calendar.DAY_OF_YEAR)
    return when {
        sameYear && dayDiff == 0 -> "Today, $time"
        sameYear && dayDiff == 1 -> "Yesterday, $time"
        else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(Date(millis)) + ", $time"
    }
}

/** Equal choices in one pill: the chosen one is the white (light) or blue (dark) segment. */
@Composable
internal fun SegmentedRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    val dark = com.rohanc.navgate.ui.theme.NavTokens.isDark()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)).padding(4.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (key, label) ->
            val on = key == selected
            Box(Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(12.dp))
                .then(if (on) (if (dark) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)) else Modifier.background(com.rohanc.navgate.ui.theme.NavTokens.porcelain())) else Modifier)
                .selectable(on, role = Role.RadioButton, onClick = { onSelect(key) }), contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold, maxLines = 1, softWrap = false, color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Trips grouped by day (Today, Yesterday, then the date), each group one card, newest first. */
@Composable
internal fun TripSections(trips: List<RouteHistoryEntry>, a: JourneyActions) {
    val groups = trips.sortedByDescending { it.recordedAtEpochMillis }.groupBy { tripDay(it.recordedAtEpochMillis) }
    groups.forEach { (day, entries) ->
        Text(day.uppercase(), Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp), style = MaterialTheme.typography.labelSmall, letterSpacing = androidx.compose.ui.unit.TextUnit(1f, androidx.compose.ui.unit.TextUnitType.Sp), fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        GlassPanel(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(22.dp), weight = GlassWeight.Regular, minOpacity = 0.8f, shadow = false) {
            Column { entries.forEachIndexed { i, trip -> if (i > 0) HorizontalDivider(Modifier.padding(start = 76.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)); TripRow(trip, a) } }
        }
    }
}

/** "Today", "Yesterday" or "26 Oct". */
internal fun tripDay(millis: Long): String {
    val then = java.util.Calendar.getInstance().apply { timeInMillis = millis }; val now = java.util.Calendar.getInstance()
    fun same(a: java.util.Calendar, b: java.util.Calendar) = a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
    if (same(then, now)) return "Today"
    if (same(then, (now.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, -1) })) return "Yesterday"
    return java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(millis))
}
