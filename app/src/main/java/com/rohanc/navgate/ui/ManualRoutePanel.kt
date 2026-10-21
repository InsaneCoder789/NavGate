package com.rohanc.navgate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rohanc.navgate.ui.journey.GlassPanel
import com.rohanc.navgate.ui.journey.GlassWeight
import com.rohanc.navgate.ui.journey.GmPrimaryButton
import com.rohanc.navgate.ui.journey.GmTonalButton
import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.core.ManualRoute
import com.rohanc.navgate.core.RoutePin
import com.rohanc.navgate.data.local.ManualRouteStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID
import com.rohanc.navgate.ui.state.asDistanceLabel

@Composable
internal fun ManualRoutePanel(store: ManualRouteStore, pins: List<RoutePin>, onPins: (List<RoutePin>)->Unit,
                              selectedPoint: GeoPoint?, onPick: ()->Unit, onClearPoint: ()->Unit,
                              onInspect: (GeoPoint)->Unit, onClearInspection: ()->Unit = {}) {
    val scope=rememberCoroutineScope()
    var unreadable by remember { mutableStateOf<List<String>>(emptyList()) }
    var routes by remember { mutableStateOf<List<ManualRoute>>(emptyList()) }
    var routeId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var name by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    var latitude by rememberSaveable { mutableStateOf("") }
    var longitude by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf("walking") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    suspend fun reload(){val snapshot=store.load();routes=snapshot.routes;unreadable=snapshot.unreadableIds}
    LaunchedEffect(store){try{reload()}catch(e:CancellationException){throw e}catch(e:Exception){message="Saved routes could not be loaded. Retry before editing."}}
    LaunchedEffect(selectedPoint){selectedPoint?.let{latitude=it.latitude.toString();longitude=it.longitude.toString()}}
    fun perform(action: suspend ()->Unit){busy=true;scope.launch{try{action();reload()}catch(e:CancellationException){throw e}catch(e:Exception){message=e.message ?: "Route could not be saved"}finally{busy=false}}}
    // The same glass cards and controls as the rest of the app, laid out in sections.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Pin and label your own ordered route. Pins are stored on this device.", style = MaterialTheme.typography.bodyLarge)
        Text("This is an unverified itinerary. Connecting lines show straight-line distances; road directions and travel times require a routing provider.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        ManualSection("Route") {
            ManualField(name, { name = it.take(120) }, "Route name")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(mode == "walking", { mode = "walking" }, label = { Text("Walking itinerary", maxLines = 1, softWrap = false) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp))
                FilterChip(mode == "driving", { mode = "driving" }, label = { Text("Driving itinerary", maxLines = 1, softWrap = false) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp))
            }
        }

        ManualSection("Add a pin") {
            GmTonalButton("Pin location on map", Icons.Rounded.PinDrop, onPick, Modifier.fillMaxWidth(), enabled = !busy)
            ManualField(label, { label = it.take(120) }, "Pin label")
            // Quick names for the places people save first.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Home", "Work", "School").forEach { quick -> AssistChip({ label = quick }, { Text(quick) }, shape = RoundedCornerShape(16.dp)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ManualField(latitude, { latitude = it.take(30) }, "Latitude", Modifier.weight(1f), KeyboardType.Decimal)
                ManualField(longitude, { longitude = it.take(30) }, "Longitude", Modifier.weight(1f), KeyboardType.Decimal)
            }
            GmPrimaryButton("Add labelled pin", Icons.Rounded.AddLocationAlt, {
                try {
                    val point = GeoPoint(latitude.toDouble(), longitude.toDouble())
                    val pin = RoutePin(UUID.randomUUID().toString(), label.trim(), point)
                    require(pins.lastOrNull()?.point?.distanceTo(point)?.let { it >= 1 } != false) { "Choose a point at least one metre from the last pin" }
                    onPins(pins + pin); label = ""; onClearPoint(); message = null
                } catch (e: Exception) { message = e.message ?: "Enter a label and valid coordinates" }
            }, Modifier.fillMaxWidth(), enabled = !busy && pins.size < 100)
        }

        if (pins.isNotEmpty()) ManualSection("Pins") {
            pins.forEachIndexed { index, pin ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { onInspect(pin.point) }) { Text("${index + 1}. ${pin.label} · ${pin.point.latitude}, ${pin.point.longitude}", style = MaterialTheme.typography.titleSmall) }
                    var edited by rememberSaveable(pin.id, pin.label) { mutableStateOf(pin.label) }
                    ManualField(edited, { edited = it.take(120) }, "Label for pin ${index + 1}")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(enabled = edited.isNotBlank() && !busy, onClick = { onPins(pins.map { if (it.id == pin.id) it.copy(label = edited.trim()) else it }) }) { Text("Apply label ${index + 1}") }
                        TextButton(enabled = index > 0 && !busy, onClick = { val next = pins.toMutableList(); next[index] = next[index - 1].also { next[index - 1] = next[index] }; onPins(next) }) { Text("Move up ${index + 1}") }
                        TextButton(enabled = !busy, onClick = { onPins(pins.filterNot { it.id == pin.id }) }) { Text("Remove pin ${index + 1}") }
                    }
                    if (index < pins.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            if (pins.size >= 2) Text("Straight-line total: ${(pins.zipWithNext().sumOf { (a, b) -> a.point.distanceTo(b.point) }).asDistanceLabel()}", style = MaterialTheme.typography.titleSmall)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GmPrimaryButton("Save manual route", Icons.Rounded.Check, { perform { store.save(ManualRoute(routeId, name.trim(), mode, pins.toList())); message = "Manual route saved" } },
                Modifier.weight(1f), enabled = !busy && pins.size >= 2 && name.isNotBlank())
            GmTonalButton("New manual route", Icons.Rounded.Add, { routeId = UUID.randomUUID().toString(); name = ""; label = ""; latitude = ""; longitude = ""; onPins(emptyList()); onClearPoint(); onClearInspection(); message = null },
                Modifier.weight(1f), enabled = !busy)
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        ManualSection("Saved manual routes") {
            if (routes.isEmpty()) Text("No manual routes saved yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            routes.forEach { route ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = { routeId = route.id; name = route.name; mode = route.mode; onPins(route.pins); onInspect(route.pins.first().point) }) { Text("Open ${route.name} · ${route.pins.size} pins · ${route.mode}") }
                    TextButton(enabled = !busy, onClick = { perform { store.delete(route.id); if (routeId == route.id) { routeId = UUID.randomUUID().toString(); name = ""; onPins(emptyList()); onClearInspection() }; message = "Manual route deleted" } }) { Text("Delete ${route.name}") }
                }
            }
            unreadable.forEach { id ->
                Text("A saved manual route is unreadable. Other routes are still available.", color = MaterialTheme.colorScheme.error)
                TextButton(enabled = !busy, onClick = { perform { store.delete(id); message = "Unreadable record removed" } }) { Text("Remove unreadable route $id") }
            }
            GmTonalButton("Reload saved routes", Icons.Rounded.Refresh, { perform { reload() } }, Modifier.fillMaxWidth(), enabled = !busy)
        }
    }
}

/** One glass card with a heading, used to group the editor into sections. */
@Composable
private fun ManualSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    GlassPanel(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), weight = GlassWeight.Regular, shadow = false) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun ManualField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier.fillMaxWidth(), keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = modifier, shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.Transparent, focusedContainerColor = Color.Transparent))
}
