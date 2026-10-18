package com.rohanc.navgate.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.PublicKey
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

@Serializable
enum class Access { Allowed, Restricted, Unknown }

@Serializable
data class GraphNode(val id: String, val point: GeoPoint)

@Serializable
data class GraphEdge(
    val id: String,
    val from: String,
    val to: String,
    val geometry: List<GeoPoint>,
    val access: Access = Access.Unknown,
    val reviewed: Boolean = false,
    val name: String = "",
    val stairs: Boolean = false,
)

@Serializable
data class CampusPlace(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val category: String = "",
    val city: String,
    val campus: String? = null,
    val point: GeoPoint? = null,
    val aliases: List<String> = emptyList(),
    val reviewed: Boolean = false,
    val sourceRow: Int? = null,
    val sourceUrl: String? = null,
)

@Serializable
data class Entrance(val id: String, val placeId: String, val nodeId: String, val label: String, val reviewed: Boolean = false)

@Serializable
data class RegionPack(
    val schemaVersion: Int = 1,
    val regionId: String,
    val version: String,
    val city: String,
    val coverage: Coverage,
    val source: String,
    val attribution: String,
    val license: String,
    val generatedAt: String,
    val reviewedAt: String? = null,
    val nodes: List<GraphNode> = emptyList(),
    val edges: List<GraphEdge> = emptyList(),
    val places: List<CampusPlace> = emptyList(),
    val entrances: List<Entrance> = emptyList(),
)

data class PackValidation(val errors: List<String>) { val valid: Boolean get() = errors.isEmpty() }

object PackValidator {
    fun validate(pack: RegionPack): PackValidation {
        val errors = mutableListOf<String>()
        fun check(ok: Boolean, message: String) { if (!ok) errors += message }
        fun ids(name: String, values: List<String>) {
            check(values.all { it.isNotBlank() && it.length <= 160 }, "$name identifiers must be nonblank and at most 160 characters")
            check(values.distinct().size == values.size, "Duplicate $name identifiers")
        }
        check(pack.schemaVersion == 1, "Unsupported schema version")
        check(pack.regionId.matches(Regex("[a-z0-9][a-z0-9_-]{0,79}")), "Invalid region ID")
        check(pack.version.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")), "Invalid graph version")
        check(listOf(pack.city, pack.source, pack.attribution, pack.license, pack.generatedAt).all { it.isNotBlank() }, "Source, licence, attribution, city and generation date are required")
        check(pack.nodes.size <= 50_000 && pack.edges.size <= 150_000 && pack.places.size <= 30_000 && pack.entrances.size <= 50_000, "Pack record limits exceeded")
        val boundary = pack.coverage.boundary
        check(boundary.size in 3..2000 && boundary.all { it.valid }, "Invalid coverage polygon")
        check(boundary.distinct().size >= 3, "Coverage needs three distinct vertices")
        // Bound the tangent-plane geometry and prevent antimeridian/polar packs.
        if (boundary.isNotEmpty() && boundary.all { it.valid }) {
            check(boundary.all { kotlin.math.abs(it.latitude) < 80 }, "Polar regions unsupported")
            check(boundary.maxOf { it.latitude } - boundary.minOf { it.latitude } <= 1.0 && boundary.maxOf { it.longitude } - boundary.minOf { it.longitude } <= 1.0, "Coverage exceeds bounded campus limits")
            val area = boundary.indices.sumOf { i -> val a = boundary[i]; val b = boundary[(i + 1) % boundary.size]; a.longitude * b.latitude - b.longitude * a.latitude }
            check(kotlin.math.abs(area) > 1e-12, "Degenerate coverage polygon")
        }
        ids("node", pack.nodes.map { it.id }); ids("edge", pack.edges.map { it.id })
        ids("place", pack.places.map { it.id }); ids("entrance", pack.entrances.map { it.id })
        val nodes = pack.nodes.associateBy { it.id }
        val places = pack.places.associateBy { it.id }
        pack.nodes.forEach { check(it.point.valid && pack.coverage.contains(it.point), "Invalid/outside node ${it.id}") }
        pack.places.forEach {
            check(it.title.isNotBlank() && it.city.isNotBlank(), "Missing place name/city ${it.id}")
            check(it.point == null || it.point.valid, "Invalid place coordinate ${it.id}")
        }
        pack.edges.forEach { edge ->
            val from = nodes[edge.from]; val to = nodes[edge.to]
            check(from != null && to != null, "Unknown edge endpoint ${edge.id}")
            check(edge.geometry.size in 2..10_000 && edge.geometry.all { it.valid && pack.coverage.contains(it) }, "Invalid edge geometry ${edge.id}")
            if (edge.geometry.size >= 2 && from != null && to != null) {
                check(edge.geometry.first() == from.point && edge.geometry.last() == to.point, "Edge geometry must follow directed endpoints exactly ${edge.id}")
                check(edge.geometry.lengthMeters().let { it.isFinite() && it > 0.01 }, "Invalid edge length ${edge.id}")
            }
            check(!edge.reviewed || !pack.reviewedAt.isNullOrBlank(), "Reviewed edges require review date")
        }
        pack.entrances.forEach {
            check(nodes.containsKey(it.nodeId) && places.containsKey(it.placeId), "Invalid entrance reference ${it.id}")
            check(!it.reviewed || !pack.reviewedAt.isNullOrBlank(), "Reviewed entrances require review date")
        }
        return PackValidation(errors.distinct())
    }
}

/** Implementations must atomically store the immutable version and switch the active pointer. */
interface RegionStore {
    suspend fun active(regionId: String): RegionPack?
    suspend fun version(regionId: String, version: String): RegionPack?
    suspend fun activate(pack: RegionPack, sha256: String)
}

sealed interface InstallResult {
    data class Installed(val regionId: String, val version: String, val sha256: String) : InstallResult
    data class Rejected(val reasons: List<String>) : InstallResult
}

class RegionPackInstaller(private val store: RegionStore, private val publicKey: PublicKey? = null) {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = false }

    /** Only APK-owned assets may use this entry; never pass downloaded bytes here. */
    val trustedUpdatesEnabled: Boolean get() = (publicKey as? RSAPublicKey)?.modulus?.bitLength()?.let { it>=2048 } == true

    suspend fun installBundled(bytes: ByteArray, expectedRegionId: String? = null): InstallResult = install(bytes, null, true,expectedRegionId)

    suspend fun installSigned(bytes: ByteArray, signature: ByteArray, expectedRegionId: String? = null): InstallResult = install(bytes, signature, false,expectedRegionId)

    private suspend fun install(bytes: ByteArray, signature: ByteArray?, bundled: Boolean, expectedRegionId: String?): InstallResult = lock.withLock {
        if (bytes.size !in 1..MAX_BYTES) return@withLock InstallResult.Rejected(listOf("Pack exceeds byte limit or is empty"))
        if (!bundled) {
            val key = publicKey?.takeIf { trustedUpdatesEnabled } ?: return@withLock InstallResult.Rejected(listOf("No trusted RSA update signing key configured"))
            val authentic = runCatching {
                Signature.getInstance("SHA256withRSA").run { initVerify(key); update(bytes); verify(requireNotNull(signature)) }
            }.getOrDefault(false)
            if (!authentic) return@withLock InstallResult.Rejected(listOf("Invalid pack signature"))
        }
        coroutineContext.ensureActive()
        val pack = try { json.decodeFromString<RegionPack>(bytes.decodeToString(throwOnInvalidSequence = true)) }
        catch (error: IllegalArgumentException) { return@withLock InstallResult.Rejected(listOf("Invalid pack JSON: ${error.message?.take(160)}")) }
        val validation = PackValidator.validate(pack)
        if (!validation.valid) return@withLock InstallResult.Rejected(validation.errors)
        if (expectedRegionId!=null && pack.regionId!=expectedRegionId) return@withLock InstallResult.Rejected(listOf("Pack does not match the selected region"))
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        coroutineContext.ensureActive()
        try { store.activate(pack, digest) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return@withLock InstallResult.Rejected(listOf("Pack could not be activated; the previous graph was retained")) }
        InstallResult.Installed(pack.regionId, pack.version, digest)
    }

    companion object { const val MAX_BYTES = 8 * 1024 * 1024 }
}
