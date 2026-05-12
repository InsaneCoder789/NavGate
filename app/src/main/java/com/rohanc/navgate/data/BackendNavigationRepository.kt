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
                val request = Request.Builder().url("$baseUrl/places?query=$encoded").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("search failed with ${response.code}")
                    val body = response.body?.string().orEmpty()
                    parsePlaces(JSONArray(body))
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
                        .put("profile", request.profile.name)
                val httpRequest =
                    Request.Builder()
                        .url("$baseUrl/route")
                        .post(payload.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                client.newCall(httpRequest).execute().use { response ->
                    if (!response.isSuccessful) error("route failed with ${response.code}")
                    val body = response.body?.string().orEmpty()
                    parseRoute(JSONObject(body))
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
                        type = item.getString("type").toPlaceType(),
                    ),
                )
            }
        }

    private fun parseRoute(json: JSONObject): RouteResponse {
        val coordinatesJson = json.getJSONArray("pathCoordinates")
        val stepsJson = json.getJSONArray("steps")
        val coordinates =
            buildList {
                for (index in 0 until coordinatesJson.length()) {
                    val coordinate = coordinatesJson.getJSONObject(index)
                    add(Coordinate(coordinate.getDouble("latitude"), coordinate.getDouble("longitude")))
                }
            }
        val steps =
            buildList {
                for (index in 0 until stepsJson.length()) {
                    val step = stepsJson.getJSONObject(index)
                    add(
                        RouteStep(
                            instruction = step.getString("instruction"),
                            distanceMeters = step.getDouble("distanceMeters"),
                            targetLatitude = step.getDouble("targetLatitude"),
                            targetLongitude = step.getDouble("targetLongitude"),
                            maneuverType = step.getString("maneuverType").toManeuverType(),
                        ),
                    )
                }
            }
        return RouteResponse(
            distanceMeters = json.getDouble("distanceMeters"),
            durationSeconds = json.getDouble("durationSeconds"),
            pathCoordinates = coordinates,
            steps = steps,
        )
    }

    companion object {
        private fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(1500, TimeUnit.MILLISECONDS)
                .readTimeout(1500, TimeUnit.MILLISECONDS)
                .build()
    }
}

private fun String.toPlaceType(): PlaceType =
    when (this) {
        "Gate" -> PlaceType.Gate
        "Academic" -> PlaceType.Academic
        "Sports" -> PlaceType.Sports
        "Residential" -> PlaceType.Residential
        "Food" -> PlaceType.Food
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
