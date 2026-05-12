package com.rohanc.navgate.data

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
    private val baseUrl: String = BuildConfig.NAVGATE_BACKEND_URL,
    private val client: OkHttpClient = defaultHttpClient(),
    private val fallback: NavigationRepository = FakeCampusRepository(),
) : NavigationRepository {
    override suspend fun searchPlaces(query: String): List<PlaceSearchResult> =
        runCatching {
            withContext(Dispatchers.IO) {
                val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
                val request = Request.Builder().url("$baseUrl/places?query=$encoded&city=Bhubaneswar").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("search failed with ${response.code}")
                    parsePlaces(JSONArray(response.body?.string().orEmpty()))
                }
            }
        }.getOrElse {
            fallback.searchPlaces(query)
        }

    override suspend fun fetchRoute(request: RouteRequest): RouteResponse =
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
