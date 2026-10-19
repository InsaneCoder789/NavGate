package com.rohanc.navgate.data.local

import android.content.Context
import androidx.room.withTransaction
import com.rohanc.navgate.data.SharedPrefsUserPlacesStore
import com.rohanc.navgate.data.UserPlacesStore
import com.rohanc.navgate.model.PlaceSearchResult
import com.rohanc.navgate.model.RouteHistoryEntry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RoomUserPlacesStore(context: Context, private val database: NavGateDatabase) : UserPlacesStore {
    private val legacy = SharedPrefsUserPlacesStore(context.applicationContext)
    private val dao = database.user()
    private val migration = Mutex()
    private var ready = false

    /** Migration is idempotent and non-destructive. A parse/IO failure leaves the source and marker untouched. */
    suspend fun initialize() = migration.withLock {
        if (ready) return@withLock
        if (!dao.migrated(MIGRATION)) {
            val saved = legacy.savedPlaces()
            val recents = legacy.recentPlaces()
            val history = legacy.routeHistory()
            database.withTransaction {
                if (!dao.migrated(MIGRATION)) {
                    saved.forEachIndexed { index, place -> if (dao.record(SAVED,place.id) == null) dao.put(UserRecord(SAVED,place.id,Json.encodeToString(place),-index.toLong())) }
                    recents.forEachIndexed { index, place -> if (dao.record(RECENTS,place.id) == null) dao.put(UserRecord(RECENTS,place.id,Json.encodeToString(place),-index.toLong())) }
                    history.forEach { entry ->
                        val id = entry.tripId.ifBlank { "legacy-${entry.destinationId}-${entry.recordedAtEpochMillis}" }
                        if (dao.record(HISTORY,id) == null) dao.put(UserRecord(HISTORY,id,Json.encodeToString(entry.copy(tripId=id)),entry.recordedAtEpochMillis))
                    }
                    dao.mark(MigrationMarker(MIGRATION))
                }
            }
        }
        ready = true
    }

    override suspend fun savedPlaces(): List<PlaceSearchResult> { initialize(); return places(SAVED,40) }
    override suspend fun recentPlaces(): List<PlaceSearchResult> { initialize(); return places(RECENTS,30) }
    override suspend fun routeHistory(): List<RouteHistoryEntry> { initialize(); return dao.records(HISTORY,100).map { Json.decodeFromString(it.payload) } }

    override suspend fun toggleSaved(place: PlaceSearchResult): List<PlaceSearchResult> {
        initialize()
        database.withTransaction {
            if (dao.record(SAVED,place.id) == null) {
                check(dao.count(SAVED)<40) { "Saved-place limit reached; remove a bookmark first" }
                dao.put(UserRecord(SAVED,place.id,Json.encodeToString(place),System.currentTimeMillis()))
            }
            else dao.remove(SAVED,place.id)
        }
        return places(SAVED,40)
    }

    override suspend fun recordRecent(place: PlaceSearchResult): List<PlaceSearchResult> {
        initialize()
        database.withTransaction {
            dao.put(UserRecord(RECENTS,place.id,Json.encodeToString(place),System.currentTimeMillis()))
            dao.trim(RECENTS,30)
        }
        return places(RECENTS,30)
    }

    override suspend fun recordRouteHistory(entry: RouteHistoryEntry): List<RouteHistoryEntry> {
        initialize()
        val id = entry.tripId.ifBlank { "${entry.destinationId}-${entry.recordedAtEpochMillis}" }
        database.withTransaction {
            dao.put(UserRecord(HISTORY,id,Json.encodeToString(entry.copy(tripId=id)),entry.recordedAtEpochMillis))
            dao.trim(HISTORY,100)
        }
        return routeHistory()
    }

    override suspend fun clearRecents() { initialize(); dao.clear(RECENTS) }
    /** Clears summaries in both tables. An active navigation checkpoint is separate and can produce a new summary. */
    override suspend fun clearRouteHistory() { clearHistory() }
    suspend fun clearHistory() { initialize(); database.withTransaction { dao.clear(HISTORY);dao.clearTrips() } }
    private suspend fun places(kind: String, limit: Int): List<PlaceSearchResult> = dao.records(kind,limit).map { Json.decodeFromString(it.payload) }

    companion object {
        private const val MIGRATION = "shared-prefs-user-places-v1"
        private const val SAVED = "saved"
        private const val RECENTS = "recent"
        private const val HISTORY = "history"
    }
}
