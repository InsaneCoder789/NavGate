package com.rohanc.navgate.ui.journey

import androidx.compose.foundation.background
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
    Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { topPx = it.height }.statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        com.rohanc.navgate.ui.components.MapControlButton(onClick = a.back) { Icon(Icons.Rounded.ArrowBack, "Close place") }
        com.rohanc.navgate.ui.components.MapControlButton(onClick = { a.share(place) }) { Icon(Icons.Rounded.Share, "Share place") }
    }
    NgSheet(Modifier.align(Alignment.BottomCenter).heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.62f).dp).onSizeChanged { bottomPx = it.height }) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Tags first (what it is, whether it is open), then the name in the display face, never more than two lines.
            val kind = if (isMonument(place)) "Monument" else PlaceSearchRanking.featureLabel(place.category ?: place.type.name)
            val status = remember(place.hours) { OpeningHours.status(place.hours, java.time.LocalDateTime.now()) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PlaceTag(kind, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                if (status != null) PlaceTag(status.label, if (status.open) com.rohanc.navgate.ui.theme.NavTokens.go() else com.rohanc.navgate.ui.theme.NavTokens.coral(), (if (status.open) com.rohanc.navgate.ui.theme.NavTokens.go() else com.rohanc.navgate.ui.theme.NavTokens.coral()).copy(alpha = 0.14f))
            }
            Text(place.title.tidy(), style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // A photo, when the data has one (OpenStreetMap image / Wikimedia Commons), above everything else.
            place.photo?.let { url -> PlacePhoto(url) }
            val away = distanceFrom(userLocation, place)
            if (away != null) Text("$away away", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // The address has its own line below; the subtitle only says something new when there is no address.
            val subtitle = place.subtitle.tidy()
            if (place.address.isNullOrBlank() && subtitle.isNotBlank() && !subtitle.equals(kind, ignoreCase = true)) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // One row: Directions takes the width, Save and Call are round buttons beside it. Website and e-mail are in the lines below, so nothing wraps.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                NgPrimaryButton("Directions", Icons.Rounded.Directions, { a.directions(place) }, Modifier.weight(1f), enabled = !tripActive && place.coordinate != null)
                com.rohanc.navgate.ui.components.MapControlButton(onClick = { a.toggleSaved(place) }) { Icon(if (saved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, if (saved) "Saved" else "Save", tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
                place.phone?.let { phone -> com.rohanc.navgate.ui.components.MapControlButton(onClick = { a.call(phone) }) { Icon(Icons.Rounded.Call, "Call") } }
            }
            // Everything else known about the place, one labelled line each.
            val visibleFacts = displayPlaceFacts(place)
            val hasFacts = !place.address.isNullOrBlank() || place.phone != null || place.email != null || place.website != null || place.social != null || place.hours != null || visibleFacts.isNotEmpty()
            if (hasFacts) GlassPanel(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp), weight = GlassWeight.Regular, minOpacity = 0.8f, shadow = false) { Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                place.address?.tidy()?.takeIf { it.isNotEmpty() }?.let { PlaceLine(Icons.Rounded.Place, it) }
                place.phone?.let { phone -> PlaceLine(Icons.Rounded.Call, phone) { a.call(phone) } }
                place.email?.let { mail -> PlaceLine(Icons.Rounded.Email, mail) { a.email(mail) } }
                place.website?.let { site -> PlaceLine(Icons.Rounded.Language, site.removePrefix("https://").removePrefix("http://").trimEnd('/')) { a.openWebsite(site) } }
                place.social?.let { link -> PlaceLine(Icons.Rounded.Link, socialLabel(link)) { a.openWebsite(link) } }
                place.hours?.let { PlaceLine(Icons.Rounded.Schedule, it.replace("; ", "\n")) }
                visibleFacts.forEach { (label, value) -> PlaceLine(null, "$label: $value") }
            } }
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

@Composable
private fun PlaceTag(text: String, color: androidx.compose.ui.graphics.Color, background: androidx.compose.ui.graphics.Color) {
    Text(text, Modifier.background(background, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp), color = color, fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp), fontWeight = FontWeight.ExtraBold, maxLines = 1)
}

/** A brand tag is business metadata, not an authoritative identity for a physical landmark. */
internal fun displayPlaceFacts(place: PlaceSearchResult): Map<String, String> {
    val kind = PlaceSearchRanking.featureLabel(place.category).lowercase(java.util.Locale.ROOT)
    val physicalFeature = isMonument(place) || kind in setOf("monument", "mountain", "island", "lake", "beach", "river", "bridge")
    return place.facts.filterKeys { label -> !physicalFeature || !label.equals("Brand", true) }
}

/** "Facebook" instead of "facebook.com/182719182431276": a numeric or long social address reads as noise, so a known network is named and any other link shows its host. */
internal fun socialLabel(link: String): String {
    val bare = link.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
    val host = bare.substringBefore('/').lowercase()
    val network = when {
        host.endsWith("facebook.com") || host.endsWith("fb.com") -> "Facebook"
        host.endsWith("instagram.com") -> "Instagram"
        host == "x.com" || host.endsWith("twitter.com") -> "X (Twitter)"
        host.endsWith("youtube.com") || host == "youtu.be" -> "YouTube"
        host.endsWith("linkedin.com") -> "LinkedIn"
        host.endsWith("wa.me") || host.endsWith("whatsapp.com") -> "WhatsApp"
        else -> null
    }
    val handle = bare.substringAfter('/', "").substringBefore('?').trimEnd('/')
    return when {
        network == null -> bare
        handle.isBlank() || handle.all { it.isDigit() } || handle.length > 24 -> network
        else -> "$network · ${handle.removePrefix("@")}"
    }
}
