package com.rohanc.navgate.ui.journey

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.rohanc.navgate.model.tidy
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rohanc.navgate.core.OpeningHours
import com.rohanc.navgate.data.search.PlaceSearchRanking
import com.rohanc.navgate.model.Coordinate
import com.rohanc.navgate.model.PlaceSearchResult

/** Place card: name, actions and facts, with the search pill above it showing what was found. */
@Composable
fun BoxScope.PlaceUi(place: PlaceSearchResult, saved: Boolean, userLocation: Coordinate?, tripActive: Boolean, onInsets: (Float, Float) -> Unit, a: JourneyActions) {
    val density = LocalDensity.current
    var topPx by remember { mutableIntStateOf(0) }
    var bottomPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(topPx, bottomPx) { with(density) { onInsets(topPx.toDp().value, bottomPx.toDp().value) } }
    Box(Modifier.align(Alignment.TopCenter).onSizeChanged { topPx = it.height }.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        NgSearchPill(place.title, false, { a.openSearch("place") }, leading = { Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.primary) },
            trailing = { IconButton(onClick = a.back) { Icon(Icons.Rounded.Close, "Close place") } })
    }
    NgSheet(Modifier.align(Alignment.BottomCenter).heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.62f).dp).onSizeChanged { bottomPx = it.height }) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // The name, large enough to read and never more than two lines; share is a plain icon (Save is a button below, so it is not repeated up here).
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(place.title.tidy(), Modifier.weight(1f).padding(top = 6.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { a.share(place) }, modifier = Modifier.size(44.dp)) { Icon(Icons.Rounded.Share, "Share place") }
            }
            // A photo, when the data has one (OpenStreetMap image / Wikimedia Commons), above everything else.
            place.photo?.let { url -> PlacePhoto(url); Text("Photo: Wikimedia Commons or OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val kind = PlaceSearchRanking.featureLabel(place.category ?: place.type.name)
            val away = distanceFrom(userLocation, place)
            Text(listOfNotNull(kind, away?.let { "$it away" }).joinToString(" · "), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // The address has its own line below; the subtitle only says something new when there is no address.
            val subtitle = place.subtitle.tidy()
            if (place.address.isNullOrBlank() && subtitle.isNotBlank() && !subtitle.equals(kind, ignoreCase = true)) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val status = remember(place.hours) { OpeningHours.status(place.hours, java.time.LocalDateTime.now()) }
            if (status != null) Text(status.label, style = MaterialTheme.typography.titleSmall, color = if (status.open) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
            // One row of actions, the main one widest. Website and e-mail are in the lines below, so nothing has to wrap onto a second row.
            ButtonRow { item ->
                NgPrimaryButton("Directions", Icons.Rounded.Directions, { a.directions(place) }, item(1.5f), enabled = !tripActive && place.coordinate != null)
                NgTonalButton(if (saved) "Saved" else "Save", if (saved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, { a.toggleSaved(place) }, item(1f))
                place.phone?.let { phone -> NgTonalButton("Call", Icons.Rounded.Call, { a.call(phone) }, item(1f)) }
            }
            // Everything else known about the place, one labelled line each.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                place.address?.tidy()?.takeIf { it.isNotEmpty() }?.let { PlaceLine(Icons.Rounded.Place, it) }
                place.phone?.let { phone -> PlaceLine(Icons.Rounded.Call, phone) { a.call(phone) } }
                place.email?.let { mail -> PlaceLine(Icons.Rounded.Email, mail) { a.email(mail) } }
                place.website?.let { site -> PlaceLine(Icons.Rounded.Language, site.removePrefix("https://").removePrefix("http://").trimEnd('/')) { a.openWebsite(site) } }
                place.social?.let { link -> PlaceLine(Icons.Rounded.Link, link.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')) { a.openWebsite(link) } }
                place.hours?.let { PlaceLine(Icons.Rounded.Schedule, it.replace("; ", "\n")) }
                place.facts.forEach { (label, value) -> PlaceLine(null, "$label: $value") }
            }
            place.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (place.coordinate == null) Text("This result has no usable map coordinates.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            place.coordinate?.let { Text("%.5f, %.5f".format(it.latitude, it.longitude), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val sources = place.source?.split('+')?.map { part -> if (part == "osm") "OpenStreetMap contributors" else if (part.startsWith("overture")) "Overture Maps" + part.substringAfter(':', "").takeIf { it.isNotEmpty() }?.let { " (${it.replaceFirstChar { c -> c.uppercase() }})" }.orEmpty() else part }
            if (sources != null) Text("Data: ${sources.joinToString(" and ")}" + (place.confidence?.takeIf { place.source != "osm" }?.let { " · confidence ${(it * 100).toInt()}%" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else if (place.id.startsWith("poi:")) Text("Place data © OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One fact about a place: an icon (or none) and the text, which may run to several lines. */
@Composable
private fun PlaceLine(icon: androidx.compose.ui.graphics.vector.ImageVector?, text: String, onClick: (() -> Unit)? = null) {
    Row((if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).heightIn(min = if (onClick != null) 36.dp else 0.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) else Spacer(Modifier.width(32.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}
