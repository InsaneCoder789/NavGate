package com.rohanc.navgate.data.routing.offline

import android.content.Context
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
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/** Where the offline router keeps its files, and how routing tiles are downloaded, checked and removed. */
class RoutingDataStore(context: Context, private val baseUrl: String = DEFAULT_BASE_URL, private val client: OkHttpClient = defaultClient) {
    private val appContext = context.applicationContext
    val root = File(appContext.filesDir, "brouter")
    val segments = File(root, "segments4")
    val profiles = File(root, "profiles2")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()

    data class Tile(val name: String, val bytes: Long)
    sealed interface Progress {
        data class Downloading(val done: Long, val total: Long) : Progress
        data class Failed(val reason: String) : Progress
    }

    private val mutableProgress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val progress: StateFlow<Map<String, Progress>> = mutableProgress
    private val mutableInstalled = MutableStateFlow(readInstalled())
    val installed: StateFlow<List<Tile>> = mutableInstalled

    private fun file(name: String) = File(segments, "$name.rd5")
    private fun readInstalled(): List<Tile> = segments.listFiles { f -> f.name.endsWith(".rd5") && RoutingTiles.isValid(f.name.removeSuffix(".rd5")) && f.length() >= MIN_BYTES }
        ?.map { Tile(it.name.removeSuffix(".rd5"), it.length()) }?.sortedBy { it.name }.orEmpty()
    private fun refresh() { mutableInstalled.value = readInstalled() }

    fun isInstalled(name: String) = RoutingTiles.isValid(name) && file(name).let { it.isFile && it.length() >= MIN_BYTES }
    fun missing(names: List<String>) = names.filterNot(::isInstalled)

    /** Copies the routing profiles that ship inside the app, once per app version. */
    fun prepareProfiles() {
        profiles.mkdirs()
        val stamp = File(profiles, ".version")
        val version = runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).let { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it).toString() } }.getOrDefault("0")
        if (stamp.exists() && stamp.readText() == version && File(profiles, "lookups.dat").exists()) return
        val names = appContext.assets.list("brouter/profiles2").orEmpty()
        for (n in names) appContext.assets.open("brouter/profiles2/$n").use { input -> File(profiles, n).outputStream().use { input.copyTo(it) } }
        stamp.writeText(version)
    }

    fun delete(name: String) {
        if (!RoutingTiles.isValid(name)) return
        cancel(name)
        file(name).delete(); File(segments, "$name.rd5.part").delete()
        refresh()
    }

    fun cancel(name: String) { synchronized(jobs) { jobs.remove(name)?.cancel() }; mutableProgress.update { it - name } }

    /** Starts (or resumes) a download. Safe to call again for a tile already downloading. */
    fun download(name: String) {
        if (!RoutingTiles.isValid(name) || isInstalled(name)) return
        synchronized(jobs) {
            if (jobs[name]?.isActive == true) return
            jobs[name] = scope.launch {
                try {
                    mutableProgress.update { it + (name to Progress.Downloading(0, 0)) }
                    fetch(name)
                    mutableProgress.update { it - name }
                    refresh()
                } catch (c: CancellationException) { throw c }
                catch (e: Exception) { mutableProgress.update { it + (name to Progress.Failed(e.message ?: "Download failed")) } }
            }
        }
    }

    private suspend fun fetch(name: String) = withContext(Dispatchers.IO) {
        segments.mkdirs()
        val part = File(segments, "$name.rd5.part")
        val have = if (part.exists()) part.length() else 0L
        val request = Request.Builder().url("$baseUrl$name.rd5").header("User-Agent", "NavGate/1.0 (https://github.com/InsaneCoder789/NavGate)")
            .apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        client.newCall(request).execute().use { response ->
            if (response.code == 416) { part.delete(); throw IOException("Download restarted; try again") }
            if (!response.isSuccessful) throw IOException("Server answered ${response.code}")
            val resumed = response.code == 206
            val body = response.body ?: throw IOException("Empty response")
            val total = (if (resumed) have else 0L) + body.contentLength().coerceAtLeast(0)
            RandomAccessFile(part, "rw").use { out ->
                if (resumed) out.seek(have) else out.setLength(0)
                var done = if (resumed) have else 0L
                val buffer = ByteArray(64 * 1024); var lastReport = 0L
                body.byteStream().use { input ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer); if (n < 0) break
                        out.write(buffer, 0, n); done += n
                        val now = System.nanoTime()
                        if (now - lastReport > 150_000_000L) { lastReport = now; mutableProgress.update { it + (name to Progress.Downloading(done, total)) } }
                    }
                }
                if (total > 0 && done != total) throw IOException("Download was cut short; it will resume")
            }
        }
        if (part.length() < MIN_BYTES) { part.delete(); throw IOException("The file is too small to be routing data") }
        // BRouter checks its own file index; a damaged tile is removed rather than kept.
        val problem = runCatching { btools.mapaccess.PhysicalFile.checkFileIntegrity(part) }.getOrElse { "unreadable" }
        if (problem != null) { part.delete(); throw IOException("The downloaded file is damaged. Try again.") }
        val target = file(name)
        if (!part.renameTo(target)) { part.copyTo(target, overwrite = true); part.delete() }
    }

    /** Download size of one tile in bytes, or null when the server cannot say. */
    suspend fun remoteSize(name: String): Long? = withContext(Dispatchers.IO) {
        if (!RoutingTiles.isValid(name)) return@withContext null
        runCatching { client.newCall(Request.Builder().url("$baseUrl$name.rd5").head().build()).execute().use { r -> if (r.isSuccessful) r.header("Content-Length")?.toLongOrNull() else null } }.getOrNull()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://brouter.de/brouter/segments4/"
        const val MIN_BYTES = 100_000L
        private val defaultClient by lazy { OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build() }
    }
}
