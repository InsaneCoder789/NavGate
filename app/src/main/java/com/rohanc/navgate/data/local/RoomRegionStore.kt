package com.rohanc.navgate.data.local

import androidx.room.withTransaction
import com.rohanc.navgate.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale

class RoomRegionStore(private val database: NavGateDatabase) : RegionStore {
    private val dao = database.regions()
    private val json = Json

    override suspend fun active(regionId: String): RegionPack? = dao.active(regionId)?.let { json.decodeFromString<RegionPack>(it.payload) }
    override suspend fun version(regionId: String, version: String): RegionPack? = dao.version(regionId, version)?.let { json.decodeFromString<RegionPack>(it.payload) }

    override suspend fun activate(pack: RegionPack, sha256: String) {
        require(PackValidator.validate(pack).valid) { "Invalid pack cannot enter persistent storage" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid pack digest" }
        database.withTransaction {
            val existing = dao.version(pack.regionId, pack.version)
            if (existing != null) {
                require(existing.sha256 == sha256 && existing.payload == json.encodeToString(pack)) { "Graph version is immutable; publish a new version" }
            } else {
                dao.insertRegion(RegionRecord(pack.regionId, null))
                dao.insertVersion(GraphVersionRecord(pack.regionId, pack.version, json.encodeToString(pack), sha256))
                dao.insertNodes(pack.nodes.map { NodeRecord(pack.regionId, pack.version, it.id, it.point.latitude, it.point.longitude) })
                dao.insertPlaces(pack.places.map { place ->
                    PlaceRecord(pack.regionId, pack.version, place.id, place.title, place.city,
                        (listOf(place.title, place.subtitle, place.category, place.city, place.campus.orEmpty()) + place.aliases).joinToString(" ").lowercase(Locale.ROOT), json.encodeToString(place))
                })
                dao.insertEdges(pack.edges.map { EdgeRecord(pack.regionId, pack.version, it.id, it.from, it.to, json.encodeToString(it.geometry), it.geometry.lengthMeters(), it.access.name, it.reviewed) })
                dao.insertEntrances(pack.entrances.map { EntranceRecord(pack.regionId, pack.version, it.id, it.placeId, it.nodeId, it.reviewed) })
            }
            dao.setActive(pack.regionId, pack.version)
        }
    }

    suspend fun search(query: String, regionId: String? = null, city: String? = null, limit: Int = 40): List<CampusPlace> =
        dao.search(query.trim().take(256).lowercase(Locale.ROOT), regionId, city, limit.coerceIn(1,100)).map { json.decodeFromString<CampusPlace>(it.payload) }

    /** All the places of a region's active pack. */
    suspend fun all(regionId: String, limit: Int = 5000): List<CampusPlace> =
        dao.search("", regionId, null, limit).map { json.decodeFromString<CampusPlace>(it.payload) }

    suspend fun place(regionId: String, id: String): CampusPlace? = dao.place(regionId,id)?.let { json.decodeFromString<CampusPlace>(it.payload) }
}
