package com.rohanc.navgate.data.search

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.rohanc.navgate.core.CampusPlace
import com.rohanc.navgate.core.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal fun declaredDigest(manifest: String?): String? = runCatching {
    kotlinx.serialization.json.Json.parseToJsonElement(requireNotNull(manifest)).let { it as kotlinx.serialization.json.JsonObject }["index_sha256"]
        ?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
}.getOrNull()?.takeIf { Regex("[0-9a-f]{64}").matches(it) }

/** Separate read-only gazetteer: never inserts unreviewed features into a routing graph. */
class IndiaPlaceIndex(private val context: Context, private val location: () -> SearchLocation? = { null }) : WorldwidePlaceSearch {
    private val lock = Any()
    private var database: SQLiteDatabase? = null

    private fun open(): SQLiteDatabase = synchronized(lock) {
        database ?: run {
            val file = File(context.noBackupFilesDir, "india-search-v1.sqlite")
            // Compare shipped bytes so installing a newer APK cannot silently retain an old index.
            val expected = declaredDigest(runCatching { context.assets.open("search/india.json").use { it.readBytes().decodeToString() } }.getOrNull())
                ?: hashAsset()
            val stamp = File(context.noBackupFilesDir,"india-search-v1.sha256")
            if (!file.exists() || !stamp.exists() || stamp.readText() != expected) {
                val temporary = File(context.noBackupFilesDir,"india-search-v1.tmp")
                try {
                    context.assets.open("search/india.sqlite").use { source -> temporary.outputStream().use { target -> source.copyTo(target); target.fd.sync() } }
                    // Android FTS4 integrity validation needs a writable staging connection.
                    // The activated gazetteer below is still opened read-only.
                    SQLiteDatabase.openDatabase(temporary.path,null,SQLiteDatabase.OPEN_READWRITE).use { candidate ->
                        require(candidate.version == 1)
                        candidate.rawQuery("PRAGMA integrity_check",null).use { check ->
                            val result = if(check.moveToFirst()) check.getString(0) else "no result"
                            require(result=="ok") { "India search index integrity: $result" }
                        }
                    }
                    check(temporary.renameTo(file)) { "Could not activate India search index" }
                    stamp.writeText(expected)
                } finally { temporary.delete() }
            }
            SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY).also { database=it }
        }
    }

    /** Hashing the 148 MB asset on every launch is slow; the build script already records its digest. */
    private fun hashAsset(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        context.assets.open("search/india.sqlite").use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if(n < 0) break; digest.update(buffer,0,n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Copies and validates the index ahead of the first query so typing is not blocked on a one-off install. */
    suspend fun warmUp() = withContext(Dispatchers.IO) { open(); Unit }

    override suspend fun search(query: String): List<CampusPlace> = withContext(Dispatchers.IO) {
        val tokens = Regex("[\\p{L}\\p{M}\\p{N}]+").findAll(PlaceSearchRanking.normalize(query.take(256)))
            .take(12).map { "\"${it.value}*\"" }.toList()
        if (tokens.isEmpty()) return@withContext emptyList()
        val expression = tokens.joinToString(" AND ")
        val bias = location()?.usable()
        val columns = "p.id,p.name,p.latitude,p.longitude,p.feature,p.aliases"
        fun read(sql: String, args: Array<String>): List<CampusPlace> = open().rawQuery(sql,args).use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    val id = rows.getLong(0)
                    val feature = rows.getString(4)
                    add(CampusPlace(id="geonames:$id",title=rows.getString(1),
                        subtitle="India · $feature · GeoNames (CC BY 4.0)",
                        category=feature,city=rows.getString(1),
                        aliases=rows.getString(5).split(',').filter { it.isNotBlank() },
                        point=GeoPoint(rows.getDouble(2),rows.getDouble(3)),reviewed=false,
                        sourceUrl="https://www.geonames.org/$id/"))
                }
            }
        }
        val national = read("SELECT $columns FROM names JOIN places p ON p.id=names.docid WHERE names MATCH ? " +
            "ORDER BY CASE WHEN lower(p.name)=lower(?) THEN 0 ELSE 1 END,p.population DESC,p.id LIMIT 120",
            arrayOf(expression,query.trim()))
        // RTree restricts the near candidate set before filtering FTS/category; no gazetteer scan.
        val nearby = if (bias == null) emptyList() else {
            val category = PlaceSearchRanking.requestedCategory(query)
            val codes = category?.let(PlaceSearchRanking::featureCodes).orEmpty()
            val condition = if (codes.isEmpty()) "p.id IN (SELECT docid FROM names WHERE names MATCH ?)"
                else "p.feature IN (${codes.joinToString(",") { "?" }})"
            val args = mutableListOf((bias.latitude-1).toString(),(bias.latitude+1).toString(),
                (bias.longitude-1).toString(),(bias.longitude+1).toString())
            if (codes.isEmpty()) args += expression else args += codes
            // A degree of longitude shrinks with latitude, so weight it by cos^2(latitude) for a fair distance order.
            val lonWeight = Math.cos(Math.toRadians(bias.latitude)).let { it * it }
            args += listOf(bias.latitude.toString(),bias.latitude.toString(),bias.longitude.toString(),bias.longitude.toString(),lonWeight.toString())
            read("SELECT $columns FROM coordinates c JOIN places p ON p.id=c.id " +
                "WHERE c.max_lat >= ? AND c.min_lat <= ? AND c.max_lon >= ? AND c.min_lon <= ? AND $condition " +
                "ORDER BY ((p.latitude-?)*(p.latitude-?) + (p.longitude-?)*(p.longitude-?)*?),p.id LIMIT 120", args.toTypedArray())
        }
        PlaceSearchRanking.merge(query,national+nearby,bias)
    }
}
