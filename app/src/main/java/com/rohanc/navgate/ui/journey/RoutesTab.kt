package com.rohanc.navgate.ui.journey

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rohanc.navgate.core.ManualRoute
import com.rohanc.navgate.data.local.ManualRouteStore
import com.rohanc.navgate.model.asMetersLabel
import com.rohanc.navgate.ui.theme.NavTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The Routes tab: the routes you drew yourself, listed like Recents (a small drawing of the route, its name and what it is, its length), with Go to follow
 * one on the roads and a way into the editor. They are kept on this phone.
 */
@Composable
fun BoxScope.RoutesTab(store: ManualRouteStore, a: JourneyActions, onNavigate: (ManualRoute) -> Unit, onEdit: (ManualRoute?) -> Unit) {
    val scope = rememberCoroutineScope()
    var routes by remember { mutableStateOf<List<ManualRoute>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<ManualRoute?>(null) }
    suspend fun reload() { try { routes = store.list(); failed = false } catch (c: CancellationException) { throw c } catch (_: Exception) { failed = true } }
    LaunchedEffect(store) { reload() }
    TabPage("Routes", "Routes", a, actions = {
        com.rohanc.navgate.ui.components.MapControlButton(onClick = { onEdit(null) }) { Icon(Icons.Rounded.Add, "New route") }
        Spacer(Modifier.width(14.dp))
    }) {
        val list = routes
        when {
            failed -> Column {
                EmptyState(Icons.Rounded.Route, "Saved routes could not be loaded.", "Your routes are still on this phone. Try loading them again.")
                NgTonalButton("Retry", Icons.Rounded.Refresh, { scope.launch { reload() } }, Modifier.fillMaxWidth().padding(horizontal = 20.dp))
            }
            list == null -> Unit
            list.isEmpty() -> EmptyState(Icons.Rounded.Route, "Create a route of your own.", "Tap + to pin the places in order. It is kept on this phone.")
            else -> Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                Text("YOUR ROUTES", Modifier.padding(start = 20.dp, top = 6.dp, bottom = 4.dp), style = MaterialTheme.typography.labelSmall, letterSpacing = androidx.compose.ui.unit.TextUnit(1f, androidx.compose.ui.unit.TextUnitType.Sp), fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                GlassPanel(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("routesList"), shape = RoundedCornerShape(22.dp), weight = GlassWeight.Regular, minOpacity = 0.8f, shadow = false) {
                    Column { list.forEachIndexed { i, route ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 92.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                        RouteRow(route, { onEdit(route) }, { onNavigate(route) }, { toDelete = route })
                    } }
                }
            }
        }
    }
    toDelete?.let { route ->
        NgDialog("Delete this route?", "\"${route.name}\" is removed from this phone. Trips you already made are kept.", "Delete", { scope.launch { try { store.delete(route.id); reload() } catch (c: CancellationException) { throw c } catch (_: Exception) { failed = true }; toDelete = null } }, { toDelete = null }, "Keep it", destructive = true)
    }
}

@Composable
internal fun RouteRow(route: ManualRoute, onOpen: () -> Unit, onGo: () -> Unit, onDelete: () -> Unit) {
    val length = remember(route) { route.pins.zipWithNext().sumOf { (a, b) -> a.point.distanceTo(b.point) } }
    Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Edit ${route.name}", onClick = onOpen).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RouteSketch(route, Modifier.size(width = 64.dp, height = 48.dp))
        Column(Modifier.weight(1f)) {
            Text(route.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${if (route.mode == "driving") "Driving" else "Walking"} · ${route.pins.size} pins · ${length.asMetersLabel()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(18.dp)).background(NavTokens.pulse()).clickable(onClickLabel = "Navigate ${route.name}", onClick = onGo).padding(horizontal = 16.dp).semantics { contentDescription = "Navigate ${route.name}" }, contentAlignment = Alignment.Center) {
            Text("Go", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold)
        }
        IconButton(onClick = onDelete, Modifier.size(48.dp)) { Icon(Icons.Rounded.Delete, "Delete ${route.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** The shape of the route drawn small: its pins joined in order, start ringed, end filled. */
@Composable
internal fun RouteSketch(route: ManualRoute, modifier: Modifier = Modifier) {
    val ink = Color(0xFF3DB8FF)
    Canvas(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF0A1222))) {
        val lat = route.pins.map { it.point.latitude }; val lon = route.pins.map { it.point.longitude }
        val minLat = lat.min(); val maxLat = lat.max(); val minLon = lon.min(); val maxLon = lon.max()
        val cos = kotlin.math.cos(Math.toRadians((minLat + maxLat) / 2))
        val w = ((maxLon - minLon) * cos).coerceAtLeast(1e-9); val h = (maxLat - minLat).coerceAtLeast(1e-9)
        val pad = 8.dp.toPx(); val scale = minOf((size.width - 2 * pad) / w.toFloat(), (size.height - 2 * pad) / h.toFloat())
        val ox = (size.width - w.toFloat() * scale) / 2f; val oy = (size.height - h.toFloat() * scale) / 2f
        val pts = route.pins.map { Offset(ox + (((it.point.longitude - minLon) * cos).toFloat()) * scale, oy + ((maxLat - it.point.latitude).toFloat()) * scale) }
        val path = androidx.compose.ui.graphics.Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
        drawPath(path, ink.copy(alpha = 0.35f), style = Stroke(7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, Color(0xFFDDE8FF), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, ink, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(Color.White, 4.dp.toPx(), pts.first()); drawCircle(Color(0xFF3DB8FF), 2.dp.toPx(), pts.first())
        drawCircle(Color(0xFF34D399), 4.dp.toPx(), pts.last())
    }
}
