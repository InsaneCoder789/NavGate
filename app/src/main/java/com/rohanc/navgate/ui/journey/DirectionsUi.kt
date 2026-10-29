package com.rohanc.navgate.ui.journey

import androidx.compose.foundation.horizontalScroll
import com.rohanc.navgate.ui.components.NavGlassSurface

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.rohanc.navgate.model.asMetersLabel
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.rohanc.navgate.core.RouteFailure
import com.rohanc.navgate.model.RouteStep
import com.rohanc.navgate.model.TravelProfile
import com.rohanc.navgate.ui.state.ModeEstimate
import com.rohanc.navgate.ui.state.NavGateUiState

/** Directions: route card on top, travel-mode tabs with times, the route summary and a clear Start button. */
@Composable
fun BoxScope.DirectionsUi(ui: NavGateUiState, steps: List<RouteStep>, routeWarnings: List<String>, permission: Boolean, onInsets: (Float, Float) -> Unit, a: JourneyActions) =
    DirectionsContent(ui, steps, routeWarnings, permission, onInsets, a)

@Composable
private fun BoxScope.DirectionsContent(ui: NavGateUiState, steps: List<RouteStep>, routeWarnings: List<String>, permission: Boolean, onInsets: (Float, Float) -> Unit, a: JourneyActions) {
    val density = LocalDensity.current
    var topPx by remember { mutableIntStateOf(0) }
    var bottomPx by remember { mutableIntStateOf(0) }
    var showSteps by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(topPx, bottomPx) { with(density) { onInsets(topPx.toDp().value, bottomPx.toDp().value) } }
    val driving = ui.travelProfile == TravelProfile.Driving
    val origin = ui.selectedOrigin
    val destination = ui.selectedDestination
    NavGlassSurface(Modifier.align(Alignment.TopCenter).onSizeChanged { topPx = it.height }.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
        shape = RoundedCornerShape(24.dp), dense = true, shadowElevation = 4.dp) {
        // One card, no rings: a plain back arrow, the route (start, dotted line, destination) in the middle, a plain swap arrow at the end.
        Row(Modifier.padding(start = 2.dp, end = 2.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = a.back, modifier = Modifier.size(44.dp)) { Icon(Icons.Rounded.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onSurface) }
            RouteStepper(Modifier.padding(start = 2.dp, end = 6.dp))
            Column(Modifier.weight(1f)) {
                EndpointText("FROM", origin?.title ?: if (permission) "Your location" else "Choose start", origin == null || origin.id.startsWith("live"), { a.openSearch("from") })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                EndpointText("TO", destination?.title ?: "Choose destination", false, { a.openSearch("to") })
            }
            val canSwap = origin != null && destination != null
            com.rohanc.navgate.ui.components.MapControlButton(onClick = a.swap, enabled = canSwap) {
                Icon(Icons.Rounded.SwapVert, "Swap start and destination", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (canSwap) 1f else 0.38f))
            }
        }
    }
    val screenDp = LocalConfiguration.current.screenHeightDp
    val topDp = with(density) { topPx.toDp().value }
    // Expanded steps may grow until just below the route card, never over it.
    val maxHeight = (if (showSteps) (screenDp - topDp - 12f).coerceAtLeast(240f) else screenDp * 0.54f).dp
    NgSheet(Modifier.align(Alignment.BottomCenter).heightIn(max = maxHeight).onSizeChanged { bottomPx = it.height }, onGrabber = { showSteps = !showSteps }, onGrabberDrag = { expand -> showSteps = expand }) {
        Column(Modifier.navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (driving) "Drive" else "Walk", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                destination?.let { IconButton(onClick = { a.share(it) }, modifier = Modifier.size(44.dp)) { Icon(Icons.Rounded.Share, "Share destination") } }
                IconButton(onClick = a.clearPlan, modifier = Modifier.size(44.dp)) { Icon(Icons.Rounded.Close, "Clear directions") }
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
            ButtonRow(Modifier.padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 16.dp), spacing = 8.dp) { item ->
                if (ui.preview != null) NgPrimaryButton(if (ui.startPending) "Starting…" else if (driving) "Start driving" else "Start walking", Icons.Rounded.Navigation, a.start, item(1f), enabled = !ui.startPending)
                else NgPrimaryButton("Preview route", Icons.Rounded.Directions, a.replan, item(1f), enabled = !ui.isLoadingRoute && origin != null && destination != null)
                NgTonalButton("Steps", if (showSteps) Icons.Rounded.ExpandMore else Icons.Rounded.FormatListBulleted, { showSteps = !showSteps }, if (isLargeText()) Modifier.fillMaxWidth() else Modifier, enabled = steps.isNotEmpty())
            }
        }
    }
}

/** One line of the route card: a small FROM or TO label over the place name, 52 dp high, so the stepper beside it lines up. */
@Composable
private fun EndpointText(label: String, text: String, highlight: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().height(52.dp).clickable(onClick = onClick), verticalArrangement = Arrangement.Center) {
        Text(label, style = MaterialTheme.typography.labelSmall, letterSpacing = androidx.compose.ui.unit.TextUnit(1f, androidx.compose.ui.unit.TextUnitType.Sp), fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

/** The start dot, three dots joining it to the destination and the destination pin: the route drawn as a path (97 dp = two rows and the line between them). */
@Composable
private fun RouteStepper(modifier: Modifier = Modifier) {
    Box(modifier.width(22.dp).height(105.dp)) {
        Icon(Icons.Rounded.RadioButtonUnchecked, null, Modifier.align(Alignment.TopCenter).padding(top = 18.dp).size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.align(Alignment.Center), verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            repeat(3) { Box(Modifier.size(3.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.75f), androidx.compose.foundation.shape.CircleShape)) }
        }
        Icon(Icons.Rounded.Flag, null, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).size(18.dp), tint = com.rohanc.navgate.ui.theme.NavTokens.coral())
    }
}

/** The ways to go, side by side as minutes: the chosen one is outlined in the pulse blue, the others are quiet cards. */
@Composable
private fun ModeTabs(ui: NavGateUiState, a: JourneyActions) {
    Row(Modifier.fillMaxWidth().selectableGroup().padding(horizontal = 20.dp).padding(top = 6.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(TravelProfile.Walking to Icons.Rounded.DirectionsWalk, TravelProfile.Driving to Icons.Rounded.DirectionsCar).forEach { (profile, icon) ->
            val chosen = ui.travelProfile == profile
            val estimate: ModeEstimate? = ui.modeEstimates[profile]
            val label = if (chosen && ui.preview != null) ui.preview.etaLabel else estimate?.etaLabel ?: "\u2014"
            val shape = RoundedCornerShape(22.dp)
            val tint = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Column(Modifier.weight(1f).heightIn(min = 76.dp).clip(shape)
                .background(if (chosen) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                .border(if (chosen) 1.5.dp else 1.dp, if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
                .selectable(chosen, role = Role.Tab, onClick = { a.setProfile(profile) }).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(icon, if (profile == TravelProfile.Driving) "Car" else "Walking", Modifier.size(20.dp), tint = tint)
                    Text(if (profile == TravelProfile.Driving) "DRIVE" else "WALK", color = tint, fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold)
                }
                Text(label, color = MaterialTheme.colorScheme.onSurface, fontFamily = com.rohanc.navgate.ui.theme.NavDisplay, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 24.sp, maxLines = 1, softWrap = false)
            }
        }
    }
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

/** The steps as a timeline: a tile with the turn, the instruction and its length; the step you are on is filled with the pulse blue and earlier ones are quiet. */
@Composable
fun StepsList(steps: List<RouteStep>, originLabel: String, currentIndex: Int = -1) {
    Text("Steps", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 8.dp))
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.RadioButtonUnchecked, null, tint = MaterialTheme.colorScheme.primary) }
        Text(originLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
    }
    steps.forEachIndexed { index, step ->
        val current = index == currentIndex
        val past = currentIndex >= 0 && index < currentIndex
        val tile = if (current) Modifier.background(com.rohanc.navgate.ui.theme.NavTokens.pulse(), RoundedCornerShape(14.dp)) else Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
        Row(Modifier.padding(vertical = 8.dp).then(if (past) Modifier.alpha(0.5f) else Modifier), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(44.dp).then(tile), contentAlignment = Alignment.Center) { Icon(maneuverIcon(step.maneuverType), null, Modifier.size(24.dp), tint = if (current) Color.White else MaterialTheme.colorScheme.primary) }
            Column(Modifier.weight(1f)) {
                Text(step.instruction, style = MaterialTheme.typography.bodyLarge, fontWeight = if (current) FontWeight.ExtraBold else FontWeight.Bold)
                // A step's length is the distance to the next manoeuvre, shown under its instruction.
                val length = steps.getOrNull(index + 1)?.distanceMeters ?: 0.0
                if (length > 0) Text(length.asMetersLabel(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (current) Text("Next", Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold)
        }
    }
}

/** Fastest, shortest and alternative routes, loaded in the background; tap one to preview it. */
/** Each route offered, as a card: its name and distance, the time as the headline. The chosen one is outlined and tinted. */
@Composable
private fun RouteChoices(choices: List<com.rohanc.navgate.ui.state.RouteChoice>, onChoose: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        choices.forEach { c ->
            val shape = RoundedCornerShape(20.dp)
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape)
                .background(if (c.selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                .border(if (c.selected) 1.5.dp else 1.dp, if (c.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
                .selectable(c.selected, role = Role.RadioButton, onClick = { onChoose(c.id) }).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(c.label, style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, maxLines = 1)
                    Text(c.distanceLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(c.etaLabel, fontFamily = com.rohanc.navgate.ui.theme.NavDisplay, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 20.sp, maxLines = 1, softWrap = false, color = if (c.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
