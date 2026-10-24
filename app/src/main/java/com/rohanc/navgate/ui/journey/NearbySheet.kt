package com.rohanc.navgate.ui.journey

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
import androidx.compose.ui.Alignment
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
import com.rohanc.navgate.data.search.PoiCategory
import com.rohanc.navgate.model.Coordinate
import com.rohanc.navgate.model.PlaceSearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * "Explore nearby" as a sheet you pull up: a short header at rest, half the screen for a quick look, nearly the whole screen to browse.
 * Pull it up over the map to see the nearest restaurants, fuel, hospitals and more, then tap one to open it.
 */
@Composable
internal fun NearbySheet(sheet: Animatable<Float, AnimationVector1D>, screenDp: Float, areaName: String?, regionId: String, userLocation: Coordinate?, recentTrips: List<com.rohanc.navgate.model.RouteHistoryEntry>, a: JourneyActions, onPlace: (PlaceSearchResult) -> Unit) {
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val collapsed = 150f; val half = screenDp * 0.46f; val full = screenDp * 0.74f
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

    val category = PoiCategory.entries.first()
    val context = LocalContext.current
    val services = remember(context) { NavGateServices.get(context) }
    var loading by remember { mutableStateOf(true) }
    // Reading the height only while laying out keeps a drag from recomposing the sheet on every frame.
    val opened by remember(sheet) { derivedStateOf { sheet.value > collapsed + 20f } }
    var wanted by remember { mutableStateOf(false) }
    LaunchedEffect(opened) { if (opened) wanted = true }
    val places by produceState<List<PlaceSearchResult>>(emptyList(), category, regionId, wanted, userLocation?.let { (it.latitude * 100).toInt() to (it.longitude * 100).toInt() }) {
        if (!wanted) return@produceState
        loading = true
        value = try { services.searchData.searchPage(category.query, regionId).places.map { it.toAppPlace() }.take(25) }
            catch (c: CancellationException) { throw c } catch (_: Exception) { emptyList() }
        loading = false
    }

    NgSheet(Modifier.layout { measurable, c ->
        val h = (sheet.value * density).roundToInt()
        val p = measurable.measure(c.copy(minHeight = h, maxHeight = h))
        layout(p.width, h) { p.place(0, 0) }
    }.clipToBounds().nestedScroll(connection)) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().draggable(drag, Orientation.Vertical, onDragStopped = { settle(it / density) }).padding(horizontal = 20.dp).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(areaName ?: "Explore nearby", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(if (opened) "Nearest first. Tap one to open it." else "Pull up to see what is around you.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState, contentPadding = PaddingValues(bottom = 12.dp)) {
                if (recentTrips.isNotEmpty()) {
                    item(key = "recent-title") { SectionLabel("Recent trips") }
                    items(recentTrips.take(5), key = { "trip-${it.tripId}-${it.recordedAtEpochMillis}" }, contentType = { "trip" }) { TripRow(it, a) }
                }
                item(key = "nearby-title") { SectionLabel("Nearby") }
                if (!loading && places.isEmpty() && opened) item(key = "empty") { Text("Nothing found nearby right now.", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(places, key = { it.id }, contentType = { "place" }) { place -> ResultRow(place, userLocation) { onPlace(place) } }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}
