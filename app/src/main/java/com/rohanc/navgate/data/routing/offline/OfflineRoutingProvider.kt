package com.rohanc.navgate.data.routing.offline

import com.rohanc.navgate.core.*
import com.rohanc.navgate.data.routing.RoadPreferences
import kotlinx.coroutines.CancellationException

/** Routes on the phone with BRouter, using the routing tiles the user has downloaded. Works with no network at all. */
class OfflineRoutingProvider internal constructor(
    private val tiles: TileAccess,
    private val engine: RouteEngine,
    private val streetNames: suspend (List<GeoPoint>) -> List<String?> = { emptyList() },
    private val clock: () -> Long = System::currentTimeMillis,
) : RoutingProvider {
    internal interface TileAccess { fun missing(names: List<String>): List<String>; fun prepare() }
    internal interface RouteEngine { suspend fun route(from: GeoPoint, to: GeoPoint, profile: String, avoidTolls: Boolean, avoidHighways: Boolean): OfflineTrack }

    companion object {
        /** [streetNames] gives a road name per point (from a saved map pack) so offline directions can name the roads. */
        fun of(data: RoutingDataStore, streetNames: suspend (List<GeoPoint>) -> List<String?> = { emptyList() }): OfflineRoutingProvider = OfflineRoutingProvider(object : TileAccess {
            override fun missing(names: List<String>) = data.missing(names)
            override fun prepare() = data.prepareProfiles()
        }, object : RouteEngine {
            private val brouter = BRouterEngine(data.segments, data.profiles)
            override suspend fun route(from: GeoPoint, to: GeoPoint, profile: String, avoidTolls: Boolean, avoidHighways: Boolean) = brouter.route(from, to, profile, avoidTolls, avoidHighways)
        }, streetNames)

        /** A provider with canned tiles and a canned answer, for tests. */
        internal fun forTesting(installed: Boolean, result: RouteResult): OfflineRoutingProvider = OfflineRoutingProvider(object : TileAccess {
            override fun missing(names: List<String>) = if (installed) emptyList() else names
            override fun prepare() {}
        }, object : RouteEngine {
            override suspend fun route(from: GeoPoint, to: GeoPoint, profile: String, avoidTolls: Boolean, avoidHighways: Boolean): OfflineTrack =
                OfflineTrack((result as RouteResult.Ready).route.geometry, result.route.estimatedSeconds)
        })
    }

    /** Tiles that must be installed for this trip, or null if it cannot be routed offline at all. */
    fun neededTiles(request: PlanRequest): List<String>? {
        val destination = request.destinationPoint ?: return null
        if (request.profile != "driving" && request.profile != "walking") return null
        return RoutingTiles.covering(listOf(request.origin, destination))
    }

    fun isReady(request: PlanRequest): Boolean = neededTiles(request)?.let { tiles.missing(it).isEmpty() } == true

    override suspend fun plan(request: PlanRequest, pinnedVersion: String?): RouteResult {
        val destination = request.destinationPoint ?: return RouteResult.Unavailable(RouteFailure.InvalidCoordinate, "Choose a destination with coordinates.")
        val needed = neededTiles(request) ?: return RouteResult.Unavailable(RouteFailure.UnsupportedProfile, "Offline routing supports driving and walking.")
        val missing = this.tiles.missing(needed)
        if (missing.isNotEmpty()) return RouteResult.Unavailable(RouteFailure.MissingRegion, "Offline routing data for this area is not downloaded. You can get it under Offline & region data.")
        return try {
            tiles.prepare()
            val track = engine.route(request.origin, destination, request.profile, RoadPreferences.avoidTolls, RoadPreferences.avoidHighways)
            val named = runCatching { streetNames(track.points) }.getOrDefault(emptyList())
            val route = OfflineRouteBuilder.build(request, OfflineTrack(track.points, track.seconds, named), clock())
            val problems = RouteIntegrity.shapeErrors(route)
            if (problems.isEmpty()) RouteResult.Ready(route) else RouteResult.Unavailable(RouteFailure.InvalidGraph, "The offline route could not be validated.")
        } catch (c: CancellationException) { throw c }
        catch (e: OfflineRouteException) { RouteResult.Unavailable(RouteFailure.OutsideCoverage, e.message ?: "No offline route was found.") }
        catch (e: Exception) { RouteResult.Unavailable(RouteFailure.InvalidGraph, "Offline routing failed: ${e.message ?: e.javaClass.simpleName}") }
    }
}

/** Where the network state the router should act on lives. Set by the app; read by the failover provider. */
object ConnectivityHint { @Volatile var online: Boolean = true }

/**
 * Tries the online router first and falls back to the offline one when the network fails or the server cannot answer.
 * With no network at all it goes straight to offline, so a trip in a dead zone does not wait for timeouts.
 */
class FailoverRoutingProvider(
    private val primary: RoutingProvider,
    private val offline: OfflineRoutingProvider,
    private val online: () -> Boolean = { ConnectivityHint.online },
) : RoutingProvider {
    override suspend fun plan(request: PlanRequest, pinnedVersion: String?): RouteResult {
        if (!online() && offline.isReady(request)) (offline.plan(request, pinnedVersion) as? RouteResult.Ready)?.let { return it }
        val first = try { primary.plan(request, pinnedVersion) } catch (c: CancellationException) { throw c } catch (e: Exception) { null }
        if (first is RouteResult.Ready) return first
        if (offline.isReady(request)) (offline.plan(request, pinnedVersion) as? RouteResult.Ready)?.let { return it }
        return first ?: primary.plan(request, pinnedVersion)
    }

    override suspend fun alternatives(request: PlanRequest): List<CampusRoute> {
        val online = if (online()) try { primary.alternatives(request) } catch (c: CancellationException) { throw c } catch (_: Exception) { emptyList() } else emptyList()
        if (online.isNotEmpty() || !offline.isReady(request)) return online
        return listOfNotNull((offline.plan(request, null) as? RouteResult.Ready)?.route)
    }
}
