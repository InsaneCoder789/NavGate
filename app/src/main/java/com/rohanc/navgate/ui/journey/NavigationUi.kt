package com.rohanc.navgate.ui.journey

import com.rohanc.navgate.ui.components.NavGlassSurface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.unit.dp
import com.rohanc.navgate.core.SessionSnapshot
import com.rohanc.navgate.core.SessionStatus
import com.rohanc.navgate.model.RouteStep
import com.rohanc.navgate.model.asEtaLabel
import com.rohanc.navgate.model.asMetersLabel
import java.text.DateFormat
import java.util.Date

private val GuidanceTeal = com.rohanc.navgate.ui.theme.NavGuidance

/** Turn-by-turn guidance: next manoeuvre on top, trip summary and exit at the bottom. */
@Composable
fun BoxScope.NavigationUi(session: SessionSnapshot, steps: List<RouteStep>, voiceOn: Boolean, driving: Boolean, motion: kotlinx.coroutines.flow.StateFlow<com.rohanc.navgate.core.MotionEstimate?>?, onInsets: (Float, Float) -> Unit, a: JourneyActions, online: Boolean = true, locationIssue: String? = null) {
    val density = LocalDensity.current
    // How long since the last accepted position: past a few seconds the map and the speed are guesses, and the screen says so instead of freezing quietly.
    val clockNow by produceState(android.os.SystemClock.elapsedRealtime()) { while (true) { kotlinx.coroutines.delay(1000); value = android.os.SystemClock.elapsedRealtime() } }
    val noSignal = session.status == SessionStatus.SignalLimited || (session.fixElapsedMillis > 0L && clockNow - session.fixElapsedMillis > 6_000L)
    var topPx by remember { mutableIntStateOf(0) }
    var bottomPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(topPx, bottomPx) { with(density) { onInsets(topPx.toDp().value, bottomPx.toDp().value) } }
    val current = steps.getOrNull(session.maneuverIndex)
    var showSensors by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(6_000); hint = false }
    val next = steps.getOrNull(session.maneuverIndex + 1)
    Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { topPx = it.height }.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NavGlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), baseColor = GuidanceTeal, contentColor = Color.White, dense = true, shadowElevation = 6.dp) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Icon(maneuverIcon(current?.maneuverType ?: com.rohanc.navgate.model.ManeuverType.Straight), null, Modifier.size(56.dp))
                Column(Modifier.weight(1f)) {
                    Text(session.distanceToManeuver.asMetersLabel(), style = MaterialTheme.typography.displaySmall)
                    // Far from the turn, "Bear left" would be premature: say to keep going on this road, and the turn comes up when it is near.
                    val farFromTurn = session.distanceToManeuver > 3_000.0 && current?.maneuverType != com.rohanc.navgate.model.ManeuverType.Arrive
                    val road = Regex("(?:onto|on)\\s+(.+?)\\.?$").find(session.instruction)?.groupValues?.get(1)
                    Text(if (farFromTurn) (road?.let { "Continue on $it" } ?: "Continue straight") else session.instruction, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (next != null && current?.maneuverType != com.rohanc.navgate.model.ManeuverType.Arrive && session.distanceToManeuver <= 3_000.0) {
            NavGlassSurface(shape = RoundedCornerShape(20.dp), baseColor = GuidanceTeal, contentColor = Color.White, dense = true, shadowElevation = 3.dp) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Then", style = MaterialTheme.typography.titleMedium); Icon(maneuverIcon(next.maneuverType), null, Modifier.size(26.dp))
                }
            }
        }
        if (hint && driving) GlassPanel(shape = RoundedCornerShape(16.dp), weight = GlassWeight.Regular, shadow = false) {
            Text("Mount your phone safely. Spoken directions are on.", Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
        }
        if (locationIssue != null) LocationNotice(locationIssue, a)
        else if (noSignal) GlassPanel(shape = RoundedCornerShape(16.dp), weight = GlassWeight.Regular, shadow = false, minOpacity = 0.7f) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Column { Text("Searching for GPS", style = MaterialTheme.typography.titleSmall); Text("The map moves again when the signal returns.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        val offlineOffRoute = !online && session.offRoute
        if (!online) OfflineNotice(offlineOffRoute, if (offlineOffRoute) rejoinHint(session) else null)
        if (session.status == SessionStatus.Rerouting) GlassPanel(shape = RoundedCornerShape(16.dp), weight = GlassWeight.Regular, shadow = false) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Text("Finding a new route…", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (motion != null && showSensors) SensorHud(motion, Modifier.align(Alignment.CenterStart).padding(start = 88.dp))
    // Voice and sensors sit under the turn banner; the map's own buttons (recentre, layers, lane, fit) keep the lower right, so the two sets never overlap.
    Column(Modifier.align(Alignment.TopEnd).padding(top = with(density) { topPx.toDp() } + 6.dp, end = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NgMapButton(if (voiceOn) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff, if (voiceOn) "Mute spoken directions" else "Turn on spoken directions", a.toggleVoice)
        // Driving has the speedometer for this (tap it); on foot there is no gauge, so the button stays.
        if (motion != null && !driving) NgMapButton(Icons.Rounded.Sensors, if (showSensors) "Hide sensor readings" else "Show sensor readings", { showSensors = !showSensors })
    }
    // Time to go: the route's figure, scaled by how the trip is really going; a stop keeps it as it was (EtaEstimator). The clock ticks on while stopped.
    val estimator = remember(session.sessionId) { com.rohanc.navgate.core.EtaEstimator() }
    val seconds = remember(session.alongMeters, session.remainingSeconds, session.remainingMeters) {
        estimator.update(System.currentTimeMillis(), session.alongMeters, session.remainingMeters, session.remainingSeconds, session.speedAlongMps)
    }
    val clock by produceState(System.currentTimeMillis()) { while (true) { kotlinx.coroutines.delay(15_000); value = System.currentTimeMillis() } }
    val arrival = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(clock + (seconds * 1000).toLong()))
    // The speedometer sits above the footer, bottom left, where the speed bubble used to be.
    // The speedometer is for driving; on a walk it is not shown.
    if (motion != null && driving) SpeedGauge(motion, { showSensors = !showSensors }, Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = with(density) { bottomPx.toDp() } + 8.dp), noSignal = noSignal)
    TripDock(null, arrival, seconds.asEtaLabel(), session.remainingMeters.asMetersLabel(), a.requestStop, a.showSteps,
        Modifier.align(Alignment.BottomCenter).onSizeChanged { bottomPx = it.height }.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp))
}

/** Guidance cannot continue without a position, so say exactly why and how to fix it. */
@Composable
private fun LocationNotice(issue: String, a: JourneyActions) {
    GlassPanel(shape = RoundedCornerShape(16.dp), weight = GlassWeight.Regular, shadow = false) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.LocationOff, null, Modifier.size(20.dp))
            Text("$issue Guidance pauses until it is back on.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = a.openAppSettings) { Text("Fix", maxLines = 1) }
        }
    }
}

/** Shown while there is no internet. Guidance itself keeps running from the saved route, so this only reassures and, if needed, points back to the road. */
@Composable
private fun OfflineNotice(offRoute: Boolean, rejoin: String?) {
    GlassPanel(shape = RoundedCornerShape(16.dp), weight = GlassWeight.Regular, shadow = false, tint = null) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.CloudOff, null, Modifier.size(20.dp))
            Text(when {
                offRoute && rejoin != null -> "Offline. $rejoin New directions will load as soon as you are back online."
                offRoute -> "Offline. Head back toward the route; new directions will load when you reconnect."
                else -> "Offline. Guidance continues from your saved route."
            }, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** "The route is 120 m north-east of you": a compass pointer back to the planned road, which works with no connection. */
internal fun rejoinHint(session: SessionSnapshot): String? {
    val here = session.location ?: return null
    val route = session.route ?: return null
    val nearest = com.rohanc.navgate.core.projectPath(here, route.geometry) ?: return null
    if (nearest.distanceMeters < 15) return null
    val words = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")
    val direction = words[(((here.bearingTo(nearest.point) + 22.5) % 360) / 45).toInt().coerceIn(0, 7)]
    return "The route is ${nearest.distanceMeters.toInt()} m $direction of you."
}

/** Shown once guidance reaches the destination. */
@Composable
fun BoxScope.ArrivalUi(destination: com.rohanc.navgate.model.PlaceSearchResult?, destinationTitle: String, driving: Boolean, tripSummary: String?, onInsets: (Float, Float) -> Unit, a: JourneyActions) {
    val density = LocalDensity.current
    val kind = com.rohanc.navgate.data.search.PinKind.of("", destination?.category ?: "")
    val tint = com.rohanc.navgate.ui.map.MapIcons.color(kind)
    val address = listOfNotNull(destination?.address?.takeIf { it.isNotBlank() }, destination?.subtitle?.takeIf { it.isNotBlank() && it != destination.address }).firstOrNull()
    // "2.3 km · 3 min · avg 39 km/h · top 50 km/h": three figures in a row, the top speed as its own quiet line.
    val parts = tripSummary?.split(" · ").orEmpty()
    val stats = buildList {
        parts.firstOrNull { it.endsWith(" km") || it.endsWith(" m") }?.let { add("DISTANCE" to it) }
        parts.firstOrNull { it.endsWith(" min") || it.endsWith(" h") }?.let { add("TIME" to it) }
        parts.firstOrNull { it.startsWith("avg ") }?.let { add("AVERAGE" to it.removePrefix("avg ")) }
    }
    val top = parts.firstOrNull { it.startsWith("top ") }?.removePrefix("top ")
    NgSheet(Modifier.align(Alignment.BottomCenter).onSizeChanged { with(density) { onInsets(0f, it.height.toDp().value) } }) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
                Column {
                    Text("You have arrived", style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Text(if (driving) "Driving trip complete" else "Walking trip complete", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(40.dp).background(tint.copy(alpha = 0.14f), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                    Icon(com.rohanc.navgate.ui.map.MapIcons.icon(kind), null, Modifier.size(22.dp), tint = tint)
                }
                Column(Modifier.weight(1f)) {
                    Text(destinationTitle, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    address?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }
            if (stats.isNotEmpty()) {
                Spacer(Modifier.height(18.dp)); HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    stats.forEachIndexed { k, (label, value) ->
                        if (k > 0) Box(Modifier.width(1.dp).height(34.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)))
                        Column(Modifier.weight(1f).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            ArrivalFigure(value)
                            Text(label, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
                if (driving && top != null) Text("Top speed $top", Modifier.fillMaxWidth().padding(bottom = 14.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
            } else Spacer(Modifier.height(16.dp))
            Spacer(Modifier.height(16.dp))
            ButtonRow(spacing = 12.dp) { item ->
                if (driving) NgTonalButton("Parking", Icons.Rounded.LocalParking, a.saveParking, item(1f))
                destination?.let { NgTonalButton("Share", Icons.Rounded.Share, { a.share(it) }, item(1f)) }
            }
            Spacer(Modifier.height(12.dp))
            NgPrimaryButton("Done", Icons.Rounded.Check, a.finishArrival, Modifier.fillMaxWidth())
        }
    }
}

/** A trip figure that gets a point smaller, one at a time, until it fits its third of the card. */
@Composable
private fun ArrivalFigure(text: String) {
    var size by remember(text) { mutableIntStateOf(22) }
    Text(text, fontSize = size.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1, softWrap = false, color = MaterialTheme.colorScheme.onSurface,
        onTextLayout = { if (it.hasVisualOverflow && size > 13) size -= 1 })
}
