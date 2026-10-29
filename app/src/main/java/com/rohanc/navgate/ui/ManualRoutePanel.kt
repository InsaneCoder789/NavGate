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
import androidx.compose.ui.platform.testTag
import com.rohanc.navgate.ui.journey.GlassPanel
import com.rohanc.navgate.ui.journey.GlassWeight
import com.rohanc.navgate.ui.journey.NgPrimaryButton
import com.rohanc.navgate.ui.journey.NgTonalButton
import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.core.ManualRoute
import com.rohanc.navgate.core.RoutePin
import com.rohanc.navgate.data.local.ManualRouteStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID
import com.rohanc.navgate.ui.state.asDistanceLabel

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ManualRoutePanel(store: ManualRouteStore, pins: List<RoutePin>, onPins: (List<RoutePin>)->Unit,
                              selectedPoint: GeoPoint?, onPick: ()->Unit, onClearPoint: ()->Unit,
                              onInspect: (GeoPoint)->Unit, onClearInspection: ()->Unit = {}, onRoadRoute: (ManualRoute)->Unit = {},
                              initialRoute: ManualRoute? = null, onInitialRouteShown: ()->Unit = {}, editorSession: Int = 0) {
    val scope=rememberCoroutineScope()
    val context=androidx.compose.ui.platform.LocalContext.current
    fun shareGpx(route: ManualRoute){context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/gpx+xml").putExtra(android.content.Intent.EXTRA_SUBJECT,route.name).putExtra(android.content.Intent.EXTRA_TEXT,com.rohanc.navgate.core.Gpx.write(route)),"Share GPX"))}
    var unreadable by remember { mutableStateOf<List<String>>(emptyList()) }
    var routes by remember { mutableStateOf<List<ManualRoute>>(emptyList()) }
    var routeId by rememberSaveable(editorSession) { mutableStateOf(UUID.randomUUID().toString()) }
    var name by rememberSaveable(editorSession) { mutableStateOf("") }
    var label by rememberSaveable(editorSession) { mutableStateOf("") }
    var latitude by rememberSaveable(editorSession) { mutableStateOf("") }
    var longitude by rememberSaveable(editorSession) { mutableStateOf("") }
    var mode by rememberSaveable(editorSession) { mutableStateOf("walking") }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var pinErrors by remember { mutableStateOf(ManualPinErrors()) }
    var busy by remember { mutableStateOf(false) }
    suspend fun reload(){val snapshot=store.load();routes=snapshot.routes;unreadable=snapshot.unreadableIds}
    LaunchedEffect(store){try{reload()}catch(e:CancellationException){throw e}catch(e:Exception){message="Saved routes could not be loaded. Try reloading them.";messageIsError=true}}
    // Opened from the Routes tab: load that route into the editor once.
    LaunchedEffect(initialRoute){initialRoute?.let{routeId=it.id;name=it.name;mode=it.mode;onPins(it.pins);onInspect(it.pins.first().point);label="";latitude="";longitude="";pinErrors=ManualPinErrors();message=null;onInitialRouteShown()}}
    LaunchedEffect(selectedPoint){selectedPoint?.let{latitude=it.latitude.toString();longitude=it.longitude.toString()}}
    fun perform(action: suspend ()->Unit){busy=true;scope.launch{try{action();reload()}catch(e:CancellationException){throw e}catch(e:Exception){message="The route could not be updated. Check the details and try again.";messageIsError=true}finally{busy=false}}}
    // The same glass cards and controls as the rest of the app, laid out in sections.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Pin and label your own ordered route. Pins are stored on this device.", style = MaterialTheme.typography.bodyLarge)
        Text("This is an unverified itinerary. Connecting lines show straight-line distances; road directions and travel times require a routing provider.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        ManualSection("Route") {
            ManualField(name, { name = it.take(120) }, "Route name")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(mode == "walking", { mode = "walking" }, label = { Text("Walking itinerary") }, shape = RoundedCornerShape(16.dp))
                FilterChip(mode == "driving", { mode = "driving" }, label = { Text("Driving itinerary") }, shape = RoundedCornerShape(16.dp))
            }
        }

        ManualSection("Add a pin") {
            NgTonalButton("Pin location on map", Icons.Rounded.PinDrop, onPick, Modifier.fillMaxWidth(), enabled = !busy)
            ManualField(label, { label = it.take(120); pinErrors = pinErrors.copy(label = null) }, "Pin label", error = pinErrors.label)
            // Quick names for the places people save first.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Home", "Work", "School").forEach { quick -> AssistChip({ label = quick; pinErrors = pinErrors.copy(label = null) }, { Text(quick) }, shape = RoundedCornerShape(16.dp)) }
            }
            ManualField(latitude, { latitude = it.take(30); pinErrors = pinErrors.copy(latitude = null, point = null) }, "Latitude", keyboard = KeyboardType.Decimal, error = pinErrors.latitude)
            ManualField(longitude, { longitude = it.take(30); pinErrors = pinErrors.copy(longitude = null, point = null) }, "Longitude", keyboard = KeyboardType.Decimal, error = pinErrors.longitude)
            NgPrimaryButton("Add labelled pin", Icons.Rounded.AddLocationAlt, {
                pinErrors = validateManualPin(label, latitude, longitude, pins)
                if (pinErrors.valid) {
                    val point = GeoPoint(latitude.trim().toDouble(), longitude.trim().toDouble())
                    val pin = RoutePin(UUID.randomUUID().toString(), label.trim(), point)
                    onPins(pins + pin); label = ""; latitude = ""; longitude = ""; onClearPoint(); message = null
                }
            }, Modifier.fillMaxWidth(), enabled = !busy && pins.size < 100)
            pinErrors.point?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("manualPinError")) }
            if (pins.size >= 100) Text("A route can contain up to 100 pins.", style = MaterialTheme.typography.bodySmall)
        }

        if (pins.isNotEmpty()) ManualSection("Pins") {
            pins.forEachIndexed { index, pin ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { onInspect(pin.point) }) { Text("${index + 1}. ${pin.label} · ${pin.point.latitude}, ${pin.point.longitude}", style = MaterialTheme.typography.titleSmall) }
                    var edited by rememberSaveable(pin.id, pin.label) { mutableStateOf(pin.label) }
                    ManualField(edited, { edited = it.take(120) }, "Label for pin ${index + 1}")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(enabled = edited.isNotBlank() && !busy, onClick = { onPins(pins.map { if (it.id == pin.id) it.copy(label = edited.trim()) else it }) }) { Text("Apply label ${index + 1}") }
                        TextButton(enabled = index > 0 && !busy, onClick = { val next = pins.toMutableList(); next[index] = next[index - 1].also { next[index - 1] = next[index] }; onPins(next) }) { Text("Move up ${index + 1}") }
                        TextButton(enabled = !busy, onClick = { onPins(pins.filterNot { it.id == pin.id }) }) { Text("Remove pin ${index + 1}") }
                    }
                    if (index < pins.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            if (pins.size >= 2) Text("Straight-line total: ${(pins.zipWithNext().sumOf { (a, b) -> a.point.distanceTo(b.point) }).asDistanceLabel()}", style = MaterialTheme.typography.titleSmall)
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NgPrimaryButton("Save manual route", Icons.Rounded.Check, { perform { store.save(ManualRoute(routeId, name.trim(), mode, pins.toList())); message = "Manual route saved"; messageIsError = false } },
                Modifier.fillMaxWidth(), enabled = !busy && pins.size >= 2 && name.isNotBlank())
            NgTonalButton("New manual route", Icons.Rounded.Add, { routeId = UUID.randomUUID().toString(); name = ""; label = ""; latitude = ""; longitude = ""; onPins(emptyList()); onClearPoint(); onClearInspection(); message = null; pinErrors = ManualPinErrors() },
                Modifier.fillMaxWidth(), enabled = !busy)
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        ManualSection("Saved manual routes") {
            if (routes.isEmpty()) Text("No manual routes saved yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            routes.forEach { route ->
                Column {
                    TextButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { routeId = route.id; name = route.name; mode = route.mode; onPins(route.pins); onInspect(route.pins.first().point) }) { Text("Open ${route.name} · ${route.pins.size} pins · ${route.mode}") }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = !busy && route.pins.size >= 2, onClick = { onRoadRoute(route) }) { Text("Navigate") }
                    TextButton(enabled = !busy && route.pins.size >= 2, onClick = { shareGpx(route) }) { Text("GPX") }
                    TextButton(enabled = !busy, onClick = { perform { store.delete(route.id); if (routeId == route.id) { routeId = UUID.randomUUID().toString(); name = ""; onPins(emptyList()); onClearInspection() }; message = "Manual route deleted"; messageIsError = false } }) { Text("Delete ${route.name}") }
                    }
                }
            }
            unreadable.forEach { id ->
                Text("A saved manual route is unreadable. Other routes are still available.", color = MaterialTheme.colorScheme.error)
                TextButton(enabled = !busy, onClick = { perform { store.delete(id); message = "Unreadable record removed"; messageIsError = false } }) { Text("Remove unreadable route $id") }
            }
            NgTonalButton("Reload saved routes", Icons.Rounded.Refresh, { perform { reload() } }, Modifier.fillMaxWidth(), enabled = !busy)
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
private fun ManualField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier.fillMaxWidth(), keyboard: KeyboardType = KeyboardType.Text, error: String? = null) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = modifier, shape = RoundedCornerShape(16.dp),
        isError = error != null,
        supportingText = if (error != null) { { Text(error) } } else null,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.Transparent, focusedContainerColor = Color.Transparent))
}


/** User-facing validation runs before parsing or constructing a persisted pin. */
internal data class ManualPinErrors(val label: String? = null, val latitude: String? = null, val longitude: String? = null, val point: String? = null) {
    val valid: Boolean get() = label == null && latitude == null && longitude == null && point == null
}

internal fun validateManualPin(label: String, latitude: String, longitude: String, pins: List<RoutePin>): ManualPinErrors {
    val lat = latitude.trim().toDoubleOrNull()
    val lon = longitude.trim().toDoubleOrNull()
    val labelError = if (label.isBlank()) "Enter a label for this pin." else if (label.trim().length > 120) "Use 120 characters or fewer." else null
    val latError = when { latitude.isBlank() -> "Enter latitude."; lat == null || !lat.isFinite() || lat !in -90.0..90.0 -> "Enter a number from −90 to 90."; else -> null }
    val lonError = when { longitude.isBlank() -> "Enter longitude."; lon == null || !lon.isFinite() || lon !in -180.0..180.0 -> "Enter a number from −180 to 180."; else -> null }
    val pointError = when {
        pins.size >= 100 -> "A route can contain up to 100 pins."
        latError == null && lonError == null && pins.lastOrNull()?.point?.distanceTo(GeoPoint(lat!!, lon!!))?.let { it < 1.0 } == true -> "Choose a point at least one metre from the last pin."
        else -> null
    }
    return ManualPinErrors(labelError, latError, lonError, pointError)
}
