package com.rohanc.navgate.data.traffic

import com.rohanc.navgate.core.GeoPoint
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * TomTom live traffic. Two uses:
 *  - [tileUrl]: coloured flow tiles drawn over the map (every road green, orange or red by how slow it is).
 *  - [flowAt]: current and free-flow speed on the road nearest a point, used to estimate the delay on a route.
 * Needs a key from developer.tomtom.com; with no key every function reports "not available" and the app hides traffic.
 */
class TomTomTraffic(private val apiKey: String, private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()) : TrafficFlowSource {
    val available: Boolean get() = apiKey.isNotBlank()

    /** Raster flow tiles, drawn relative to free-flow speed. The dark style reads better over the dark map. */
    fun tileUrl(dark: Boolean): String? = if (!available) null
        else "https://api.tomtom.com/traffic/map/4/tile/flow/${if (dark) "relative0-dark" else "relative0"}/{z}/{x}/{y}.png?key=$apiKey&tileSize=512"

    override suspend fun flowAt(point: GeoPoint): FlowSample? {
        if (!available) return null
        val url = "https://api.tomtom.com/traffic/services/4/flowSegmentData/absolute/10/json".toHttpUrl().newBuilder()
            .addQueryParameter("key", apiKey).addQueryParameter("point", "${point.latitude},${point.longitude}").addQueryParameter("unit", "KMPH").build()
        return parse(fetch(Request.Builder().url(url).header("Accept", "application/json").build()))
    }

    private suspend fun fetch(request: Request): String? = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWith(Result.success(null)) }
            override fun onResponse(call: Call, response: Response) {
                val body = response.use { r -> if (r.isSuccessful) r.body?.string()?.takeIf { it.length < 100_000 } else null }
                if (continuation.isActive) continuation.resumeWith(Result.success(body))
            }
        })
    }

    internal companion object {
        private val json = Json { ignoreUnknownKeys = true }
        /** `{"flowSegmentData":{"currentSpeed":41,"freeFlowSpeed":70,"roadClosure":false,...}}` */
        fun parse(body: String?): FlowSample? = runCatching {
            val data = json.parseToJsonElement(body ?: return null).jsonObject["flowSegmentData"] as? JsonObject ?: return null
            val current = data["currentSpeed"]?.jsonPrimitive?.double ?: return null
            val free = data["freeFlowSpeed"]?.jsonPrimitive?.double ?: return null
            if (!current.isFinite() || !free.isFinite() || free <= 0) return null
            FlowSample(current, free, data["roadClosure"]?.jsonPrimitive?.boolean == true)
        }.getOrNull()
    }
}
