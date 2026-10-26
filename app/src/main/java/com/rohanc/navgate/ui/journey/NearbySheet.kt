package com.rohanc.navgate.ui.journey

import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.rohanc.navgate.data.local.NavGateServices
import com.rohanc.navgate.data.local.toAppPlace
import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.core.KiitArea
import com.rohanc.navgate.model.Coordinate
import com.rohanc.navgate.model.PlaceSearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * "Explore nearby" as a sheet you pull up: a short header at rest, half the screen for a quick look, nearly the whole screen to browse.
 * Pull it up over the map to see the nearest restaurants, fuel, hospitals and more, then tap one to open it.
 */
@Composable
internal fun NearbySheet(sheet: Animatable<Float, AnimationVector1D>, screenDp: Float, areaName: String?, kiitMode: Boolean, userLocation: Coordinate?, recentTrips: List<com.rohanc.navgate.model.RouteHistoryEntry>, a: JourneyActions, onPlace: (PlaceSearchResult) -> Unit, maxDp: Float = screenDp * 0.74f) {
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    // Never taller than the room between the search controls and the bottom bar, however large the text is set.
    val full = minOf(screenDp * 0.74f, maxDp).coerceAtLeast(200f); val collapsed = minOf(104f, full - 40f); val half = minOf(screenDp * 0.46f, (collapsed + full) / 2f)
    val anchors = listOf(collapsed, half, full)
    fun settle(velocityDpPerSec: Float) {
        val here = sheet.value
        val target = when {
            velocityDpPerSec < -900f -> anchors.firstOrNull { it > here + 8f } ?: full          // flicked up
            velocityDpPerSec > 900f -> anchors.lastOrNull { it < here - 8f } ?: collapsed       // flicked down
            else -> anchors.minBy { kotlin.math.abs(it - here) }
        }
        scope.launch { sheet.animateTo(target) }
    }
    val listState = rememberLazyListState()
    val connection = remember(sheet) {
        object : NestedScrollConnection {
            // Pulling the list up first grows the sheet, then scrolls the list; pulling down at the top of the list shrinks the sheet.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y / density
                if (dy < 0 && sheet.value < full) { scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { sheet.snapTo((sheet.value - dy).coerceAtMost(full)) }; return Offset(0f, available.y) }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y / density
                if (dy > 0 && sheet.value > collapsed) { scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { sheet.snapTo((sheet.value - dy).coerceAtLeast(collapsed)) }; return Offset(0f, available.y) }
                return Offset.Zero
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity { settle(consumed.y / density + available.y / density); return available }
        }
    }
    val drag = rememberDraggableState { px -> scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { sheet.snapTo((sheet.value - px / density).coerceIn(collapsed, full)) } }

    val context = LocalContext.current
    val services = remember(context) { NavGateServices.get(context) }
    var loading by remember { mutableStateOf(true) }
    // The KIIT directory takes over Nearby only in the KIIT region and only while the user is on or near the campuses; anywhere else it is what is nearby.
    val kiitActive = kiitMode && userLocation != null && KiitArea.contains(GeoPoint(userLocation.latitude, userLocation.longitude))
    // Reading the height only while laying out keeps a drag from recomposing the sheet on every frame.
    val opened by remember(sheet) { derivedStateOf { sheet.value > collapsed + 20f } }
    var wanted by remember { mutableStateOf(false) }
    LaunchedEffect(opened) { if (opened) wanted = true }
    val places by produceState<List<PlaceSearchResult>>(emptyList(), kiitActive, wanted, userLocation?.let { (it.latitude * 200).toInt() to (it.longitude * 200).toInt() }) {
        if (!wanted) return@produceState
        loading = true
        val here = userLocation?.let { GeoPoint(it.latitude, it.longitude) }
        value = try {
            if (kiitActive && here != null) services.repository.regionPlacesNear("kiit", here).map { it.toAppPlace() }
            else services.searchData.nearbyPage(here).places.map { it.toAppPlace() }.take(40)
        } catch (c: CancellationException) { throw c } catch (_: Exception) { emptyList() }
        loading = false
    }

    val emergency by produceState<List<PlaceSearchResult>>(emptyList(), wanted, userLocation?.let { (it.latitude * 200).toInt() to (it.longitude * 200).toInt() }) {
        if (!wanted || userLocation == null) return@produceState
        value = try { services.searchData.emergencyNear(GeoPoint(userLocation.latitude, userLocation.longitude)).map { it.toAppPlace() } }
            catch (c: CancellationException) { throw c } catch (_: Exception) { emptyList() }
    }

    // When the emergency places arrive above a list that has already moved a little, bring the top of the list back into view.
    LaunchedEffect(opened) { if (!opened) listState.scrollToItem(0) }
    LaunchedEffect(opened, emergency.size) { if (opened && emergency.isNotEmpty()) { kotlinx.coroutines.delay(350); listState.scrollToItem(0) } }

    NgSheet(Modifier.layout { measurable, c ->
        val h = (sheet.value * density).roundToInt()
        val p = measurable.measure(c.copy(minHeight = h, maxHeight = h))
        layout(p.width, h) { p.place(0, 0) }
    }.clipToBounds().nestedScroll(connection)) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().draggable(drag, Orientation.Vertical, onDragStopped = { settle(it / density) }).padding(horizontal = 20.dp).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (kiitActive) "KIIT nearby" else areaName ?: "Explore nearby", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(when { kiitActive && opened -> "Campuses, hostels, food and sports, nearest first."; kiitActive -> "Pull up to see the KIIT places around you."; kiitMode && opened -> "KIIT places show here when you are on campus. Showing what is nearby."; opened -> "Nearest first. Tap one to open it."; else -> "Pull up to see what is around you." }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState, contentPadding = PaddingValues(bottom = 12.dp)) {
                if (opened && emergency.isNotEmpty()) {
                    item(key = "emergency-title") { SectionLabel("Emergency nearby") }
                    items(emergency.take(3), key = { "emergency-${it.id}" }, contentType = { "place" }) { place -> ResultRow(place, userLocation) { onPlace(place) } }
                }
                if (opened && recentTrips.isNotEmpty()) {
                    item(key = "recent-title") { SectionLabel("Recent trips") }
                    items(recentTrips.distinctBy { it.destinationTitle to it.travelMode }.take(2), key = { "trip-${it.tripId}-${it.recordedAtEpochMillis}" }, contentType = { "trip" }) { TripRow(it, a) }
                }
                if (opened) item(key = "nearby-title") { SectionLabel(if (kiitActive) "On campus" else "Nearby") }
                if (loading && opened && places.isEmpty()) item(key = "skeleton") { SkeletonRows(4) }
                if (!loading && places.isEmpty() && opened) item(key = "empty") { Text(if (kiitActive) "No KIIT places have a position yet." else "Nothing found nearby right now.", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (opened) items(places, key = { it.id }, contentType = { "place" }) { place -> ResultRow(place, userLocation) { onPlace(place) } }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

/** Grey placeholder rows with the shape of a place row, so the list does not jump when the places arrive. */
@Composable
private fun SkeletonRows(count: Int) {
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "skeleton").animateFloat(0.35f, 0.8f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(800), androidx.compose.animation.core.RepeatMode.Reverse), label = "alpha")
    val fill = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    repeat(count) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp).alpha(pulse), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(44.dp).background(fill, androidx.compose.foundation.shape.CircleShape))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth(0.55f).height(14.dp).background(fill, androidx.compose.foundation.shape.RoundedCornerShape(7.dp)))
                Box(Modifier.fillMaxWidth(0.35f).height(10.dp).background(fill, androidx.compose.foundation.shape.RoundedCornerShape(5.dp)))
            }
        }
    }
}
