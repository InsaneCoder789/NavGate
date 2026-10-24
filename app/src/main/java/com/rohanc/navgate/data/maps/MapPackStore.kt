package com.rohanc.navgate.data.maps

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** One installed offline map: a PMTiles file plus what the user should see about it. */
@Serializable
data class MapPack(val id: String, val name: String, val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double, val bytes: Long, val createdMillis: Long, val imported: Boolean = false) {
    fun contains(lat: Double, lon: Double) = lat in minLat..maxLat && lon in minLon..maxLon
}

/**
 * Offline map packs on this phone. Each pack is one PMTiles file; one pack at a time is "active". When the phone has no
 * connection (or the user chose to always use offline maps) the app draws the active pack with fonts and icons stored locally.
 */
class MapPackStore(context: Context, private val client: OkHttpClient = defaultClient, private val assetBaseUrl: String = ASSET_BASE_URL) {
    private val appContext = context.applicationContext
    val root = File(appContext.filesDir, "mappacks")
    private val styleDir = File(root, "style")
    private val prefs = appContext.getSharedPreferences("navgate_map_packs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var buildJob: Job? = null

    sealed interface Status {
        data object Idle : Status
        data class Working(val label: String, val done: Int, val total: Int) : Status
        data class Failed(val reason: String) : Status
    }

    private val mutablePacks = MutableStateFlow(readPacks())
    val packs: StateFlow<List<MapPack>> = mutablePacks
    private val mutableStatus = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = mutableStatus
    private val mutableActive = MutableStateFlow(prefs.getString(KEY_ACTIVE, null))
    val activeId: StateFlow<String?> = mutableActive
    private val mutableAlways = MutableStateFlow(prefs.getBoolean(KEY_ALWAYS, false))
    /** When true the app draws the offline map even with a connection, to save mobile data. */
    val alwaysOffline: StateFlow<Boolean> = mutableAlways
    private val mutableStyleVersion = MutableStateFlow(0)
    /** Bumps whenever the style files change, so the map reloads. */
    val styleVersion: StateFlow<Int> = mutableStyleVersion

    fun file(pack: MapPack) = File(root, "${pack.id}.pmtiles")
    private fun metaFile(id: String) = File(root, "$id.json")

    private fun readPacks(): List<MapPack> = root.listFiles { f -> f.name.endsWith(".json") }?.mapNotNull { meta ->
        runCatching { Json.decodeFromString<MapPack>(meta.readText()) }.getOrNull()?.takeIf { File(root, "${it.id}.pmtiles").isFile }
    }?.sortedByDescending { it.createdMillis }.orEmpty()

    private fun refresh() { mutablePacks.value = readPacks(); if (mutableActive.value != null && mutablePacks.value.none { it.id == mutableActive.value }) setActive(mutablePacks.value.firstOrNull()?.id) }

    fun active(): MapPack? = mutablePacks.value.firstOrNull { it.id == mutableActive.value }

    fun setActive(id: String?) { prefs.edit().putString(KEY_ACTIVE, id).apply(); mutableActive.value = id; mutableStyleVersion.update { it + 1 } }
    fun setAlwaysOffline(value: Boolean) { prefs.edit().putBoolean(KEY_ALWAYS, value).apply(); mutableAlways.value = value }

    fun delete(id: String) {
        File(root, "$id.pmtiles").delete(); metaFile(id).delete()
        refresh()
    }

    private var cachedIndex: Pair<String, StreetNameIndex>? = null

    /** A road name for each point, from the active pack's road-name layer; empty when no pack covers the whole line. */
    suspend fun streetNamesFor(points: List<com.rohanc.navgate.core.GeoPoint>): List<String?> = withContext(Dispatchers.IO) {
        val pack = active() ?: return@withContext emptyList()
        if (points.size < 2 || !pack.contains(points.first().latitude, points.first().longitude) || !pack.contains(points.last().latitude, points.last().longitude)) return@withContext emptyList()
        val index = synchronized(this@MapPackStore) {
            cachedIndex?.takeIf { it.first == pack.id }?.second ?: StreetNameIndex(Pmtiles.Reader(file(pack))).also { cachedIndex = pack.id to it }
        }
        index.namesAlong(points)
    }

    fun totalBytes() = mutablePacks.value.sumOf { it.bytes }

    /** Downloads tiles for [area] and installs them as a new pack, which becomes the active one. */
    fun download(area: MapArea, builder: MapPackBuilder = MapPackBuilder()) {
        if (buildJob?.isActive == true) return
        buildJob = scope.launch {
            val id = UUID.randomUUID().toString().take(12)
            val target = File(root, "$id.pmtiles")
            try {
                mutableStatus.value = Status.Working("Map tiles", 0, 1)
                builder.build(area, target, File(root, "tmp")) { p -> mutableStatus.value = Status.Working("Map tiles", p.done, p.total) }
                mutableStatus.value = Status.Working("Fonts and icons", 0, 1)
                ensureStyleAssets { done, total -> mutableStatus.value = Status.Working("Fonts and icons", done, total) }
                install(MapPack(id, area.name, area.minLon, area.minLat, area.maxLon, area.maxLat, target.length(), System.currentTimeMillis()))
                mutableStatus.value = Status.Idle
            } catch (c: CancellationException) { target.delete(); File(root, "tmp").listFiles()?.forEach { it.delete() }; mutableStatus.value = Status.Idle; throw c }
            catch (e: Exception) { target.delete(); mutableStatus.value = Status.Failed(e.message ?: "Download failed") }
        }
    }

    fun cancel() { buildJob?.cancel() }
    fun dismissFailure() { if (mutableStatus.value is Status.Failed) mutableStatus.value = Status.Idle }

    private fun install(pack: MapPack) {
        metaFile(pack.id).writeText(Json.encodeToString(pack))
        refresh(); setActive(pack.id)
    }

    /** Copies a PMTiles file the user picked, after checking it is a version 3 vector archive. */
    fun importFrom(uri: Uri, displayName: String?) {
        if (buildJob?.isActive == true) return
        buildJob = scope.launch {
            val id = UUID.randomUUID().toString().take(12); val target = File(root, "$id.pmtiles")
            try {
                root.mkdirs(); mutableStatus.value = Status.Working("Copying map file", 0, 1)
                appContext.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: throw IOException("The file could not be opened.")
                val header = Pmtiles.Reader(target).header
                if (header.tileType != Pmtiles.TYPE_MVT) throw IOException("This map file does not hold vector tiles.")
                if (header.tileCompression != Pmtiles.COMPRESSION_GZIP && header.tileCompression != Pmtiles.COMPRESSION_NONE) throw IOException("This map file uses a tile compression NavGate cannot read.")
                mutableStatus.value = Status.Working("Fonts and icons", 0, 1)
                ensureStyleAssets { done, total -> mutableStatus.value = Status.Working("Fonts and icons", done, total) }
                val name = displayName?.removeSuffix(".pmtiles")?.takeIf { it.isNotBlank() } ?: "Imported map"
                install(MapPack(id, name, header.minLon, header.minLat, header.maxLon, header.maxLat, target.length(), System.currentTimeMillis(), imported = true))
                mutableStatus.value = Status.Idle
            } catch (c: CancellationException) { target.delete(); mutableStatus.value = Status.Idle; throw c }
            catch (e: Exception) { target.delete(); mutableStatus.value = Status.Failed(if (e is IOException) e.message ?: "Import failed" else "That file is not a PMTiles map.") }
        }
    }

    // ---- Fonts, icons and the style that points at a pack ----

    private val fontStacks = listOf("Noto Sans Regular", "Noto Sans Bold", "Noto Sans Italic")
    /** Glyph ranges for Latin, punctuation and the main Indian scripts (Devanagari and Bengali through Malayalam). */
    private val glyphRanges = listOf("0-255", "256-511", "512-767", "7680-7935", "8192-8447", "2304-2559", "2560-2815", "2816-3071", "3072-3327", "3328-3583")

    fun styleAssetsReady() = File(styleDir, "sprites/ofm.json").isFile && fontStacks.all { File(styleDir, "fonts/$it/0-255.pbf").isFile }

    private suspend fun ensureStyleAssets(onProgress: (Int, Int) -> Unit) = withContext(Dispatchers.IO) {
        val jobs = ArrayList<Pair<String, File>>()
        for (n in listOf("ofm.json", "ofm.png", "ofm@2x.json", "ofm@2x.png")) jobs += "sprites/ofm_f384/$n" to File(styleDir, "sprites/$n")
        for (f in fontStacks) for (r in glyphRanges) jobs += "fonts/${Uri.encode(f)}/$r.pbf" to File(styleDir, "fonts/$f/$r.pbf")
        var done = 0
        for ((path, out) in jobs) {
            if (!out.isFile || out.length() == 0L) {
                out.parentFile?.mkdirs()
                client.newCall(Request.Builder().url(assetBaseUrl + path).header("User-Agent", "NavGate/1.0 (https://github.com/InsaneCoder789/NavGate)").build()).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("Map fonts could not be downloaded (${r.code}).")
                    val part = File(out.parentFile, out.name + ".part")
                    part.outputStream().use { o -> (r.body ?: throw IOException("Empty answer")).byteStream().copyTo(o) }
                    part.renameTo(out)
                }
            }
            onProgress(++done, jobs.size)
        }
    }

    /**
     * A copy of one of the app's map styles whose vector source reads the active pack and whose fonts and icons are local files.
     * Returns the file:// URL of the written style, or null when there is no usable pack or the assets are missing.
     */
    fun offlineStyleUrl(assetName: String): String? {
        val pack = active() ?: return null
        if (!styleAssetsReady()) return null
        val source = runCatching { appContext.assets.open(assetName).use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull() ?: return null
        val styled = OfflineStyle.rewrite(source, "pmtiles://file://${file(pack).absolutePath}", "file://${styleDir.absolutePath}/fonts/{fontstack}/{range}.pbf", "file://${styleDir.absolutePath}/sprites/ofm") ?: return null
        val out = File(styleDir, "offline-${assetName.removeSuffix(".json")}-${pack.id}.json")
        if (!out.isFile || out.readText() != styled) { styleDir.mkdirs(); out.writeText(styled) }
        return "file://${out.absolutePath}"
    }

    companion object {
        const val ASSET_BASE_URL = "https://tiles.openfreemap.org/"
        private const val KEY_ACTIVE = "active"
        private const val KEY_ALWAYS = "always_offline"
        private val defaultClient by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build() }
    }
}

/** Rewrites a map style so it reads from local files. Kept separate and free of Android so it can be unit tested. */
object OfflineStyle {
    /** Returns the rewritten style JSON, or null if [style] is not a style this code understands. */
    fun rewrite(style: String, vectorSourceUrl: String, glyphs: String, sprite: String): String? {
        val root = runCatching { Json.parseToJsonElement(style).jsonObject }.getOrNull() ?: return null
        val sources = root["sources"]?.jsonObject ?: return null
        val vectorName = sources.entries.firstOrNull { it.value.jsonObject["type"]?.jsonPrimitive?.content == "vector" }?.key ?: return null
        // Raster sources (the faint low-zoom shading) need the network, so they and the layers that use them are dropped.
        val rasterNames = sources.entries.filter { it.value.jsonObject["type"]?.jsonPrimitive?.content == "raster" }.map { it.key }.toSet()
        val newSources = buildJsonObject {
            for ((k, v) in sources) if (k !in rasterNames) put(k, if (k == vectorName) buildJsonObject { put("type", "vector"); put("url", vectorSourceUrl) } else v)
        }
        val layers = root["layers"]?.jsonArray?.filter { it.jsonObject["source"]?.jsonPrimitive?.contentOrNull !in rasterNames }.orEmpty()
        return buildJsonObject {
            for ((k, v) in root) when (k) {
                "sources" -> put(k, newSources); "layers" -> put(k, JsonArray(layers)); "glyphs" -> put(k, glyphs); "sprite" -> put(k, sprite); else -> put(k, v)
            }
            if ("glyphs" !in root) put("glyphs", glyphs)
        }.toString()
    }
}
