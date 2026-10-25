package com.rohanc.navgate.ui

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.rohanc.navgate.app.CrashReporter
import com.rohanc.navgate.app.CrashUploader
import com.rohanc.navgate.core.*
import com.rohanc.navgate.ui.location.BindLocationTracking
import com.rohanc.navgate.ui.location.BindMotionSensors
import com.rohanc.navgate.ui.location.TurnHaptics
import com.rohanc.navgate.ui.map.RouteTilePrefetcher
import com.rohanc.navgate.ui.map.rememberMapConnection
import com.rohanc.navgate.ui.state.NavGateViewModel
import kotlinx.coroutines.delay

/** If the app crashed last time, offer to share what happened. Nothing is sent unless the user taps Share. */
@Composable
internal fun CrashReportPrompt(autoSend: Boolean) {
    val context = LocalContext.current
    var crashReport by remember { mutableStateOf(CrashReporter.pending(context)) }
    val url = com.rohanc.navgate.BuildConfig.CRASH_REPORT_URL
    // Opted in: send quietly and move on. If it fails, keep the report and fall back to asking.
    var sent by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(autoSend, crashReport) {
        val report = crashReport
        if (autoSend && url.isNotBlank() && report != null && !sent) {
            sent = true
            if (kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CrashUploader.send(url, report) }) { CrashReporter.clear(context); crashReport = null } else failed = true
        }
    }
    val quiet = autoSend && url.isNotBlank() && !failed
    crashReport?.takeUnless { quiet }?.let { report ->
        com.rohanc.navgate.ui.journey.NgDialog("NavGate closed unexpectedly", "Sorry about that. You can share the technical details with the developer. They contain no places, routes or personal data.", "Share details", {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "NavGate crash report").putExtra(Intent.EXTRA_TEXT, report), "Share crash details"))
            CrashReporter.clear(context); crashReport = null
        }, { CrashReporter.clear(context); crashReport = null }, "Dismiss")
    }
}

/** Sensors and GPS in, one fused estimate out: 10 Hz to the vehicle marker, 2 Hz to guidance. */
@Composable
internal fun LocationPipeline(vm: NavGateViewModel, fusion: MotionFusion, permission: Boolean, active: Boolean, screen: String, displayLocation: () -> GeoPoint?) {
    val saver = (LocalContext.current.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager)
    BindMotionSensors(enabled = permission && (active || (screen == "Camera" || screen == "Live")), fusion = fusion) { displayLocation() }
    BindLocationTracking(enabled = permission, navigating = active, onLocationSample = { fix ->
        fusion.onGps(GpsSample(fix.point, fix.accuracyMeters, fix.speedMps, fix.heading, fix.elapsedMillis)); vm.onRawFix(fix)
    }, onSignalTick = vm::checkSignal)
    val motionBusy by rememberUpdatedState(active || (screen == "Camera" || screen == "Live"))
    LaunchedEffect(fusion, permission) {
        if (!permission) return@LaunchedEffect
        var lastFed = 0L
        while (true) {
            // Smooth 10 Hz only while the car marker or AR needs it; a relaxed 2 Hz otherwise.
            // 10 Hz only at driving speed (the marker glides between samples); half that on foot, and slower again in battery saver.
            delay(MotionRate.intervalMillis(motionBusy, fusion.estimate(SystemClock.elapsedRealtime())?.speedMps ?: 0.0, saver.isPowerSaveMode))
            val now = SystemClock.elapsedRealtime()
            val estimate = fusion.estimate(now)
            vm.onMotion(estimate)
            if (estimate != null && now - lastFed >= 500 && now - estimate.elapsedMillis < 1_000 && !(estimate.usingDeadReckoning && estimate.accuracyMeters > 45)) {
                lastFed = now
                vm.onLocationFix(LocationFix(estimate.point, now, estimate.accuracyMeters, estimate.headingDegrees, estimate.speedMps))
            }
        }
    }
}

private const val PREFETCH_STEP_METERS = 2_000.0
private const val PREFETCH_AHEAD_METERS = 4_200.0

internal class TripRuntime(val online: Boolean, val tripSummary: String?)

/** Connectivity, route-tile warming, screen wake, turn haptics and the arrival summary. */
@Composable
internal fun rememberTripRuntime(vm: NavGateViewModel, session: SessionSnapshot, active: Boolean, screen: String): TripRuntime {
    val context = LocalContext.current
    val online = rememberMapConnection()
    SideEffect { vm.networkAvailable = online }
    val tripRoute = session.route
    // Save the map along the route while there is a connection, so it still draws if the signal drops mid-trip.
    // Only the next few kilometres at a time, ahead of where the trip has got to, and not at all in battery saver: downloading and
    // caching tiles for a whole long route up front costs radio and CPU time the user may never need.
    val line = remember(tripRoute?.id) { tripRoute?.let { RouteGeometry(it.geometry) } }
    val checkpoint = if (tripRoute != null && screen == "Navigation") (tripRoute.routeToShapeMeters(session.alongMeters) / PREFETCH_STEP_METERS).toInt() else 0
    LaunchedEffect(tripRoute?.id, online, screen, checkpoint) {
        val power = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        if (online && line != null && tripRoute != null && !power.isPowerSaveMode && screen in listOf("Directions", "Route", "Navigation", "Camera", "Live")) {
            val from = checkpoint * PREFETCH_STEP_METERS - 200.0; val to = from + PREFETCH_AHEAD_METERS
            val window = line.points.filterIndexed { i, _ -> line.cumulative[i] in from..to }
            if (window.size >= 2) RouteTilePrefetcher.prefetch(context, "${tripRoute.id}#$checkpoint", window)
        }
    }
    // Keep the screen on while guiding, and give a touch cue before each turn.
    DisposableEffect(active, screen) {
        val window = (context as? Activity)?.window
        if (active || (screen == "Camera" || screen == "Live")) window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val drivingTrip = (tripRoute?.profile ?: "") == "driving"
    val toTurn = session.distanceToManeuver
    val cue = if (!active) 0 else if (drivingTrip) when { toTurn < 40 -> 2; toTurn < 150 -> 1; else -> 0 } else when { toTurn < 12 -> 2; toTurn < 40 -> 1; else -> 0 }
    val turnKind = tripRoute?.maneuvers?.getOrNull(session.maneuverIndex)?.kind
    LaunchedEffect(active, session.maneuverIndex, cue) {
        if (cue > 0) TurnHaptics.cue(context, turnKind, cue)
    }
    var tripStartMillis by remember { mutableLongStateOf(0L) }
    var topSpeedMps by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(active) { if (active && tripStartMillis == 0L) { tripStartMillis = System.currentTimeMillis(); topSpeedMps = 0.0 } }
    // Collected in an effect, not in composition, so the 10 Hz motion updates do not recompose the whole screen.
    LaunchedEffect(active) { if (active) vm.motionState.collect { m -> m?.speedMps?.takeIf { it < 80 }?.let { if (it > topSpeedMps) topSpeedMps = it } } }
    LaunchedEffect(screen) { if (screen == "Explore") tripStartMillis = 0L }
    val summary = if (screen == "Arrival" && tripStartMillis > 0 && tripRoute != null) {
        val minutes = ((System.currentTimeMillis() - tripStartMillis) / 60_000.0).coerceAtLeast(1.0)
        val km = tripRoute.distanceMeters / 1000.0
        listOfNotNull("%.1f km".format(km), "${minutes.toInt()} min", "avg %d km/h".format((km / (minutes / 60.0)).toInt()),
            topSpeedMps.takeIf { drivingTrip && it > 1 }?.let { "top %d km/h".format((it * 3.6).toInt()) }).joinToString(" · ")
    } else null
    return TripRuntime(online, summary)
}

/** How often the fused position is handed to the rest of the app. */
internal object MotionRate {
    fun intervalMillis(busy: Boolean, speedMps: Double, powerSave: Boolean): Long {
        val base = if (!busy) 500L else if (speedMps > 5.0) 100L else 200L
        return if (powerSave) maxOf(base, 250L) else base
    }
}
