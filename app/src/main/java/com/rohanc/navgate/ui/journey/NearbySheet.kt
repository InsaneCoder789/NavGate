package com.rohanc.navgate.ui.journey

import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.rounded.LocalHospital
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
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
internal fun NearbySheet(sheet: Animatable<Float, AnimationVector1D>, screenDp: Float, areaName: String?, kiitMode: Boolean, userLocation: Coordinate?, recentTrips: List<com.rohanc.navgate.model.RouteHistoryEntry>, a: JourneyActions, onPlace: (PlaceSearchResult) -> Unit, maxDp: Float = screenDp * 0.74f, suggestion: com.rohanc.navgate.ui.state.Suggestion? = null, reduceMotion: Boolean = false) {
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    // Never taller than the room between the search controls and the bottom bar, however large the text is set.
    val full = minOf(screenDp * 0.74f, maxDp).coerceAtLeast(0f); val collapsed = minOf(104f, full); val half = nearbyPeekDp(screenDp).coerceIn(collapsed, full)
    val anchors = listOf(collapsed, half, full)
    suspend fun moveSheet(target: Float) { if (reduceMotion) sheet.snapTo(target) else sheet.animateTo(target) }
    fun settle(velocityDpPerSec: Float) {
        val here = sheet.value
        val target = when {
            velocityDpPerSec < -900f -> anchors.firstOrNull { it > here + 8f } ?: full          // flicked up
            velocityDpPerSec > 900f -> anchors.lastOrNull { it < here - 8f } ?: collapsed       // flicked down
            else -> anchors.minBy { kotlin.math.abs(it - here) }
        }
        scope.launch { moveSheet(target) }
    }
    val listState = rememberLazyListState()
    val connection = remember(sheet, full, collapsed, density, reduceMotion) {
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
        val h = (sheet.value.coerceIn(0f, full) * density).roundToInt().coerceIn(0, c.maxHeight)
        val p = measurable.measure(c.copy(minHeight = h, maxHeight = h))
        layout(p.width, h) { p.place(0, 0) }
    }.clipToBounds().nestedScroll(connection)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().draggable(drag, Orientation.Vertical, onDragStopped = { settle(it / density) }).padding(horizontal = 20.dp).padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (kiitActive) "KIIT NEARBY" else (areaName?.let { "NEARBY \u00B7 ${it.uppercase()}" } ?: "NEARBY"), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, letterSpacing = 1.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(if (opened && sheet.value >= full - 4f) "Show less" else "See all", Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).clickable { scope.launch { moveSheet(if (sheet.value >= full - 4f) half else full) } }.padding(horizontal = 8.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState, contentPadding = PaddingValues(bottom = 12.dp)) {
                if (opened && suggestion != null) item(key = "smart-row") { Box(Modifier.padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 4.dp)) { SmartRow(suggestion, { a.planAgain(suggestion.title) }) } }
                if (opened) item(key = "tiles") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NearbyTile("Food", androidx.compose.material.icons.Icons.Rounded.Restaurant, androidx.compose.ui.graphics.Color(0xFFFF8A3D), Modifier.weight(1f)) { a.openCategory(com.rohanc.navgate.data.search.PoiCategory.Food) }
                        NearbyTile("Fuel", androidx.compose.material.icons.Icons.Rounded.LocalGasStation, androidx.compose.ui.graphics.Color(0xFF7C5CFF), Modifier.weight(1f)) { a.openCategory(com.rohanc.navgate.data.search.PoiCategory.Fuel) }
                        NearbyTile("Emergency", androidx.compose.material.icons.Icons.Rounded.LocalHospital, null, Modifier.weight(1f)) { scope.launch { moveSheet(full); if(reduceMotion) listState.scrollToItem(if (suggestion != null) 2 else 1) else listState.animateScrollToItem(if (suggestion != null) 2 else 1) } }
                    }
                }
                if (opened && emergency.isNotEmpty()) {
                    item(key = "emergency-title") { SectionLabel("Emergency nearby") }
                    items(emergency.take(3), key = { "emergency-${it.id}" }, contentType = { "place" }) { place -> ResultRow(place, userLocation) { onPlace(place) } }
                }
                if (opened && recentTrips.isNotEmpty()) {
                    item(key = "recent-title") { SectionLabel("Recent trips") }
                    items(recentTrips.distinctBy { it.destinationTitle to it.travelMode }.take(2), key = { "trip-${it.tripId}-${it.recordedAtEpochMillis}" }, contentType = { "trip" }) { TripRow(it, a) }
                }
                if (opened && kiitActive) item(key = "nearby-title") { SectionLabel("On campus") }
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
    val fill = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    repeat(count) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp).alpha(0.65f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(44.dp).background(fill, androidx.compose.foundation.shape.CircleShape))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth(0.55f).height(14.dp).background(fill, androidx.compose.foundation.shape.RoundedCornerShape(7.dp)))
                Box(Modifier.fillMaxWidth(0.35f).height(10.dp).background(fill, androidx.compose.foundation.shape.RoundedCornerShape(5.dp)))
            }
        }
    }
}

/** The height the Explore sheet rests at: room for the "Leaving now?" row and the three tiles, never more than 36% of the screen. */
fun nearbyPeekDp(screenDp: Float): Float = minOf(272f, screenDp * 0.36f)

/** A square-ish shortcut: Food, Fuel, Emergency. Emergency is the one filled colour on the screen. */
@Composable
private fun NearbyTile(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val emergency = tint == null
    val coral = com.rohanc.navgate.ui.theme.NavTokens.coral()
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    val face = if (emergency) Modifier.background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(androidx.compose.ui.graphics.Color(0xFFE5484D), coral))) else Modifier
    val inner: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().heightIn(min = 84.dp).padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, null, Modifier.size(24.dp), tint = tint ?: androidx.compose.ui.graphics.Color.White)
            Text(label, color = if (emergency) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, softWrap = true)
        }
    }
    if (emergency) Box(modifier.clip(shape).then(face).clickable(onClick = onClick)) { inner() }
    else GlassPanel(modifier, shape = shape, weight = GlassWeight.Regular, minOpacity = 0.8f, onClick = onClick) { inner() }
}
