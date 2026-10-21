package com.rohanc.navgate.data.local

import androidx.room.withTransaction
import com.rohanc.navgate.core.ManualRoute
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Uses the existing generic user-record table; no destructive schema migration or graph mutation. */
class ManualRouteStore(private val database: NavGateDatabase) {
    data class Snapshot(val routes: List<ManualRoute>, val unreadableIds: List<String>)
    suspend fun load(): Snapshot {
        val routes=mutableListOf<ManualRoute>();val invalid=mutableListOf<String>()
        for(record in database.user().records(KIND,100)) {
            val route=runCatching{Json.decodeFromString<ManualRoute>(record.payload).also{require(it.id==record.id)}}.getOrNull()
            if(route==null)invalid+=record.id else routes+=route
        }
        return Snapshot(routes,invalid)
    }
    suspend fun list(): List<ManualRoute> = load().routes
    suspend fun save(route: ManualRoute) {
        // Revalidate a defensive snapshot before persistence (callers can supply mutable lists).
        val snapshot=route.copy(pins=route.pins.toList())
        database.withTransaction {
            val dao=database.user()
            check(dao.record(KIND,snapshot.id)!=null || dao.count(KIND)<100) { "Remove a manual route before saving more than 100" }
            dao.put(UserRecord(KIND,snapshot.id,Json.encodeToString(snapshot),System.currentTimeMillis()))
        }
    }
    suspend fun delete(id: String) { database.user().remove(KIND,id) }
    private companion object { const val KIND="manual-route-v1" }
}
