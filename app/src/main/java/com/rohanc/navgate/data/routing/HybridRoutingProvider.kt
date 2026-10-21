package com.rohanc.navgate.data.routing

import com.rohanc.navgate.core.*

/** Reviewed campus walking routes stay local; other coordinate endpoints use a real road router. */
class HybridRoutingProvider(private val regions:RegionStore,private val road:RoutingProvider):RoutingProvider {
    private val local=RoutePlanner(regions)
    override suspend fun plan(request:PlanRequest,pinnedVersion:String?):RouteResult {
        if(request.routingSource=="valhalla")return road.plan(request)
        if(request.routingSource!=null && request.routingSource!="local_campus_astar")return RouteResult.Unavailable(RouteFailure.InvalidGraph,"Unknown routing provider")
        if(pinnedVersion!=null || !request.entranceId.isNullOrBlank())return local.plan(request,pinnedVersion)
        val pack=regions.active(request.regionId)
        if(request.profile=="walking" && pack?.coverage?.contains(request.origin)==true && pack.entrances?.any{it.reviewed && it.placeId==request.destinationPlaceId}==true)
            return local.plan(request)
        return road.plan(request)
    }

    override suspend fun alternatives(request:PlanRequest):List<CampusRoute> {
        // External trips keep their provider even after entering campus coverage. Local graph/entrance
        // requests must not acquire unrelated road alternatives or bypass reviewed-campus routing.
        if(request.routingSource=="valhalla")return road.alternatives(request)
        if(request.routingSource!=null || !request.entranceId.isNullOrBlank())return emptyList()
        val pack=regions.active(request.regionId)
        if(request.profile=="walking" && pack?.coverage?.contains(request.origin)==true && pack.entrances.any{it.reviewed && it.placeId==request.destinationPlaceId})
            return emptyList()
        return road.alternatives(request)
    }
}
