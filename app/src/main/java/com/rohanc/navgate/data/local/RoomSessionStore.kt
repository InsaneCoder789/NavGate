package com.rohanc.navgate.data.local

import androidx.room.withTransaction
import com.rohanc.navgate.core.*
import com.rohanc.navgate.model.RouteHistoryEntry
import com.rohanc.navgate.model.TravelProfile
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RoomSessionStore(private val database: NavGateDatabase, private val regions: RoomRegionStore) : SessionStore {
    @Volatile private var writtenRouteId: String? = null

    override suspend fun loadCheckpoint(): SessionCheckpoint? {
        val record = database.user().checkpoint() ?: return null
        val routes = mutableMapOf<String, String?>()
        // The decoder is not suspending, so fetch the referenced route first.
        val routeId = runCatching { StoredJson.parseToJsonElement(record.payload).jsonObject["routeId"]?.jsonPrimitive?.content }.getOrNull()
        if (routeId != null) routes[routeId] = database.user().record(CheckpointCodec.ROUTE_KIND, routeId)?.payload
        return CheckpointCodec.decode(record.payload) { routes[it] }
    }

    override suspend fun persist(snapshot: SessionSnapshot, updatedAtEpochMillis: Long) {
        val route = snapshot.route ?: return
        val sessionId = snapshot.sessionId ?: return
        val active = snapshot.status in NavigationSession.ACTIVE
        val terminal = snapshot.status in setOf(SessionStatus.Arrived,SessionStatus.Stopped)
        if (!active && !terminal) return
        val title = route.destinationTitle ?: regions.version(route.regionId,route.graphVersion)?.places?.find { it.id==route.destinationPlaceId }?.title ?: route.destinationPlaceId
        database.withTransaction {
            if(route.external)require(RouteIntegrity.shapeErrors(route).isEmpty()){"Cannot persist invalid provider directions"}
            else require(database.regions().version(route.regionId,route.graphVersion) != null) { "Cannot checkpoint a trip without its pinned graph" }
            val previous=database.user().trip(sessionId)?.let { StoredJson.decodeFromString<RouteHistoryEntry>(it.payload) }
            val summary = RouteHistoryEntry(route.destinationPlaceId,title,if(route.profile=="driving")TravelProfile.Driving else TravelProfile.Walking,previous?.recordedAtEpochMillis ?: updatedAtEpochMillis,
                snapshot.remainingSeconds,sessionId,snapshot.status.name,route.regionId,route.graphVersion,route.distanceMeters,
                if(terminal) previous?.finishedAtEpochMillis ?: updatedAtEpochMillis else null)
            database.user().putTrip(TripRecord(sessionId,route.regionId,route.graphVersion,Json.encodeToString(summary),updatedAtEpochMillis))
            database.user().put(UserRecord("history",sessionId,Json.encodeToString(summary),updatedAtEpochMillis))
            database.user().trim("history",100)
            database.user().trimTrips(sessionId)
            if (active) {
                if (writtenRouteId != route.id) {
                    database.user().put(UserRecord(CheckpointCodec.ROUTE_KIND,route.id,CheckpointCodec.encodeRoute(route),updatedAtEpochMillis))
                    database.user().trim(CheckpointCodec.ROUTE_KIND,1)
                }
                database.user().putCheckpoint(CheckpointRecord(payload=CheckpointCodec.encode(snapshot),updatedAt=updatedAtEpochMillis))
            } else {
                database.user().clearCheckpoint()
                database.user().clear(CheckpointCodec.ROUTE_KIND)
            }
        }
        writtenRouteId = if (active) route.id else null
    }
}
