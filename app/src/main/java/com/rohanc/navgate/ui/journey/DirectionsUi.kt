package com.rohanc.navgate.ui.journey

import androidx.compose.foundation.horizontalScroll
import com.rohanc.navgate.ui.components.NavGlassSurface

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
import com.rohanc.navgate.model.asMetersLabel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rohanc.navgate.core.RouteFailure
import com.rohanc.navgate.model.RouteStep
import com.rohanc.navgate.model.TravelProfile
import com.rohanc.navgate.ui.state.ModeEstimate
import com.rohanc.navgate.ui.state.NavGateUiState

/** Directions: route card on top, travel-mode tabs with times, the route summary and a clear Start button. */
@Composable
fun BoxScope.DirectionsUi(ui: NavGateUiState, steps: List<RouteStep>, routeWarnings: List<String>, permission: Boolean, onInsets: (Float, Float) -> Unit, a: JourneyActions) {
    val density = LocalDensity.current
    var topPx by remember { mutableIntStateOf(0) }
    var bottomPx by remember { mutableIntStateOf(0) }
    var showSteps by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(topPx, bottomPx) { with(density) { onInsets(topPx.toDp().value, bottomPx.toDp().value) } }
    val driving = ui.travelProfile == TravelProfile.Driving
    val origin = ui.selectedOrigin
    val destination = ui.selectedDestination
    NavGlassSurface(Modifier.align(Alignment.TopCenter).onSizeChanged { topPx = it.height }.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
        shape = RoundedCornerShape(28.dp), dense = true, shadowElevation = 4.dp) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = a.back) { Icon(Icons.Rounded.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                EndpointRow(Icons.Rounded.RadioButtonChecked, MaterialTheme.colorScheme.secondary, origin?.title ?: "Choose start", origin == null || origin.id.startsWith("live"), { a.openSearch("from") })
                HorizontalDivider(Modifier.padding(start = 40.dp), color = MaterialTheme.colorScheme.outlineVariant)
                EndpointRow(Icons.Rounded.Place, MaterialTheme.colorScheme.error, destination?.title ?: "Choose destination", false, { a.openSearch("to") })
            }
            IconButton(onClick = a.swap) { Icon(Icons.Rounded.SwapVert, "Swap") }
        }
    }
    val screenDp = LocalConfiguration.current.screenHeightDp
    val topDp = with(density) { topPx.toDp().value }
    // Expanded steps may grow until just below the route card, never over it.
    val maxHeight = (if (showSteps) (screenDp - topDp - 12f).coerceAtLeast(240f) else screenDp * 0.44f).dp
    GmSheet(Modifier.align(Alignment.BottomCenter).heightIn(max = maxHeight).onSizeChanged { bottomPx = it.height }, onGrabber = { showSteps = !showSteps }) {
        Column(Modifier.navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (driving) "Drive" else "Walk", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                destination?.let { GmRoundButton(Icons.Rounded.Share, "Share destination", { a.share(it) }) }
                GmRoundButton(Icons.Rounded.Close, "Clear directions", a.clearPlan)
            }
            ModeTabs(ui, a)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Summary(ui, driving, permission, routeWarnings, a)
                if (showSteps) StepsList(steps, origin?.title ?: "Your location")
                if (ui.preview != null) Text("Routing by Valhalla · © OpenStreetMap contributors. Times are estimates without live traffic.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ui.startNotice?.let {
                GlassPanel(Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp), shape = RoundedCornerShape(16.dp), weight = GlassWeight.Regular, shadow = false, tint = MaterialTheme.colorScheme.errorContainer) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
            Row(Modifier.padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ui.preview != null) GmPrimaryButton(if (ui.startPending) "Starting…" else if (driving) "Start driving" else "Start walking", Icons.Rounded.Navigation, a.start, Modifier.weight(1f), enabled = !ui.startPending && permission)
                else GmPrimaryButton(if (driving) "Preview driving route" else "Preview walking route", Icons.Rounded.Directions, a.replan, Modifier.weight(1f), enabled = !ui.isLoadingRoute && origin != null && destination != null)
                GmTonalButton("Steps", if (showSteps) Icons.Rounded.ExpandMore else Icons.Rounded.FormatListBulleted, { showSteps = !showSteps }, enabled = steps.isNotEmpty())
            }
        }
    }
}

@Composable
private fun EndpointRow(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color, text: String, highlight: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
        Text(text, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium,
            color = if (highlight) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ModeTabs(ui: NavGateUiState, a: JourneyActions) {
    Row(Modifier.fillMaxWidth().selectableGroup().padding(top = 4.dp)) {
        listOf(TravelProfile.Driving to Icons.Rounded.DirectionsCar, TravelProfile.Walking to Icons.Rounded.DirectionsWalk).forEach { (profile, icon) ->
            val chosen = ui.travelProfile == profile
            val estimate: ModeEstimate? = ui.modeEstimates[profile]
            val label = if (chosen && ui.preview != null) ui.preview.etaLabel else estimate?.etaLabel ?: "—"
            Column(Modifier.weight(1f).selectable(chosen, role = Role.Tab, onClick = { a.setProfile(profile) }), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(icon, if (profile == TravelProfile.Driving) "Car" else "Walking", Modifier.size(24.dp), tint = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(label, style = MaterialTheme.typography.titleMedium, color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(Modifier.fillMaxWidth().height(3.dp).background(if (chosen) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun Summary(ui: NavGateUiState, driving: Boolean, permission: Boolean, warnings: List<String>, a: JourneyActions) {
    val preview = ui.preview
    when {
        ui.isLoadingRoute && preview == null -> Text("Finding the best ${if (driving) "driving" else "walking"} route…", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        preview != null -> {
            Text("${preview.etaLabel} (${preview.distanceLabel})", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
            val seconds = ui.snapshot.route?.durationSeconds
            val arrive = seconds?.let { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(System.currentTimeMillis() + (it * 1000).toLong())) }
            Text(listOfNotNull(arrive?.let { "Arrive $it" }, preview.firstInstruction).joinToString(" · "), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (ui.routeChoices.size > 1) RouteChoices(ui.routeChoices, a.chooseRoute)
            if (!permission) Banner("Turn on location to start guidance.", "Turn on", a.requestLocation)
        }
        ui.routeFailure != null -> {
            Text(failureText(ui.routeFailure, ui.statusMessage), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = a.replan) { Text("Try again") }
        }
        ui.selectedOrigin == null -> Banner("Choose a start, or use your current location.", "Use my location", a.useCurrentLocation)
        else -> Text(ui.statusMessage ?: "Choose a destination.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    ui.statusMessage?.takeIf { preview != null && it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary) }
    warnings.filter { it.isNotBlank() && !it.startsWith("Valhalla") && !it.startsWith("Arrival is at the mapped") }.take(3).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

private fun failureText(reason: RouteFailure, detail: String?): String = detail?.takeIf { it.isNotBlank() } ?: when (reason) {
    RouteFailure.NoConnection -> "No connection. Check the network and try again."
    else -> "A route could not be found between these places."
}

@Composable
private fun Banner(text: String, action: String, onAction: () -> Unit) {
    GlassPanel(shape = RoundedCornerShape(16.dp), weight = GlassWeight.Regular, shadow = false, tint = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            TextButton(onClick = onAction) { Text(action, maxLines = 1, softWrap = false) }
        }
    }
}

@Composable
fun StepsList(steps: List<RouteStep>, originLabel: String, currentIndex: Int = -1) {
    Text("Steps", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(Icons.Rounded.RadioButtonChecked, null, tint = MaterialTheme.colorScheme.secondary); Text(originLabel, style = MaterialTheme.typography.titleMedium)
    }
    steps.forEachIndexed { index, step ->
        HorizontalDivider(Modifier.padding(start = 40.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(maneuverIcon(step.maneuverType), null, Modifier.size(24.dp))
            Column {
                Text(step.instruction, style = MaterialTheme.typography.bodyLarge, fontWeight = if (index == currentIndex) FontWeight.Bold else FontWeight.Normal)
                // A step's length is the distance to the next manoeuvre, shown under its instruction like a maps app.
                val length = steps.getOrNull(index + 1)?.distanceMeters ?: 0.0
                if (length > 0) Text(length.asMetersLabel(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Fastest, shortest and alternative routes, loaded in the background; tap one to preview it. */
@Composable
private fun RouteChoices(choices: List<com.rohanc.navgate.ui.state.RouteChoice>, onChoose: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { c ->
            FilterChip(c.selected, { onChoose(c.id) }, label = {
                Column { Text(c.label, style = MaterialTheme.typography.labelLarge); Text("${c.etaLabel} · ${c.distanceLabel}", style = MaterialTheme.typography.labelSmall) }
            }, shape = RoundedCornerShape(16.dp))
        }
    }
}
