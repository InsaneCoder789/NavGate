package com.rohanc.navgate.data.local

import android.content.Context
import com.rohanc.navgate.core.*
import com.rohanc.navgate.data.NavigationRepository
import com.rohanc.navgate.data.search.PlaceSearchRanking
import com.rohanc.navgate.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RouteUnavailableException(val failure: RouteResult.Unavailable) : IllegalStateException(failure.detail)

/** Production data boundary: local search and local routing, with no public HTTP/fake fallbacks. */
class LocalNavigationRepository(
    context: Context,
    private val regions: RoomRegionStore,
    private val users: RoomUserPlacesStore,
) : BackendData, NavigationRepository {
    private val appContext = context.applicationContext
    private val initialization = Mutex()
    private var initialized = false

    override suspend fun initialize() = initialization.withLock {
        if (initialized) return@withLock
        if (regions.active("kiit") == null) {
            val bytes = withContext(Dispatchers.IO) { appContext.assets.open("regions/kiit-catalog.json").use { it.readBytes() } }
            val result = withContext(Dispatchers.Default) { RegionPackInstaller(regions).installBundled(bytes,"kiit") }
            check(result is InstallResult.Installed) { "Bundled catalogue validation failed" }
        }
        // Legacy user-data migration is independent: a corrupt bookmark must not disable graph/search access.
        initialized = true
    }

    override suspend fun search(query: String, regionId: String): List<CampusPlace> {
        initialize()
        return regions.search(query,regionId)
    }

    override suspend fun place(regionId: String, id: String): CampusPlace? { initialize(); return regions.place(regionId,id) }

    override suspend fun searchPlaces(query: String, cityHint: String?): List<PlaceSearchResult> {
        initialize()
        return regions.search(query,city=cityHint).map { it.toAppPlace() }
    }

    /** Legacy adapter for callers requiring the old interface. New screens should consume NavigationBackend.state. */
    override suspend fun fetchRoute(request: RouteRequest): RouteResponse {
        initialize()
        val id = request.destinationPlaceId ?: throw RouteUnavailableException(RouteResult.Unavailable(RouteFailure.MissingEntrance,"Choose a destination with a reviewed entrance"))
        val result = withContext(Dispatchers.Default) { RoutePlanner(regions).plan(PlanRequest(0,if (request.cityHint=="Mumbai") "mumbai" else "kiit",
            GeoPoint(request.origin.latitude,request.origin.longitude),id,profile=request.profile.name.lowercase())) }
        return when(result) {
            is RouteResult.Ready -> result.route.toAppRoute()
            is RouteResult.Unavailable -> throw RouteUnavailableException(result)
        }
    }
}

fun CampusPlace.toAppPlace(): PlaceSearchResult {
    val group=PlaceSearchRanking.category(category)
    val type=when(category.lowercase()) {
        "boys hostel","girls hostel","residential"->PlaceType.Residential
        "kiit","kiss","academic"->PlaceType.Academic
        "sports"->PlaceType.Sports
        "cafeteria","cafe","food"->PlaceType.Food
        "guest house","hospitality"->PlaceType.Hospitality
        "kims","medical"->PlaceType.Medical
        "commercial"->PlaceType.Commercial
        else->when(group){"health"->PlaceType.Medical;"education"->PlaceType.Academic;"food"->PlaceType.Food;"lodging"->PlaceType.Hospitality;"rail","airport"->PlaceType.Transit;else->PlaceType.Landmark}
    }
    val label=if(id.startsWith("geonames:"))when(group){
        "health"->"Hospital or clinic";"education"->"School or university";"food"->"Restaurant";"lodging"->"Hotel";
        "rail"->"Railway station";"airport"->"Airport";"worship"->"Place of worship";"settlement"->"City or locality";
        else->if(category.startsWith("ADM"))"Administrative area"else"Place"
    }else category
    return PlaceSearchResult(id,title,subtitle,point?.latitude,point?.longitude,type,city,campus,label,phone,website,hours,detail,sourceUrl)
}

internal fun maneuverTypeForKind(kind:String):ManeuverType=when(kind){
    "Start"->ManeuverType.Start;"SlightLeft"->ManeuverType.SlightLeft;"Left"->ManeuverType.Left
    "SlightRight"->ManeuverType.SlightRight;"Right"->ManeuverType.Right;"UTurn"->ManeuverType.UTurn
    "Roundabout"->ManeuverType.Roundabout;"RoundaboutExit"->ManeuverType.RoundaboutExit;"SharpLeft"->ManeuverType.SharpLeft;"SharpRight"->ManeuverType.SharpRight
    "KeepLeft"->ManeuverType.KeepLeft;"KeepRight"->ManeuverType.KeepRight;"Merge"->ManeuverType.Merge
    "Arrive"->ManeuverType.Arrive;else->ManeuverType.Straight
}

fun CampusRoute.toAppRoute(): RouteResponse = RouteResponse(distanceMeters,estimatedSeconds,
    geometry.map { Coordinate(it.latitude,it.longitude) },
    maneuvers.mapIndexed { index, maneuver ->
        RouteStep(maneuver.instruction,(maneuver.alongMeters-(maneuvers.getOrNull(index-1)?.alongMeters ?: 0.0)).coerceAtLeast(0.0),
            maneuver.target.latitude,maneuver.target.longitude,
            maneuverTypeForKind(maneuver.kind))
    },travelMode=profile,routeSource=source,routeConfidence=if(external)"provider_estimate"else"reviewed_graph",supportsAr=false,
    warnings=if(external)warnings+if(fromCache)listOf("Cached route; fetched at $fetchedAtEpochMillis")else emptyList() else listOf("Campus paths reviewed $reviewedAt; no live closure information"))
