package com.rohanc.navgate.ui.journey

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.data.local.NavGateServices
import com.rohanc.navgate.data.maps.MapArea
import com.rohanc.navgate.data.maps.MapPackBuilder
import com.rohanc.navgate.data.maps.MapPackStore
import com.rohanc.navgate.data.routing.offline.RoutingDataStore
import com.rohanc.navgate.data.routing.offline.RoutingTiles

private fun megabytes(bytes: Long) = if (bytes >= 1_000_000) "%.0f MB".format(bytes / 1_000_000.0) else "%.0f KB".format(bytes / 1_000.0)

@Composable private fun OfflineGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 4.dp))
        GlassPanel(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), weight = GlassWeight.Thin, shadow = false) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
    }
}

/** Last place the phone knew itself to be, without starting GPS. Null when location is off or never fixed. */
private fun lastKnown(context: Context): GeoPoint? {
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!granted) return null
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return runCatching { manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time } }.getOrNull()?.let { GeoPoint(it.latitude, it.longitude) }
}

/** Offline maps (PMTiles packs) and offline routing data (BRouter tiles), managed on the Offline & region data page. */
@Composable
fun OfflineMapAndRoutingGroups(tripActive: Boolean) {
    val context = LocalContext.current
    val services = remember(context) { NavGateServices.get(context) }
    OfflineMapsGroup(services.mapPacks, tripActive)
    OfflineRoutingGroup(services.routingData)
}

@Composable
private fun OfflineMapsGroup(store: MapPackStore, tripActive: Boolean) {
    val context = LocalContext.current
    val packs by store.packs.collectAsState(); val active by store.activeId.collectAsState()
    val status by store.status.collectAsState(); val always by store.alwaysOffline.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull()
            store.importFrom(uri, name)
        }
    }
    val builder = remember { MapPackBuilder() }
    val busy = status is MapPackStore.Status.Working
    OfflineGroup("Offline maps") {
        Text("Save the map for an area so it keeps drawing with no signal. Maps come from OpenFreeMap (OpenStreetMap data) and are stored on this phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (packs.isEmpty()) Text("No offline maps yet.", style = MaterialTheme.typography.bodyMedium)
        packs.forEach { pack ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RadioButton(selected = pack.id == active, onClick = { store.setActive(pack.id) })
                Column(Modifier.weight(1f)) {
                    Text(pack.name, style = MaterialTheme.typography.bodyLarge)
                    Text(megabytes(pack.bytes) + if (pack.imported) " · imported file" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { store.delete(pack.id) }, enabled = !busy) { Icon(Icons.Rounded.Delete, "Delete ${pack.name}") }
            }
        }
        if (packs.isNotEmpty()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Always use offline maps", style = MaterialTheme.typography.bodyLarge)
                Text("Draw the saved map even with a connection, to save mobile data. Otherwise it is used when you are offline.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(always, store::setAlwaysOffline)
        }
        when (val s = status) {
            is MapPackStore.Status.Working -> {
                Text("${s.label}: ${s.done} of ${s.total}", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { if (s.total == 0) 0f else s.done.toFloat() / s.total }, Modifier.fillMaxWidth())
                OutlinedButton(onClick = store::cancel) { Text("Cancel") }
            }
            is MapPackStore.Status.Failed -> {
                Text(s.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = store::dismissFailure) { Text("Dismiss") }
            }
            MapPackStore.Status.Idle -> Unit
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        listOf(10.0 to "Around me · 10 km", 25.0 to "Around me · 25 km", 50.0 to "Around me · 50 km (a whole city region)").forEach { (radius, label) ->
            NgTonalButton(label, Icons.Rounded.Download, {
                val here = lastKnown(context)
                if (here == null) message = "Turn on location (and open the map once) so NavGate knows which area to save."
                else {
                    val area = MapArea.around("Near " + "%.2f, %.2f".format(here.latitude, here.longitude), here, radius)
                    val tiles = builder.tileCount(area)
                    if (tiles > MapPackBuilder.MAX_TILES) message = "That area is too large ($tiles tiles). Choose a smaller one."
                    else { message = null; store.download(area, builder) }
                }
            }, Modifier.fillMaxWidth(), enabled = !busy)
        }
        NgTonalButton("Import a .pmtiles file", Icons.Rounded.FileOpen, { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), enabled = !busy)
        Text("An imported file must be a PMTiles v3 vector map in the OpenMapTiles layout (for example one made from OpenFreeMap).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OfflineRoutingGroup(data: RoutingDataStore) {
    val context = LocalContext.current
    val installed by data.installed.collectAsState(); val progress by data.progress.collectAsState()
    var sizes by remember { mutableStateOf<Map<String, Long?>>(emptyMap()) }
    val here = remember { lastKnown(context) }
    val suggestion = remember(here) { here?.let { RoutingTiles.nameFor(it) } }
    LaunchedEffect(suggestion) { if (suggestion != null && suggestion !in sizes) sizes = sizes + (suggestion to data.remoteSize(suggestion)) }
    OfflineGroup("Offline routing") {
        Text("With routing data on the phone, NavGate can plan and re-plan driving and walking routes with no connection. It is used when the online router cannot answer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (installed.isEmpty()) Text("No routing data yet.", style = MaterialTheme.typography.bodyMedium)
        installed.forEach { tile ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(areaLabel(tile.name), style = MaterialTheme.typography.bodyLarge)
                    Text(megabytes(tile.bytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { data.delete(tile.name) }) { Icon(Icons.Rounded.Delete, "Delete routing data ${tile.name}") }
            }
        }
        progress.forEach { (name, p) ->
            when (p) {
                is RoutingDataStore.Progress.Downloading -> {
                    Text("${areaLabel(name)}: ${megabytes(p.done)}" + if (p.total > 0) " of ${megabytes(p.total)}" else "", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(progress = { if (p.total <= 0) 0f else (p.done.toFloat() / p.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { data.cancel(name) }) { Text("Cancel") }
                }
                is RoutingDataStore.Progress.Failed -> {
                    Text("${areaLabel(name)}: ${p.reason}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { data.download(name) }) { Text("Retry") }
                }
            }
        }
        if (suggestion != null && !data.isInstalled(suggestion) && suggestion !in progress) {
            val size = sizes[suggestion]
            NgPrimaryButton("Download routing data here" + (size?.let { " · ${megabytes(it)}" } ?: ""), Icons.Rounded.Download, { data.download(suggestion) }, Modifier.fillMaxWidth())
            Text("Covers ${areaLabel(suggestion)}, a 5° by 5° area. Trips near its edge may need the neighbouring area too.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (suggestion == null) {
            Text("Turn on location to see which routing data covers where you are.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Routing data: BRouter segments, © OpenStreetMap contributors.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun areaLabel(name: String): String {
    val c = RoutingTiles.centerOf(name) ?: return name
    return "$name · around %.1f°%s, %.1f°%s".format(Math.abs(c.latitude), if (c.latitude >= 0) "N" else "S", Math.abs(c.longitude), if (c.longitude >= 0) "E" else "W")
}
