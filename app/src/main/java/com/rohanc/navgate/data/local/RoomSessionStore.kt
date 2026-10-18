package com.rohanc.navgate.data.local

import androidx.room.withTransaction
import com.rohanc.navgate.core.*
import com.rohanc.navgate.model.RouteHistoryEntry
import com.rohanc.navgate.model.TravelProfile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RoomSessionStore(private val database: NavGateDatabase, private val regions: RoomRegionStore) : SessionStore {
    override suspend fun loadCheckpoint(): SessionCheckpoint? = database.user().checkpoint()?.let { Json.decodeFromString<SessionCheckpoint>(it.payload) }

    override suspend fun persist(snapshot: SessionSnapshot, updatedAtEpochMillis: Long) {
        val route = snapshot.route ?: return
        val sessionId = snapshot.sessionId ?: return
        val active = snapshot.status in NavigationSession.ACTIVE
        val terminal = snapshot.status in setOf(SessionStatus.Arrived,SessionStatus.Stopped)
        if (!active && !terminal) return
        val title = regions.version(route.regionId,route.graphVersion)?.places?.find { it.id==route.destinationPlaceId }?.title ?: route.destinationPlaceId
        database.withTransaction {
            require(database.regions().version(route.regionId,route.graphVersion) != null) { "Cannot checkpoint a trip without its pinned graph" }
            val previous=database.user().trip(sessionId)?.let { Json.decodeFromString<RouteHistoryEntry>(it.payload) }
            val summary = RouteHistoryEntry(route.destinationPlaceId,title,TravelProfile.Walking,previous?.recordedAtEpochMillis ?: updatedAtEpochMillis,
                snapshot.remainingSeconds,sessionId,snapshot.status.name,route.regionId,route.graphVersion,route.distanceMeters,
                if(terminal) previous?.finishedAtEpochMillis ?: updatedAtEpochMillis else null)
            database.user().putTrip(TripRecord(sessionId,route.regionId,route.graphVersion,Json.encodeToString(summary),updatedAtEpochMillis))
            database.user().put(UserRecord("history",sessionId,Json.encodeToString(summary),updatedAtEpochMillis))
            database.user().trim("history",100)
            database.user().trimTrips(sessionId)
            if (active) database.user().putCheckpoint(CheckpointRecord(payload=Json.encodeToString(SessionCheckpoint(snapshot)),updatedAt=updatedAtEpochMillis))
            else database.user().clearCheckpoint()
        }
    }
}
