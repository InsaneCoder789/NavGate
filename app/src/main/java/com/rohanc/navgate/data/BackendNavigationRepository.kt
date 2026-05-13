package com.rohanc.navgate.data

import android.app.Application
import android.content.Context
import com.rohanc.navgate.BuildConfig
import com.rohanc.navgate.model.Coordinate
import com.rohanc.navgate.model.ManeuverType
import com.rohanc.navgate.model.PlaceSearchResult
import com.rohanc.navgate.model.PlaceType
import com.rohanc.navgate.model.RouteRequest
import com.rohanc.navgate.model.RouteResponse
import com.rohanc.navgate.model.RouteStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class BackendNavigationRepository(
    private val application: Application? = null,
    private val baseUrl: String = BuildConfig.NAVGATE_BACKEND_URL,
    private val client: OkHttpClient = defaultHttpClient(),
    private val fallback: NavigationRepository = FakeCampusRepository(),
) : NavigationRepository {
    override suspend fun searchPlaces(query: String, cityHint: String?): List<PlaceSearchResult> =
        runCatching {
            withContext(Dispatchers.IO) {
                val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
                val cityQuery =
                    cityHint
                        ?.takeIf { it.isNotBlank() }
                        ?.let { "&city=${URLEncoder.encode(it, Charsets.UTF_8.name())}" }
                        .orEmpty()
                val request = Request.Builder().url("$baseUrl/places?query=$encoded$cityQuery").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("search failed with ${response.code}")
                    val backendPlaces = parsePlaces(JSONArray(response.body?.string().orEmpty()))
                    if (backendPlaces.isNotEmpty()) {
                        backendPlaces
                    } else {
                        fallbackPlaces(query, cityHint)
                    }
                }
            }
        }.getOrElse {
            fallbackPlaces(query, cityHint)
        }

    private suspend fun fallbackPlaces(query: String, cityHint: String?): List<PlaceSearchResult> =
        runCatching {
            val bundled = application?.let { searchBundledPlaces(it, query, cityHint) }.orEmpty()
            val openMap = searchOpenMap(query, cityHint)
            (bundled + openMap)
                .distinctBy { "${it.title}-${it.latitude}-${it.longitude}" }
                .take(12)
                .ifEmpty { fallback.searchPlaces(query, cityHint) }
        }.getOrElse {
            fallback.searchPlaces(query, cityHint)
        }

    override suspend fun fetchRoute(request: RouteRequest): RouteResponse =
        runCatching {
            fetchOpenMapRoute(request)
        }.getOrElse {
            runCatching {
                withContext(Dispatchers.IO) {
                    val payload =
                        JSONObject()
                            .put("origin", JSONObject().put("latitude", request.origin.latitude).put("longitude", request.origin.longitude))
                            .put("destination", JSONObject().put("latitude", request.destination.latitude).put("longitude", request.destination.longitude))
                            .put("destinationPlaceId", request.destinationPlaceId)
                            .put("cityHint", request.cityHint ?: "Bhubaneswar")
                            .put("profile", request.profile.name.lowercase())
                    val httpRequest =
                        Request.Builder()
                            .url("$baseUrl/route")
                            .post(payload.toString().toRequestBody("application/json".toMediaType()))
                            .build()
                    client.newCall(httpRequest).execute().use { response ->
                        if (!response.isSuccessful) error("route failed with ${response.code}")
                        parseRoute(JSONObject(response.body?.string().orEmpty()))
                    }
                }
            }.getOrElse {
                fallback.fetchRoute(request)
            }
        }

    private suspend fun searchOpenMap(query: String, cityHint: String?): List<PlaceSearchResult> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val composedQuery =
                buildString {
                    append(query)
                    cityHint?.takeIf { it.isNotBlank() }?.let {
                        append(", ")
                        append(it)
                    }
                    append(", India")
                }
            val encoded = URLEncoder.encode(composedQuery, Charsets.UTF_8.name())
            val url =
                "https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=1&limit=8&countrycodes=in&q=$encoded"
            val request =
                Request.Builder()
                    .url(url)
                    .header("User-Agent", "NavGate/1.0 (android prototype)")
                    .header("Accept-Language", "en")
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val array = JSONArray(response.body?.string().orEmpty())
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.getJSONObject(index)
                        val latitude = item.optString("lat").toDoubleOrNull() ?: continue
                        val longitude = item.optString("lon").toDoubleOrNull() ?: continue
                        val displayName = item.optString("display_name")
                        val title = displayName.substringBefore(",").ifBlank { item.optString("name", "Location") }
                        val subtitle = displayName.substringAfter(",", "").trim().ifBlank { item.optString("type", "OpenStreetMap location") }
                        add(
                            PlaceSearchResult(
                                id = "osm-${item.optString("osm_type")}-${item.optString("osm_id")}",
                                title = title,
                                subtitle = subtitle,
                                latitude = latitude,
                                longitude = longitude,
                                type = item.optString("type").toPlaceTypeFromOpenMap(),
                                city = cityHint,
                                category = item.optString("category").ifBlank { item.optString("type") },
                            ),
                        )
                    }
                }
            }
        }

    private suspend fun fetchOpenMapRoute(request: RouteRequest): RouteResponse =
        withContext(Dispatchers.IO) {
            val profile = if (request.profile.name.equals("Driving", ignoreCase = true)) "driving" else "foot"
            val url =
                "https://router.project-osrm.org/route/v1/$profile/${request.origin.longitude},${request.origin.latitude};${request.destination.longitude},${request.destination.latitude}" +
                    "?overview=full&geometries=geojson&steps=true"
            val httpRequest =
                Request.Builder()
                    .url(url)
                    .header("User-Agent", "NavGate/1.0 (android prototype)")
                    .build()
            client.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) error("open route failed with ${response.code}")
                val payload = JSONObject(response.body?.string().orEmpty())
                val route = payload.getJSONArray("routes").getJSONObject(0)
                val geometry = route.getJSONObject("geometry").getJSONArray("coordinates")
                val coordinates =
                    buildList {
                        for (index in 0 until geometry.length()) {
                            val point = geometry.getJSONArray(index)
                            add(Coordinate(latitude = point.getDouble(1), longitude = point.getDouble(0)))
                        }
                    }
                val legs = route.getJSONArray("legs")
                val steps =
                    buildList {
                        for (legIndex in 0 until legs.length()) {
                            val leg = legs.getJSONObject(legIndex)
                            val stepsArray = leg.getJSONArray("steps")
                            for (stepIndex in 0 until stepsArray.length()) {
                                val step = stepsArray.getJSONObject(stepIndex)
                                val maneuver = step.getJSONObject("maneuver")
                                val location = maneuver.getJSONArray("location")
                                add(
                                    RouteStep(
                                        instruction = buildInstruction(step, maneuver),
                                        distanceMeters = step.getDouble("distance"),
                                        targetLatitude = location.getDouble(1),
                                        targetLongitude = location.getDouble(0),
                                        maneuverType = maneuver.optString("modifier").toManeuverTypeFromOpenMap(),
                                        durationSeconds = step.optDouble("duration"),
                                        streetName = step.optString("name"),
                                    ),
                                )
                            }
                        }
                    }
                RouteResponse(
                    distanceMeters = route.getDouble("distance"),
                    durationSeconds = route.getDouble("duration"),
                    pathCoordinates = coordinates,
                    steps = if (steps.isEmpty()) fallback.fetchRoute(request).steps else steps,
                    travelMode = request.profile.name.lowercase(),
                    routeSource = "osrm",
                    routeConfidence = "high",
                    supportsAr = true,
                )
            }
        }

    private fun searchBundledPlaces(context: Context, query: String, cityHint: String?): List<PlaceSearchResult> {
        val token = query.trim().lowercase()
        val targetCity = cityHint?.lowercase()
        val results = mutableListOf<PlaceSearchResult>()
        val assetFiles =
            when (targetCity) {
                "mumbai" -> listOf("mumbai_popular_places.json")
                "bhubaneswar" -> listOf("custom_pois.json", "bhubaneswar_student_places.json")
                else -> listOf("mumbai_popular_places.json", "custom_pois.json", "bhubaneswar_student_places.json")
            }
        for (assetFile in assetFiles) {
            val content = context.assets.open(assetFile).bufferedReader().use { it.readText() }
            val array = JSONArray(content)
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val city = item.optString("city")
                if (targetCity != null && !city.equals(cityHint, ignoreCase = true)) continue
                if (token.isNotBlank()) {
                    val joined =
                        listOf(
                            item.optString("name"),
                            item.optString("title"),
                            item.optString("subtitle"),
                            item.optString("normalizedName"),
                            item.optString("categoryNormalized"),
                        ).joinToString(" ").lowercase()
                    if (!joined.contains(token)) continue
                }
                results +=
                    PlaceSearchResult(
                        id = item.optString("id").ifBlank { "${assetFile.removeSuffix(".json")}-$index" },
                        title = item.optString("name").ifBlank { item.optString("title", "Location") },
                        subtitle = item.optString("subtitle"),
                        latitude = item.getDouble("latitude"),
                        longitude = item.getDouble("longitude"),
                        type = (item.optString("placeType").ifBlank { item.optString("type") }).toPlaceType(),
                        city = city.takeIf { it.isNotBlank() },
                        campusLabel = item.optString("campusLabel").takeIf { it.isNotBlank() },
                        category = item.optString("categoryNormalized").ifBlank { item.optString("category") }.takeIf { it.isNotBlank() },
                    )
                if (results.size >= 20) break
            }
            if (results.size >= 20) break
        }
        return results.distinctBy { "${it.title}-${it.latitude}-${it.longitude}" }
    }

    private fun parsePlaces(array: JSONArray): List<PlaceSearchResult> =
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    PlaceSearchResult(
                        id = item.getString("id"),
                        title = item.getString("title"),
                        subtitle = item.getString("subtitle"),
                        latitude = item.getDouble("latitude"),
                        longitude = item.getDouble("longitude"),
                        type = item.optString("type").toPlaceType(),
                        city = item.optString("city").takeIf { it.isNotBlank() },
                        campusLabel = item.optString("campusLabel").takeIf { it.isNotBlank() },
                        category = item.optString("category").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }

    private fun parseRoute(json: JSONObject): RouteResponse {
        val coordinates =
            buildList {
                val coordinatesJson = json.getJSONArray("pathCoordinates")
                for (index in 0 until coordinatesJson.length()) {
                    val coordinate = coordinatesJson.getJSONObject(index)
                    add(Coordinate(coordinate.getDouble("latitude"), coordinate.getDouble("longitude")))
                }
            }
        val steps =
            buildList {
                val stepsJson = json.getJSONArray("steps")
                for (index in 0 until stepsJson.length()) {
                    val step = stepsJson.getJSONObject(index)
                    add(
                        RouteStep(
                            instruction = step.getString("instruction"),
                            distanceMeters = step.getDouble("distanceMeters"),
                            targetLatitude = step.getDouble("targetLatitude"),
                            targetLongitude = step.getDouble("targetLongitude"),
                            maneuverType = step.optString("maneuverType").toManeuverType(),
                            durationSeconds = step.optDouble("durationSeconds"),
                            bearingStart = step.optDouble("bearingStart"),
                            bearingEnd = step.optDouble("bearingEnd"),
                            streetName = step.optString("streetName"),
                        ),
                    )
                }
            }
        return RouteResponse(
            distanceMeters = json.getDouble("distanceMeters"),
            durationSeconds = json.getDouble("durationSeconds"),
            pathCoordinates = coordinates,
            steps = steps,
            travelMode = json.optString("travelMode", requestModeFromRoute(json)),
            routeSource = json.optString("routeSource", "backend"),
            routeConfidence = json.optString("routeConfidence", "medium"),
            supportsAr = json.optBoolean("supportsAr", true),
            warnings = json.optJSONArray("warnings")?.toStringList().orEmpty(),
        )
    }

    companion object {
        private fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(1500, TimeUnit.MILLISECONDS)
                .readTimeout(2500, TimeUnit.MILLISECONDS)
                .build()

        private fun requestModeFromRoute(json: JSONObject): String =
            if (json.optDouble("distanceMeters") > 0) "walking" else "walking"
    }
}

private fun JSONArray.toStringList(): List<String> = List(length()) { getString(it) }

private fun String.toPlaceType(): PlaceType =
    when (this) {
        "Gate" -> PlaceType.Gate
        "Academic", "KISS" -> PlaceType.Academic
        "Sports" -> PlaceType.Sports
        "Residential" -> PlaceType.Residential
        "Food" -> PlaceType.Food
        "Commercial" -> PlaceType.Commercial
        "Hospitality" -> PlaceType.Hospitality
        "Medical" -> PlaceType.Medical
        "Landmark" -> PlaceType.Landmark
        else -> PlaceType.Transit
    }

private fun String.toPlaceTypeFromOpenMap(): PlaceType =
    when (lowercase()) {
        "restaurant", "cafe", "fast_food" -> PlaceType.Food
        "school", "college", "university" -> PlaceType.Academic
        "hospital", "clinic", "doctors" -> PlaceType.Medical
        "hotel", "guest_house" -> PlaceType.Hospitality
        "bus_stop", "station" -> PlaceType.Transit
        "commercial", "supermarket", "shop" -> PlaceType.Commercial
        "park", "stadium", "sports_centre" -> PlaceType.Sports
        else -> PlaceType.Landmark
    }

private fun String.toManeuverType(): ManeuverType =
    when (this) {
        "Start" -> ManeuverType.Start
        "SlightLeft" -> ManeuverType.SlightLeft
        "Left" -> ManeuverType.Left
        "SlightRight" -> ManeuverType.SlightRight
        "Right" -> ManeuverType.Right
        "Arrive" -> ManeuverType.Arrive
        else -> ManeuverType.Straight
    }

private fun String.toManeuverTypeFromOpenMap(): ManeuverType =
    when (lowercase()) {
        "slight left" -> ManeuverType.SlightLeft
        "left" -> ManeuverType.Left
        "slight right" -> ManeuverType.SlightRight
        "right" -> ManeuverType.Right
        else -> ManeuverType.Straight
    }

private fun buildInstruction(step: JSONObject, maneuver: JSONObject): String {
    val modifier = maneuver.optString("modifier").ifBlank { "straight" }
    val streetName = step.optString("name").takeIf { it.isNotBlank() }
    val action =
        when (modifier.lowercase()) {
            "left" -> "Turn left"
            "slight left" -> "Keep left"
            "right" -> "Turn right"
            "slight right" -> "Keep right"
            else -> "Continue straight"
        }
    return streetName?.let { "$action on $it" } ?: action
}
