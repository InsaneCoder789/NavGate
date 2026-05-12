package com.rohanc.navgate.data

import com.rohanc.navgate.model.PlaceSearchResult
import com.rohanc.navgate.model.RouteRequest
import com.rohanc.navgate.model.RouteResponse

interface NavigationRepository {
    suspend fun searchPlaces(query: String, cityHint: String? = null): List<PlaceSearchResult>

    suspend fun fetchRoute(request: RouteRequest): RouteResponse
}
