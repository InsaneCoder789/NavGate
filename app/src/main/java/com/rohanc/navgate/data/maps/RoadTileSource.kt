package com.rohanc.navgate.data.maps

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Vector tile bytes for road analysis: the saved map pack when it has the tile, the tile server otherwise. Recent tiles are kept in memory. */
class RoadTileSource(
    private val pack: suspend (z: Int, x: Int, y: Int) -> ByteArray?,
    /** Where fetched tiles are kept between runs (about 20 MB, oldest dropped first); null keeps them in memory only. */
    private val cacheDir: java.io.File? = null,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build(),
    private val tileJsonUrl: String = MapPackBuilder.TILEJSON_URL,
    private val fetch: (suspend (z: Int, x: Int, y: Int) -> ByteArray?)? = null,
) {
    private val recent = object : LinkedHashMap<Triple<Int, Int, Int>, ByteArray>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<Int, Int, Int>, ByteArray>?) = size > 48
    }
    @Volatile private var template: String? = null

    suspend fun tile(z: Int, x: Int, y: Int): ByteArray? {
        val key = Triple(z, x, y)
        synchronized(recent) { recent[key] }?.let { return it }
        val bytes = pack(z, x, y) ?: fromDisk(z, x, y) ?: (if (fetch != null) fetch.invoke(z, x, y) else online(z, x, y))?.also { toDisk(z, x, y, it) }
        if (bytes != null) synchronized(recent) { recent[key] = bytes }
        return bytes
    }

    private fun file(z: Int, x: Int, y: Int) = cacheDir?.let { java.io.File(it, "${z}_${x}_$y.pbf") }
    private suspend fun fromDisk(z: Int, x: Int, y: Int): ByteArray? = withContext(Dispatchers.IO) { file(z, x, y)?.takeIf { it.isFile }?.let { f -> runCatching { f.setLastModified(System.currentTimeMillis()); f.readBytes() }.getOrNull() } }
    private suspend fun toDisk(z: Int, x: Int, y: Int, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val dir = cacheDir ?: return@withContext
        runCatching {
            dir.mkdirs(); file(z, x, y)!!.writeBytes(bytes)
            var total = 0L; val all = dir.listFiles().orEmpty().sortedBy { it.lastModified() }
            all.forEach { total += it.length() }
            for (f in all) { if (total <= MAX_DISK_BYTES) break; total -= f.length(); f.delete() }
        }
    }

    private suspend fun online(z: Int, x: Int, y: Int): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val url = (template ?: client.newCall(Request.Builder().url(tileJsonUrl).build()).execute().use { r ->
                Regex("\"tiles\"\\s*:\\s*\\[\\s*\"([^\"]+)\"").find(r.body?.string().orEmpty())?.groupValues?.get(1)
            }?.also { template = it }) ?: return@withContext null
            client.newCall(Request.Builder().url(url.replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y")).build()).execute().use { r ->
                if (r.isSuccessful) r.body?.bytes() else null
            }
        } catch (_: java.io.IOException) { null }
    }

    private companion object { const val MAX_DISK_BYTES = 20L * 1024 * 1024 }
}
