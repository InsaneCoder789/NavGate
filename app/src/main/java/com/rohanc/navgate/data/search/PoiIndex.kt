package com.rohanc.navgate.data.search

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.rohanc.navgate.core.CampusPlace
import com.rohanc.navgate.core.GeoPoint
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
class PoiIndex(private val context: Context, private val assetName: String = "mumbai_pois") {
    private val lock = Any()
    private var database: SQLiteDatabase? = null
    private var box: DoubleArray? = null
    private var failed = false

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
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).also { database = it }
        }.getOrElse { failed = true; null }
    }

    /** Copies the asset ahead of the first query so category buttons answer instantly. */
    suspend fun warmUp() = withContext(Dispatchers.IO) { open(); Unit }

    /** Whether [point] lies inside the indexed region, so the index can answer "near me" for it. */
    fun covers(point: GeoPoint): Boolean { open(); val b = box ?: return bundled; return point.latitude in b[0]..b[2] && point.longitude in b[1]..b[3] }

    /** The closest places of [category], widening the search until enough are found. */
    suspend fun nearby(category: PoiCategory, near: GeoPoint, limit: Int = 60): List<CampusPlace> = withContext(Dispatchers.IO) {
        val db = open() ?: return@withContext emptyList()
        val lonScale = cos(Math.toRadians(near.latitude)).coerceAtLeast(0.2)
        for (radiusKm in listOf(2.0, 5.0, 12.0, 30.0)) {
            val dLat = radiusKm / 111.0; val dLon = dLat / lonScale
            val rows = read(db, "SELECT $COLUMNS FROM pois WHERE category=? AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? " +
                "ORDER BY ((latitude-?)*(latitude-?)+((longitude-?)*(longitude-?))*?) LIMIT ?",
                arrayOf(category.slug, (near.latitude - dLat).toString(), (near.latitude + dLat).toString(), (near.longitude - dLon).toString(), (near.longitude + dLon).toString(),
                    near.latitude.toString(), near.latitude.toString(), near.longitude.toString(), near.longitude.toString(), (lonScale * lonScale).toString(), limit.toString()))
            if (rows.size >= minOf(limit, 12) || radiusKm == 30.0) return@withContext rows
        }
        emptyList()
    }

    /** Name search over every indexed place, nearest first when a position is known. */
    suspend fun search(query: String, near: GeoPoint?, limit: Int = 40): List<CampusPlace> = withContext(Dispatchers.IO) {
        val db = open() ?: return@withContext emptyList()
        val expression = ftsExpression(query) ?: return@withContext emptyList()
        val order = if (near == null) "" else "ORDER BY ((p.latitude-${near.latitude})*(p.latitude-${near.latitude})+((p.longitude-${near.longitude})*(p.longitude-${near.longitude}))*${cos(Math.toRadians(near.latitude)).let { it * it }})"
        read(db, "SELECT ${COLUMNS.split(',').joinToString(",") { "p.$it" }} FROM names JOIN pois p ON p.rowid=names.docid WHERE names MATCH ? $order LIMIT ?", arrayOf(expression, limit.toString()))
    }

    private fun read(db: SQLiteDatabase, sql: String, args: Array<String>): List<CampusPlace> = db.rawQuery(sql, args).use { rows ->
        buildList {
            while (rows.moveToNext()) {
                val id = rows.getString(0); val subtype = rows.getString(3); val brand = rows.getString(4); val cuisine = rows.getString(12)
                val label = PlaceSearchRanking.featureLabel(subtype)
                val area = rows.getString(7)
                val subtitle = listOfNotNull(label, cuisine.takeIf { it.isNotBlank() }?.replace(';', ',')?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }, area.takeIf { it.isNotBlank() }).joinToString(" · ")
                add(CampusPlace(id = "poi:$id", title = rows.getString(1), subtitle = subtitle, category = subtype, city = "Mumbai", point = GeoPoint(rows.getDouble(5), rows.getDouble(6)),
                    aliases = listOfNotNull(brand.takeIf { it.isNotBlank() }), reviewed = false, sourceUrl = "https://www.openstreetmap.org/${if (id.startsWith("w")) "way" else if (id.startsWith("r")) "relation" else "node"}/${id.drop(1)}",
                    phone = rows.getString(8).takeIf { it.isNotBlank() }, website = rows.getString(9).takeIf { it.isNotBlank() }, hours = rows.getString(10).takeIf { it.isNotBlank() },
                    detail = rows.getString(11).takeIf { it.isNotBlank() }?.let { "Wheelchair access: $it" }))
            }
        }
    }

    private companion object { const val COLUMNS = "id,name,category,subtype,brand,latitude,longitude,address,phone,website,hours,wheelchair,cuisine" }
}
