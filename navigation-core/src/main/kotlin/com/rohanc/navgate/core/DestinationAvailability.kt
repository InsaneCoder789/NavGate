package com.rohanc.navgate.core

import kotlinx.serialization.Serializable

@Serializable
enum class DestinationReadiness { MissingRegion, InvalidGraph, MissingGraph, MissingEntrance, ReviewedEntrance }

@Serializable
data class DestinationAvailability(
    val placeId:String,
    val readiness:DestinationReadiness,
    val entranceIds:List<String> = emptyList(),
    val hasDisplayCoordinate:Boolean=false,
)

/** Batch capabilities for search screens. ReviewedEntrance is not a promise of connectivity from every origin. */
fun destinationAvailability(pack:RegionPack?):Map<String,DestinationAvailability> {
    if(pack==null) return emptyMap()
    val valid=PackValidator.validate(pack).valid
    val legal=pack.edges.filter{it.reviewed && it.access==Access.Allowed}
    val participating=legal.flatMap{listOf(it.from,it.to)}.toHashSet()
    val entrances=pack.entrances.filter{it.reviewed && it.nodeId in participating}.groupBy{it.placeId}
    return pack.places.associate{place ->
        val ids=entrances[place.id].orEmpty().map{it.id}
        place.id to DestinationAvailability(place.id,when{
            !valid -> DestinationReadiness.InvalidGraph
            legal.isEmpty() -> DestinationReadiness.MissingGraph
            ids.isEmpty() -> DestinationReadiness.MissingEntrance
            else -> DestinationReadiness.ReviewedEntrance
        },ids,place.point!=null)
    }
}
