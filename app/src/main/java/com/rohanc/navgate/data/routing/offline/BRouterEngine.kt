package com.rohanc.navgate.data.routing.offline

import btools.router.OsmNodeNamed
import btools.router.RoutingContext
import btools.router.RoutingEngine
import com.rohanc.navgate.core.GeoPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class OfflineRouteException(message: String) : Exception(message)

/** The result of one offline search: the road line and BRouter's travel time. Street names are not in the offline data. */
class OfflineTrack(val points: List<GeoPoint>, val seconds: Double)

/** Thin, thread-safe wrapper around the vendored BRouter engine. One search runs at a time. */
class BRouterEngine(private val segmentDir: File, private val profileDir: File) {
    private val lock = Mutex()

    suspend fun route(from: GeoPoint, to: GeoPoint, profile: String, avoidTolls: Boolean = false, avoidHighways: Boolean = false, timeoutMillis: Long = 45_000): OfflineTrack =
        lock.withLock { withContext(Dispatchers.Default) { search(from, to, profile, avoidTolls, avoidHighways, timeoutMillis) } }

    private suspend fun search(from: GeoPoint, to: GeoPoint, profile: String, avoidTolls: Boolean, avoidHighways: Boolean, timeoutMillis: Long): OfflineTrack {
        val profileFile = File(profileDir, if (profile == "driving") "car-vario.brf" else "hiking-mountain.brf")
        if (!profileFile.isFile || !File(profileDir, "lookups.dat").isFile) throw OfflineRouteException("Offline routing is not set up yet.")
        val rc = RoutingContext()
        rc.localFunction = profileFile.absolutePath
        if (profile == "driving" && (avoidTolls || avoidHighways)) {
            rc.keyValues = HashMap<String, String>().apply { if (avoidTolls) put("avoid_toll", "1"); if (avoidHighways) put("avoid_motorways", "1") }
        }
        fun node(name: String, p: GeoPoint) = OsmNodeNamed().also {
            it.name = name; it.ilon = ((p.longitude + 180.0) * 1_000_000.0 + 0.5).toInt(); it.ilat = ((p.latitude + 90.0) * 1_000_000.0 + 0.5).toInt()
        }
        val engine = RoutingEngine(null, null, segmentDir, listOf(node("from", from), node("to", to)), rc, RoutingEngine.BROUTER_ENGINEMODE_ROUTING)
        engine.quite = true
        // A cancelled trip plan must stop the search, which otherwise keeps a core busy for up to the timeout.
        val job = currentCoroutineContext()[Job]
        val handle = job?.invokeOnCompletion { cause -> if (cause is CancellationException) engine.terminate() }
        try { engine.doRun(timeoutMillis) } finally { handle?.dispose() }
        currentCoroutineContext()[Job]?.let { if (!it.isActive) throw CancellationException("Offline search cancelled") }

        engine.errorMessage?.let { throw OfflineRouteException(friendly(it)) }
        val track = engine.foundTrack ?: throw OfflineRouteException("No offline route was found.")
        val points = ArrayList<GeoPoint>(track.nodes.size)
        val times = ArrayList<Double>(track.nodes.size)
        for (n in track.nodes) {
            val p = GeoPoint(n.iLat / 1_000_000.0 - 90.0, n.iLon / 1_000_000.0 - 180.0)
            if (points.lastOrNull()?.let { it.latitude == p.latitude && it.longitude == p.longitude } == true) continue
            points += p; times += n.time.toDouble()
        }
        if (points.size < 2) throw OfflineRouteException("No offline route was found.")
        val seconds = (times.last() - times.first()).takeIf { it > 0 } ?: track.totalSeconds.toDouble()
        return OfflineTrack(points, seconds)
    }

    private fun friendly(raw: String): String = when {
        raw.contains("datafile", true) && raw.contains("not found", true) -> "Offline routing data is missing for part of this trip."
        raw.contains("not found", true) -> "No road was found near one of the points."
        raw.contains("timeout", true) || raw.contains("exceeded", true) -> "The offline search took too long. Try a shorter trip."
        else -> raw.take(120)
    }
}
