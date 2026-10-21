package com.rohanc.navgate.data.local

import androidx.room.withTransaction
import com.rohanc.navgate.core.CampusRoute
import com.rohanc.navgate.data.routing.RoadRouteCache
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Bounded local-only route cache. TTL/validation is enforced by the routing provider on every read. */
class RoomRoadRouteCache(private val database:NavGateDatabase):RoadRouteCache {
    override suspend fun read(key:String):CampusRoute? = database.user().record(KIND,key)?.let{runCatching{StoredJson.decodeFromString<CampusRoute>(it.payload)}.getOrNull()}
    override suspend fun write(key:String,route:CampusRoute){database.withTransaction {
        database.user().put(UserRecord(KIND,key,StoredJson.encodeToString(route),route.fetchedAtEpochMillis ?: System.currentTimeMillis()))
        database.user().trim(KIND,12)
    }}
    private companion object {const val KIND="road-route-cache-v1"}
}
