package com.rohanc.navgate.data.search

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.rohanc.navgate.core.CampusPlace
import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.model.tidy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.cos

/**
 * Offline restaurants, petrol stations, hospitals, hotels and more for a region, built from OpenStreetMap by
 * `scripts/build_poi_index.py`. If the asset was not built into this APK the index reports itself unavailable and
 * search falls back to the online geocoder.
 */
/** Something that can answer "what is near this point" and "find this name" from offline map data. */
interface PoiSource {
    fun covers(point: GeoPoint): Boolean
    suspend fun warmUp()
    suspend fun nearby(category: PoiCategory, near: GeoPoint, limit: Int = 60): List<CampusPlace>
    /** The nearest everyday places of every kind (food, health, shops, banks, transport ...), nearest first. */
    suspend fun nearbyAll(near: GeoPoint, limit: Int = 40): List<CampusPlace>
    suspend fun search(query: String, near: GeoPoint?, limit: Int = 40): List<CampusPlace>
    /** Everyday places inside a map box, as light pins for drawing on the map; the full details come from [placeById] when one is tapped. */
    suspend fun pins(south: Double, west: Double, north: Double, east: Double, limit: Int): List<MapPin> = emptyList()
    suspend fun placeById(id: String): CampusPlace? = null
    /** The nearest hospitals, police and fire stations, nearest first. */
    suspend fun emergency(near: GeoPoint, limit: Int = 6): List<CampusPlace> = emptyList()
}

/** A place as the map needs it: where it is and what to call it. */
data class MapPin(val id: String, val name: String, val category: String, val latitude: Double, val longitude: Double, val full: CampusPlace? = null, val kind: PinKind = PinKind.Other)

/** Several regions' indexes (Mumbai, Odisha ...) behind one: the one covering the user's position answers "near me"; name search asks all of them. */
class PoiIndexSet(private val indexes: List<PoiSource>) : PoiSource {
    override fun covers(point: GeoPoint) = indexes.any { it.covers(point) }
    override suspend fun warmUp() { indexes.forEach { it.warmUp() } }
    override suspend fun nearby(category: PoiCategory, near: GeoPoint, limit: Int) = indexes.firstOrNull { it.covers(near) }?.nearby(category, near, limit).orEmpty()
    override suspend fun nearbyAll(near: GeoPoint, limit: Int) = indexes.firstOrNull { it.covers(near) }?.nearbyAll(near, limit).orEmpty()
    override suspend fun pins(south: Double, west: Double, north: Double, east: Double, limit: Int): List<MapPin> {
        val centre = GeoPoint((south + north) / 2, (west + east) / 2)
        return indexes.filter { it.covers(centre) }.flatMap { it.pins(south, west, north, east, limit) }
    }
    override suspend fun placeById(id: String): CampusPlace? = indexes.firstNotNullOfOrNull { it.placeById(id) }
    override suspend fun emergency(near: GeoPoint, limit: Int): List<CampusPlace> = indexes.firstOrNull { it.covers(near) }?.emergency(near, limit).orEmpty()
    override suspend fun search(query: String, near: GeoPoint?, limit: Int): List<CampusPlace> {
        val ranked = if (near != null) indexes.sortedByDescending { it.covers(near) } else indexes
        return ranked.flatMap { it.search(query, near, limit) }.distinctBy { it.id }.take(limit)
    }
}

class PoiIndex(private val context: Context, private val assetName: String = "mumbai_pois", private val cityLabel: String = "Mumbai") : PoiSource {
    private val lock = Any()
    private var database: SQLiteDatabase? = null
    private var box: DoubleArray? = null
    private var failed = false
    /** Schema 2 carries e-mail, photo, social page, source, confidence, extra facts and other names; schema 1 files still open. */
    private var richColumns = false

    private val assetPath get() = "search/$assetName.sqlite"
    private val manifestPath get() = "search/$assetName.json"
    val bundled: Boolean by lazy { runCatching { context.assets.list("search").orEmpty().contains("$assetName.sqlite") }.getOrDefault(false) }

    private fun open(): SQLiteDatabase? = synchronized(lock) {
        if (!bundled || failed) return null
        database ?: runCatching {
            val manifest = runCatching { Json.parseToJsonElement(context.assets.open(manifestPath).use { it.readBytes().decodeToString() }) as JsonObject }.getOrNull()
            val expected = manifest?.get("index_sha256")?.jsonPrimitive?.content?.takeIf { Regex("[0-9a-f]{64}").matches(it) } ?: "unknown"
            manifest?.get("bbox")?.jsonPrimitive?.content?.split(',')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 4 }?.let { box = it.toDoubleArray() }
            val file = File(context.noBackupFilesDir, "$assetName-v1.sqlite")
            val stamp = File(context.noBackupFilesDir, "$assetName-v1.sha256")
            if (!file.exists() || !stamp.exists() || stamp.readText() != expected) {
                val temporary = File(context.noBackupFilesDir, "$assetName-v1.tmp")
                try {
                    context.assets.open(assetPath).use { source -> temporary.outputStream().use { target -> source.copyTo(target); target.fd.sync() } }
                    check(temporary.renameTo(file)) { "Could not activate the place index" }
                    stamp.writeText(expected)
                } finally { temporary.delete() }
            }
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).also { database = it; richColumns = it.version >= 2 }
        }.getOrElse { failed = true; null }
    }

    /** Copies the asset ahead of the first query so category buttons answer instantly. */
    override suspend fun warmUp() { withContext(Dispatchers.IO) { open(); Unit } }

    /** Whether [point] lies inside the indexed region, so the index can answer "near me" for it. */
    override fun covers(point: GeoPoint): Boolean { open(); val b = box ?: return bundled; return point.latitude in b[0]..b[2] && point.longitude in b[1]..b[3] }

    /** The closest places of [category], widening the search until enough are found. */
    override suspend fun nearby(category: PoiCategory, near: GeoPoint, limit: Int): List<CampusPlace> = withContext(Dispatchers.IO) {
        val db = open() ?: return@withContext emptyList()
        val lonScale = cos(Math.toRadians(near.latitude)).coerceAtLeast(0.2)
        for (radiusKm in listOf(2.0, 5.0, 12.0, 30.0)) {
            val dLat = radiusKm / 111.0; val dLon = dLat / lonScale
            val rows = read(db, "SELECT ${columns()} FROM pois WHERE category=? AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? " +
                "ORDER BY ((latitude-?)*(latitude-?)+((longitude-?)*(longitude-?))*?) LIMIT ?",
                arrayOf(category.slug, (near.latitude - dLat).toString(), (near.latitude + dLat).toString(), (near.longitude - dLon).toString(), (near.longitude + dLon).toString(),
                    near.latitude.toString(), near.latitude.toString(), near.longitude.toString(), near.longitude.toString(), (lonScale * lonScale).toString(), limit.toString()))
            if (rows.size >= minOf(limit, 12) || radiusKm == 30.0) return@withContext rows
        }
        emptyList()
    }

    /** The nearest everyday places of every kind: widens the circle until there are enough, and leaves out business-to-business listings, villages and car parks. */
    override suspend fun nearbyAll(near: GeoPoint, limit: Int): List<CampusPlace> = withContext(Dispatchers.IO) {
        val db = open() ?: return@withContext emptyList()
        val lonScale = cos(Math.toRadians(near.latitude)).coerceAtLeast(0.2)
        for (radiusKm in listOf(0.6, 1.5, 4.0, 12.0)) {
            val dLat = radiusKm / 111.0; val dLon = dLat / lonScale
            val rows = read(db, "SELECT ${columns()} FROM pois WHERE category NOT IN ('business','locality','parking','toilets') AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? " +
                "ORDER BY ((latitude-?)*(latitude-?)+((longitude-?)*(longitude-?))*?) LIMIT ?",
                arrayOf((near.latitude - dLat).toString(), (near.latitude + dLat).toString(), (near.longitude - dLon).toString(), (near.longitude + dLon).toString(),
                    near.latitude.toString(), near.latitude.toString(), near.longitude.toString(), near.longitude.toString(), (lonScale * lonScale).toString(), limit.toString()))
            if (rows.size >= limit || radiusKm == 12.0) return@withContext rows
        }
        emptyList()
    }

    override suspend fun pins(south: Double, west: Double, north: Double, east: Double, limit: Int): List<MapPin> = withContext(Dispatchers.IO) {
        val db = open() ?: return@withContext emptyList()
        db.rawQuery("SELECT id,name,subtype,latitude,longitude,category FROM pois WHERE category NOT IN ('business','locality','parking','toilets') AND name<>'' AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? LIMIT ?",
            arrayOf(south.toString(), north.toString(), west.toString(), east.toString(), limit.toString())).use { rows ->
            buildList { while (rows.moveToNext()) add(MapPin("poi:${rows.getString(0)}", rows.getString(1), rows.getString(2).orEmpty(), rows.getDouble(3), rows.getDouble(4), kind = PinKind.of(rows.getString(5).orEmpty(), rows.getString(2).orEmpty()))) }
        }
    }

    override suspend fun emergency(near: GeoPoint, limit: Int): List<CampusPlace> = withContext(Dispatchers.IO) {
        val db = open() ?: return@withContext emptyList()
        val lonScale = cos(Math.toRadians(near.latitude)).coerceAtLeast(0.2)
        val marks = PinKind.EMERGENCY_SUBTYPES.joinToString(",") { "?" }
        // Nearest of each kind, so a hospital is never crowded out by several police stations (or the other way round).
        val found = ArrayList<CampusPlace>()
        for (group in listOf(listOf("hospital", "emergency department"), listOf("police station", "police"), listOf("fire station", "fire_station"))) {
            for (radiusKm in listOf(3.0, 8.0, 20.0, 50.0)) {
                val dLat = radiusKm / 111.0; val dLon = dLat / lonScale
                val rows = read(db, "SELECT ${columns()} FROM pois WHERE subtype IN (${group.joinToString(",") { "?" }}) AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? " +
                    "ORDER BY ((latitude-?)*(latitude-?)+((longitude-?)*(longitude-?))*?) LIMIT 2",
                    (group + listOf((near.latitude - dLat).toString(), (near.latitude + dLat).toString(), (near.longitude - dLon).toString(), (near.longitude + dLon).toString(),
                        near.latitude.toString(), near.latitude.toString(), near.longitude.toString(), near.longitude.toString(), (lonScale * lonScale).toString())).toTypedArray())
                if (rows.isNotEmpty()) { found += rows; break }
            }
        }
        found.sortedBy { it.point?.distanceTo(near) ?: Double.MAX_VALUE }.take(limit)
    }

    override suspend fun placeById(id: String): CampusPlace? = withContext(Dispatchers.IO) {
        val raw = id.removePrefix("poi:").takeIf { id.startsWith("poi:") } ?: return@withContext null
        val db = open() ?: return@withContext null
        read(db, "SELECT ${columns()} FROM pois WHERE id=?", arrayOf(raw)).firstOrNull()
    }

    /** Name search over every indexed place, nearest first when a position is known. */
    override suspend fun search(query: String, near: GeoPoint?, limit: Int): List<CampusPlace> = withContext(Dispatchers.IO) {
        val db = open() ?: return@withContext emptyList()
        val expression = ftsExpression(query) ?: return@withContext emptyList()
        val order = if (near == null) "" else "ORDER BY ((p.latitude-${near.latitude})*(p.latitude-${near.latitude})+((p.longitude-${near.longitude})*(p.longitude-${near.longitude}))*${cos(Math.toRadians(near.latitude)).let { it * it }})"
        read(db, "SELECT ${columns().split(',').joinToString(",") { "p.$it" }} FROM names JOIN pois p ON p.rowid=names.docid WHERE names MATCH ? $order LIMIT ?", arrayOf(expression, limit.toString()))
    }

    private fun read(db: SQLiteDatabase, sql: String, args: Array<String>): List<CampusPlace> = db.rawQuery(sql, args).use { rows ->
        val rich = richColumns && rows.columnCount > 13
        fun text(i: Int) = rows.getString(i).orEmpty().tidy().takeIf { it.isNotEmpty() }
        buildList {
            while (rows.moveToNext()) {
                val id = rows.getString(0); val subtype = rows.getString(3); val brand = rows.getString(4); val cuisine = rows.getString(12)
                val label = PlaceSearchRanking.featureLabel(subtype)
                val area = rows.getString(7)
                val subtitle = listOfNotNull(label, cuisine.takeIf { it.isNotBlank() }?.replace(';', ',')?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }, area.takeIf { it.isNotBlank() }).joinToString(" · ")
                // OpenStreetMap ids start with n/w/r and have a page of their own; Overture ids start with o and have none.
                val osmLink = if (id.firstOrNull() in listOf('n', 'w', 'r')) "https://www.openstreetmap.org/${if (id.startsWith("w")) "way" else if (id.startsWith("r")) "relation" else "node"}/${id.drop(1)}" else null
                val others = if (rich) text(19)?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty() else emptyList()
                add(CampusPlace(id = "poi:$id", title = rows.getString(1).orEmpty().tidy(), subtitle = subtitle.tidy(), category = subtype, city = cityLabel, point = GeoPoint(rows.getDouble(5), rows.getDouble(6)),
                    aliases = listOfNotNull(brand.takeIf { it.isNotBlank() }) + others, reviewed = false, sourceUrl = osmLink,
                    phone = text(8), website = text(9), hours = text(10),
                    address = text(7), email = if (rich) text(13) else null, photo = if (rich) text(14) else null, social = if (rich) text(15) else null,
                    source = if (rich) text(16) else null, confidence = if (rich) rows.getDouble(17).takeIf { it in 0.0..1.0 } else null,
                    facts = PlaceFacts.from(brand, cuisine, rows.getString(11).orEmpty(), if (rich) rows.getString(18).orEmpty() else "")))
            }
        }
    }

    private fun columns() = if (richColumns) "$COLUMNS,$RICH" else COLUMNS

    private companion object {
        const val COLUMNS = "id,name,category,subtype,brand,latitude,longitude,address,phone,website,hours,wheelchair,cuisine"
        const val RICH = "email,photo,social,source,confidence,extra,altnames"
    }
}
