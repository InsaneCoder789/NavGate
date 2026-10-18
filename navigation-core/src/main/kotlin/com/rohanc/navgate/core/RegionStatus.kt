package com.rohanc.navgate.core

import kotlinx.serialization.Serializable

@Serializable
enum class RegionReadiness { Missing, Invalid, CatalogueOnly, ReviewedGraph }

@Serializable
data class RegionStatus(
    val regionId:String,
    val readiness:RegionReadiness,
    val version:String?=null,
    val placeCount:Int=0,
    val missingCoordinateCount:Int=0,
    val reviewedEdgeCount:Int=0,
    val reviewedEntranceCount:Int=0,
    val reviewedAt:String?=null,
    val generatedAt:String?=null,
    val source:String?=null,
    val attribution:String?=null,
    val license:String?=null,
    // Tile installation has its own store; graph readiness must never imply an offline basemap.
    val basemapAvailability:String="not_managed_by_routing_store",
    val supportedProfiles:List<String> = emptyList(),
)

fun regionStatus(regionId:String,pack:RegionPack?):RegionStatus {
    if(pack==null) return RegionStatus(regionId,RegionReadiness.Missing)
    val valid=pack.regionId==regionId && PackValidator.validate(pack).valid
    val edges=pack.edges.filter { it.reviewed && it.access==Access.Allowed }
    val participating=edges.flatMap { listOf(it.from,it.to) }.toSet()
    val entrances=pack.entrances.filter { it.reviewed && it.nodeId in participating }
    val ready=valid && edges.isNotEmpty() && entrances.isNotEmpty()
    return RegionStatus(regionId,
        when { !valid -> RegionReadiness.Invalid; ready -> RegionReadiness.ReviewedGraph;else -> RegionReadiness.CatalogueOnly },
        pack.version,pack.places.size,pack.places.count { it.point==null },edges.size,entrances.size,
        pack.reviewedAt,pack.generatedAt,pack.source,pack.attribution,pack.license,
        supportedProfiles=if(ready) listOf("walking") else emptyList())
}
