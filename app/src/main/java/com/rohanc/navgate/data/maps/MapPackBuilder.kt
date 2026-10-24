package com.rohanc.navgate.data.maps

import com.rohanc.navgate.core.GeoPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** A rectangle of the world, in degrees. */
data class MapArea(val name: String, val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double) {
    companion object {
        /** A square of roughly 2 x [radiusKm] kilometres around a point. */
        fun around(name: String, center: GeoPoint, radiusKm: Double): MapArea {
            val dLat = radiusKm / 111.32
            val dLon = radiusKm / (111.32 * Math.cos(Math.toRadians(center.latitude)).coerceAtLeast(0.05))
            return MapArea(name, (center.longitude - dLon).coerceAtLeast(-180.0), (center.latitude - dLat).coerceAtLeast(-85.0),
                (center.longitude + dLon).coerceAtMost(180.0), (center.latitude + dLat).coerceAtMost(85.0))
        }
    }
}

/**
 * Downloads the vector tiles of one area from OpenFreeMap (the same tiles the live map uses) and packs them into a PMTiles archive,
 * so the map keeps drawing with no signal. The tile count is capped to stay kind to the free tile server and to storage.
 */
class MapPackBuilder(
    private val client: OkHttpClient = defaultClient,
    private val tileJsonUrl: String = TILEJSON_URL,
) {
    data class Progress(val done: Int, val total: Int)

    fun tilesFor(area: MapArea, maxZoom: Int = MAX_ZOOM): List<Triple<Int, Int, Int>> {
        val out = ArrayList<Triple<Int, Int, Int>>()
        for (z in 0..maxZoom) {
            val n = 1 shl z
            val x0 = lonToX(area.minLon, n); val x1 = lonToX(area.maxLon, n)
            val y0 = latToY(area.maxLat, n); val y1 = latToY(area.minLat, n)
            for (x in x0..x1) for (y in y0..y1) out += Triple(z, x, y)
        }
        return out
    }

    fun tileCount(area: MapArea, maxZoom: Int = MAX_ZOOM) = tilesFor(area, maxZoom).size

    /** Builds the archive at [target]. Throws [IOException] with a plain-language reason when it cannot finish. */
    suspend fun build(area: MapArea, target: File, scratchDir: File, onProgress: (Progress) -> Unit): Unit = withContext(Dispatchers.IO) {
        val tiles = tilesFor(area)
        if (tiles.size > MAX_TILES) throw IOException("That area is too large (${tiles.size} tiles). Choose a smaller one.")
        val tileJson = fetchText(tileJsonUrl).let { Json.parseToJsonElement(it).jsonObject }
        val template = tileJson["tiles"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content ?: throw IOException("The map server did not describe its tiles.")
        scratchDir.mkdirs(); target.parentFile?.mkdirs()
        val scratch = File(scratchDir, target.name + ".scratch")
        val done = AtomicInteger(0); val failed = AtomicInteger(0)
        Pmtiles.Writer(scratch).use { writer ->
            val gate = Semaphore(PARALLEL)
            coroutineScope {
                tiles.map { (z, x, y) ->
                    async {
                        gate.withPermit {
                            ensureActive()
                            val bytes = fetchTile(template.replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y"))
                            if (bytes == null) { if (failed.incrementAndGet() > tiles.size / 10 + 5) throw IOException("The map server stopped answering. Try again later.") }
                            else if (bytes.isNotEmpty()) writer.add(z, x, y, bytes)
                            val d = done.incrementAndGet()
                            if (d % 16 == 0 || d == tiles.size) onProgress(Progress(d, tiles.size))
                        }
                    }
                }.awaitAll()
            }
            if (writer.count == 0) throw IOException("The map server had no tiles for that area.")
            val meta = buildJsonObject {
                put("name", area.name); put("format", "pbf"); put("type", "baselayer"); put("version", tileJson["version"] ?: JsonPrimitive("1"))
                put("description", "NavGate offline map pack. Tiles by OpenFreeMap, schema OpenMapTiles.")
                tileJson["attribution"]?.let { put("attribution", it) }
                tileJson["vector_layers"]?.let { put("vector_layers", it) }
                put("minzoom", 0); put("maxzoom", MAX_ZOOM)
            }.toString()
            writer.finish(target, meta, area.minLon, area.minLat, area.maxLon, area.maxLat)
        }
    }

    private fun fetchText(url: String): String = client.newCall(request(url).build()).execute().use { r ->
        if (!r.isSuccessful) throw IOException("Map server answered ${r.code}")
        r.body?.string() ?: throw IOException("Empty answer from the map server")
    }

    private fun request(url: String) = Request.Builder().url(url).header("User-Agent", "NavGate/1.0 (https://github.com/InsaneCoder789/NavGate)")

    /** Raw, still-compressed tile bytes; empty array for "no tile here"; null when the fetch failed after retries. */
    private suspend fun fetchTile(url: String): ByteArray? {
        repeat(3) { attempt ->
            try {
                // Asking for gzip ourselves makes OkHttp hand the compressed bytes over untouched, which is what the archive stores.
                client.newCall(request(url).header("Accept-Encoding", "gzip").build()).execute().use { r ->
                    when {
                        r.code == 404 || r.code == 204 -> return ByteArray(0)
                        r.isSuccessful -> {
                            val body = r.body?.bytes() ?: return ByteArray(0)
                            return if (body.isEmpty() || Pmtiles.isGzip(body)) body else Pmtiles.gzip(body)
                        }
                        r.code == 429 || r.code >= 500 -> Unit
                        else -> return null
                    }
                }
            } catch (c: CancellationException) { throw c } catch (_: IOException) { }
            delay(400L * (attempt + 1))
        }
        return null
    }

    companion object {
        const val TILEJSON_URL = "https://tiles.openfreemap.org/planet"
        const val MAX_ZOOM = 14
        const val MAX_TILES = 6_000
        private const val PARALLEL = 6
        private val defaultClient by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build() }

        private fun lonToX(lon: Double, n: Int) = floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        private fun latToY(lat: Double, n: Int): Int {
            val rad = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
            return floor((1.0 - asinh(tan(rad)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
        }
    }
}
