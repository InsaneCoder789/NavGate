package com.rohanc.navgate.data.local

import com.rohanc.navgate.core.DestinationAvailability
import com.rohanc.navgate.core.destinationAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads the installed region, including saved places outside the current bounded search. */
data class DestinationDetails(val availability:DestinationAvailability?,val entranceLabels:Map<String,String>,val title:String?=null,val place:com.rohanc.navgate.model.PlaceSearchResult?=null)
suspend fun NavGateServices.destinationDetails(regionId:String,placeId:String,version:String?=null):DestinationDetails=withContext(Dispatchers.IO){
    val pack=if(version==null)regions.active(regionId)else regions.version(regionId,version)
    val capability=destinationAvailability(pack)[placeId]
    val reviewedIds=capability?.entranceIds.orEmpty().toSet()
    DestinationDetails(capability,pack?.entrances.orEmpty().filter{it.id in reviewedIds}.associate{it.id to it.label},pack?.places?.find{it.id==placeId}?.title,pack?.places?.find{it.id==placeId}?.toAppPlace())
}
