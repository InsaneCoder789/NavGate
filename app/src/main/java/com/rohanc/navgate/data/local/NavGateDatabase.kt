package com.rohanc.navgate.data.local

import android.content.Context
import androidx.room.*

@Entity(tableName = "regions")
data class RegionRecord(@PrimaryKey val id: String, val activeVersion: String?)

@Entity(tableName = "graph_versions", primaryKeys = ["regionId", "version"], foreignKeys = [
    ForeignKey(entity = RegionRecord::class, parentColumns = ["id"], childColumns = ["regionId"], onDelete = ForeignKey.RESTRICT),
])
data class GraphVersionRecord(val regionId: String, val version: String, val payload: String, val sha256: String)

@Entity(tableName = "graph_nodes", primaryKeys = ["regionId", "version", "id"], foreignKeys = [
    ForeignKey(entity = GraphVersionRecord::class, parentColumns = ["regionId", "version"], childColumns = ["regionId", "version"], onDelete = ForeignKey.CASCADE),
])
data class NodeRecord(val regionId: String, val version: String, val id: String, val latitude: Double, val longitude: Double)

@Entity(tableName = "graph_edges", primaryKeys = ["regionId", "version", "id"], foreignKeys = [
    ForeignKey(entity = NodeRecord::class, parentColumns = ["regionId", "version", "id"], childColumns = ["regionId", "version", "fromId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = NodeRecord::class, parentColumns = ["regionId", "version", "id"], childColumns = ["regionId", "version", "toId"], onDelete = ForeignKey.RESTRICT),
], indices = [Index(value = ["regionId", "version", "fromId"]), Index(value = ["regionId", "version", "toId"])])
data class EdgeRecord(val regionId: String, val version: String, val id: String, val fromId: String, val toId: String, val geometry: String, val lengthMeters: Double, val access: String, val reviewed: Boolean)

@Entity(tableName = "places", primaryKeys = ["regionId", "version", "id"], foreignKeys = [
    ForeignKey(entity = GraphVersionRecord::class, parentColumns = ["regionId", "version"], childColumns = ["regionId", "version"], onDelete = ForeignKey.CASCADE),
], indices = [Index(value = ["city", "regionId", "version"])])
data class PlaceRecord(val regionId: String, val version: String, val id: String, val title: String, val city: String, val searchText: String, val payload: String)

@Entity(tableName = "entrances", primaryKeys = ["regionId", "version", "id"], foreignKeys = [
    ForeignKey(entity = PlaceRecord::class, parentColumns = ["regionId", "version", "id"], childColumns = ["regionId", "version", "placeId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = NodeRecord::class, parentColumns = ["regionId", "version", "id"], childColumns = ["regionId", "version", "nodeId"], onDelete = ForeignKey.RESTRICT),
], indices = [Index(value = ["regionId", "version", "placeId"]), Index(value = ["regionId", "version", "nodeId"])])
data class EntranceRecord(val regionId: String, val version: String, val id: String, val placeId: String, val nodeId: String, val reviewed: Boolean)

/** User snapshots intentionally have no FK to a replaceable catalogue: bookmarks survive missing places. */
@Entity(tableName = "user_records", primaryKeys = ["kind", "id"])
data class UserRecord(val kind: String, val id: String, val payload: String, val updatedAt: Long)

@Entity(tableName = "trip_records")
data class TripRecord(@PrimaryKey val id: String, val regionId: String, val graphVersion: String, val payload: String, val updatedAt: Long)

@Entity(tableName = "session_checkpoint")
data class CheckpointRecord(@PrimaryKey val id: Int = 1, val payload: String, val updatedAt: Long)

@Entity(tableName = "migration_markers")
data class MigrationMarker(@PrimaryKey val id: String)

@Dao
interface RegionDao {
    @Query("SELECT v.* FROM graph_versions v JOIN regions r ON r.id=v.regionId AND r.activeVersion=v.version WHERE r.id=:regionId")
    suspend fun active(regionId: String): GraphVersionRecord?
    @Query("SELECT * FROM graph_versions WHERE regionId=:regionId AND version=:version")
    suspend fun version(regionId: String, version: String): GraphVersionRecord?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertRegion(region: RegionRecord)
    @Insert suspend fun insertVersion(version: GraphVersionRecord)
    @Insert suspend fun insertNodes(nodes: List<NodeRecord>)
    @Insert suspend fun insertEdges(edges: List<EdgeRecord>)
    @Insert suspend fun insertPlaces(places: List<PlaceRecord>)
    @Insert suspend fun insertEntrances(entrances: List<EntranceRecord>)
    @Query("UPDATE regions SET activeVersion=:version WHERE id=:regionId") suspend fun setActive(regionId: String, version: String)
    @Query("SELECT p.* FROM places p JOIN regions r ON r.id=p.regionId AND r.activeVersion=p.version WHERE (:regionId IS NULL OR p.regionId=:regionId) AND (:city IS NULL OR p.city=:city) AND instr(p.searchText,:query)>0 ORDER BY CASE WHEN lower(p.title)=:query THEN 0 ELSE 1 END, p.title COLLATE NOCASE, p.id LIMIT :limit")
    suspend fun search(query: String, regionId: String?, city: String?, limit: Int): List<PlaceRecord>
    @Query("SELECT p.* FROM places p JOIN regions r ON r.id=p.regionId AND r.activeVersion=p.version WHERE p.regionId=:regionId AND p.id=:id")
    suspend fun place(regionId: String, id: String): PlaceRecord?
}

@Dao
interface UserDao {
    @Query("SELECT * FROM user_records WHERE kind=:kind ORDER BY updatedAt DESC, id LIMIT :limit") suspend fun records(kind: String, limit: Int): List<UserRecord>
    @Query("SELECT * FROM user_records WHERE kind=:kind AND id=:id") suspend fun record(kind: String, id: String): UserRecord?
    @Upsert suspend fun put(record: UserRecord)
    @Query("DELETE FROM user_records WHERE kind=:kind AND id=:id") suspend fun remove(kind: String, id: String)
    @Query("DELETE FROM user_records WHERE kind=:kind") suspend fun clear(kind: String)
    @Query("SELECT COUNT(*) FROM user_records WHERE kind=:kind") suspend fun count(kind: String): Int
    @Query("DELETE FROM user_records WHERE kind=:kind AND id NOT IN (SELECT id FROM user_records WHERE kind=:kind ORDER BY updatedAt DESC, id LIMIT :limit)")
    suspend fun trim(kind: String, limit: Int)
    @Query("SELECT EXISTS(SELECT 1 FROM migration_markers WHERE id=:id)") suspend fun migrated(id: String): Boolean
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun mark(marker: MigrationMarker)
    @Upsert suspend fun putTrip(record: TripRecord)
    @Query("SELECT * FROM trip_records ORDER BY updatedAt DESC LIMIT :limit") suspend fun trips(limit: Int): List<TripRecord>
    @Query("SELECT * FROM trip_records WHERE id=:id") suspend fun trip(id:String): TripRecord?
    @Query("DELETE FROM trip_records") suspend fun clearTrips()
    @Query("DELETE FROM trip_records WHERE id<>:preserveId AND id NOT IN (SELECT id FROM trip_records ORDER BY updatedAt DESC, id LIMIT 100)") suspend fun trimTrips(preserveId:String)
    @Upsert suspend fun putCheckpoint(record: CheckpointRecord)
    @Query("SELECT * FROM session_checkpoint WHERE id=1") suspend fun checkpoint(): CheckpointRecord?
    @Query("DELETE FROM session_checkpoint") suspend fun clearCheckpoint()
}

@Database(entities = [RegionRecord::class, GraphVersionRecord::class, NodeRecord::class, EdgeRecord::class, PlaceRecord::class,
    EntranceRecord::class, UserRecord::class, TripRecord::class, CheckpointRecord::class, MigrationMarker::class], version = 1, exportSchema = true)
abstract class NavGateDatabase : RoomDatabase() {
    abstract fun regions(): RegionDao
    abstract fun user(): UserDao

    companion object {
        @Volatile private var instance: NavGateDatabase? = null
        fun get(context: Context): NavGateDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, NavGateDatabase::class.java, "navgate.db").build().also { instance = it }
        }
    }
}
