package com.rohanc.navgate.core

import kotlinx.serialization.Serializable

@Serializable
enum class RegionReadiness { Missing, Invalid, Catalogue }

/** What is installed for a region. The catalogue is a list of test places; routes always come from the road routers. */
@Serializable
data class RegionStatus(
    val regionId:String,
    val readiness:RegionReadiness,
    val version:String?=null,
    val placeCount:Int=0,
    val missingCoordinateCount:Int=0,
    val reviewedAt:String?=null,
    val generatedAt:String?=null,
    val source:String?=null,
    val attribution:String?=null,
    val license:String?=null,
)

fun regionStatus(regionId:String,pack:RegionPack?):RegionStatus {
    if(pack==null) return RegionStatus(regionId,RegionReadiness.Missing)
    val valid=pack.regionId==regionId && PackValidator.validate(pack).valid
    return RegionStatus(regionId,if(valid) RegionReadiness.Catalogue else RegionReadiness.Invalid,
        pack.version,pack.places.size,pack.places.count { it.point==null },
        pack.reviewedAt,pack.generatedAt,pack.source,pack.attribution,pack.license)
}
