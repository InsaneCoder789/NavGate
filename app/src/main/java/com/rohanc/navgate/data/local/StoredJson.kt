package com.rohanc.navgate.data.local

import com.rohanc.navgate.core.CampusRoute
import com.rohanc.navgate.core.SessionCheckpoint
import com.rohanc.navgate.core.SessionSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Tolerant codec for on-device payloads, so an app upgrade that adds or drops a field does not discard a trip or cache. */
internal val StoredJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

/**
 * Splits a checkpoint into a small progress record and the route, which is written once per route
 * instead of on every 2 s checkpoint (a long drive can carry up to 200k geometry points).
 */
internal object CheckpointCodec {
    const val ROUTE_KIND = "checkpoint-route-v1"
    private const val FORMAT = 2

    @Serializable
    private data class Stored(val format: Int, val routeId: String, val snapshot: SessionSnapshot)

    fun encode(snapshot: SessionSnapshot): String {
        val route = requireNotNull(snapshot.route) { "Checkpoint needs a route" }
        return StoredJson.encodeToString(Stored(FORMAT, route.id, snapshot.copy(route = null)))
    }

    fun encodeRoute(route: CampusRoute): String = StoredJson.encodeToString(route)

    /** Reads this format, or the earlier single-record format that embedded the route. */
    fun decode(payload: String, route: (String) -> String?): SessionCheckpoint {
        val stored = runCatching { StoredJson.decodeFromString<Stored>(payload) }.getOrNull()?.takeIf { it.format == FORMAT }
            ?: return StoredJson.decodeFromString<SessionCheckpoint>(payload)
        val routeJson = requireNotNull(route(stored.routeId)) { "Checkpoint route is missing" }
        return SessionCheckpoint(stored.snapshot.copy(route = StoredJson.decodeFromString<CampusRoute>(routeJson)))
    }
}
