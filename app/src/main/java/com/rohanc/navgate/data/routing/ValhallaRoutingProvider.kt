package com.rohanc.navgate.data.routing

import com.rohanc.navgate.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs

interface RoadRouteCache {
    suspend fun read(key:String):CampusRoute?
    suspend fun write(key:String,route:CampusRoute)
}

/** Real Valhalla road/path routing. Requests are single-flight, cancellable and never fake a route. */
class ValhallaRoutingProvider(
    private val endpoint:HttpUrl="https://valhalla1.openstreetmap.de/route".toHttpUrl(),
    private val client:OkHttpClient=OkHttpClient.Builder().connectTimeout(8,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS)
        .callTimeout(25,TimeUnit.SECONDS).retryOnConnectionFailure(false).build(),
    private val cache:RoadRouteCache?=null,
    private val epochMillis:()->Long=System::currentTimeMillis,
    private val elapsedMillis:()->Long={System.nanoTime()/1_000_000},
    private val minimumIntervalMillis:Long=1_000,
):RoutingProvider {
    // Guards only the request-slot reservation; network I/O never runs under it, so a slow call cannot block a reroute.
    private val gate=Mutex()
    private var nextSlot:Long?=null
    @Volatile private var retryAt=0L
    init { require(minimumIntervalMillis>=0) }
    override suspend fun plan(request:PlanRequest,pinnedVersion:String?):RouteResult {
        if(request.profile !in setOf("walking","driving"))return RouteResult.Unavailable(RouteFailure.UnsupportedProfile,"Choose walking or car")
        val destination=request.destinationPoint
        if(!request.origin.valid || destination?.valid!=true)return RouteResult.Unavailable(RouteFailure.InvalidCoordinate,"Choose a start and destination with valid map coordinates")
        if(pinnedVersion!=null)return RouteResult.Unavailable(RouteFailure.InvalidGraph,"Road routes cannot use a campus graph version")
        return try {
            run {
                val cacheKey=key(request)
                val cached=try{cache?.read(cacheKey)}catch(c:CancellationException){throw c}catch(_:Exception){null}
                val ttl=if(request.profile=="driving")5*60_000L else 15*60_000L
                if(cached!=null && cached.profile==request.profile && cached.provider==endpoint.host && cached.destinationPlaceId==request.destinationPlaceId &&
                    cached.destinationPoint==destination && cached.regionId==request.regionId && cached.geometry.firstOrNull()?.distanceTo(request.origin)?.let{it<=100}==true &&
                    cached.fetchedAtEpochMillis?.let{epochMillis()-it in 0 until ttl}==true && RouteIntegrity.shapeErrors(cached).isEmpty()) {
                    return@run RouteResult.Ready(cached.copy(id=UUID.randomUUID().toString(),requestId=request.requestId,fromCache=true,destinationTitle=request.destinationTitle))
                }
                delay(reserveSlot())
                val payload=payload(request)
                val http=Request.Builder().url(endpoint.newBuilder().addQueryParameter("json",payload).build())
                    .header("User-Agent","NavGate/1.0 (https://github.com/InsaneCoder789/NavGate)")
                    .header("X-Client-Id","github.com/InsaneCoder789/NavGate").header("Accept","application/json").build()
                val result=parse(fetch(http),request,endpoint.host,epochMillis())
                if(result is RouteResult.Ready)try{cache?.write(cacheKey,result.route)}catch(c:CancellationException){throw c}catch(_:Exception){/* A cache failure must not discard real directions. */}
                result
            }
        }catch(cancelled:CancellationException){throw cancelled}
        catch(error:Exception){RouteResult.Unavailable(RouteFailure.NoConnection,error.message?.takeIf{it.startsWith("Routing provider")} ?: "Road directions unavailable. Check your connection and retry; no route was substituted.")}
    }

    /** Spaces requests by the minimum interval without serializing their network time. Returns the wait before sending. */
    private suspend fun reserveSlot():Long=gate.withLock {
        val now=elapsedMillis()
        if(now<retryAt)throw IOException("Routing provider is rate limited. Wait and retry.")
        val start=maxOf(now,nextSlot ?: now)
        nextSlot=start+minimumIntervalMillis
        start-now
    }

    private fun key(r:PlanRequest):String {
        val raw=listOf(endpoint.toString(),// Origin is bucketed to ~100 m: reads still require the cached route to start within 100 m of the pin.
        Math.round(r.origin.latitude*1000),Math.round(r.origin.longitude*1000),r.destinationPoint?.latitude,r.destinationPoint?.longitude,r.profile,r.destinationPlaceId,r.regionId).joinToString("|")
        return java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString(""){"%02x".format(it)}
    }
    private suspend fun fetch(request:Request):String=suspendCancellableCoroutine { continuation ->
        val call=client.newCall(request)
        continuation.invokeOnCancellation{call.cancel()}
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:IOException){if(continuation.isActive)continuation.resumeWith(Result.failure(e))}
            override fun onResponse(call:Call,response:Response){
                val result=runCatching { response.use { r ->
                    if(r.code==429 || r.code==503)retryAt=elapsedMillis()+(r.header("Retry-After")?.toLongOrNull()?.coerceIn(1,3600) ?: 60)*1000
                    if(!r.isSuccessful && r.code !in listOf(400,404))throw IOException("Routing provider HTTP ${r.code}; retry later.")
                    val body=r.body ?: throw IOException("Empty routing response")
                    require(body.contentLength()<=MAX_BYTES)
                    val bytes=ByteArrayOutputStream()
                    body.byteStream().use { input -> val buffer=ByteArray(8192)
                        while(true){val n=input.read(buffer);if(n<0)break;require(bytes.size()+n<=MAX_BYTES);bytes.write(buffer,0,n)}
                    }
                    bytes.toString(Charsets.UTF_8.name())
                } }
                if(continuation.isActive)continuation.resumeWith(result)
            }
        })
    }

    companion object {
        private const val MAX_BYTES=4*1024*1024
        internal fun payload(request:PlanRequest):String=buildJsonObject {
            val costing=if(request.profile=="driving")"auto"else"pedestrian"
            put("costing",costing);put("units","kilometers");put("language","en-US")
            putJsonObject("costing_options"){putJsonObject(costing){put("use_ferry",0)}}
            putJsonArray("locations") { for(point in listOf(request.origin,requireNotNull(request.destinationPoint))) add(buildJsonObject {
                put("lat",point.latitude);put("lon",point.longitude);put("search_cutoff",100);put("radius",25)
                // Do not snap a driver standing by a pier onto a ferry instead of a nearby road.
                if(request.profile=="driving")putJsonObject("search_filter"){put("exclude_ferry",true)}
            }) }
        }.toString()

        internal fun parse(payload:String,request:PlanRequest,provider:String,now:Long):RouteResult {
            try {
                val root=Json.parseToJsonElement(payload).jsonObject
                if(root["error_code"]!=null)return RouteResult.Unavailable(RouteFailure.NoConnection,"No ${request.profile} route found near these pins. Choose a nearby mapped road or path and retry.")
                val trip=root["trip"]?.jsonObject ?: error("Missing trip")
                require(trip["status"]?.jsonPrimitive?.int==0)
                require(trip["units"]?.jsonPrimitive?.content=="kilometers")
                val legs=trip["legs"]!!.jsonArray;require(legs.size==1)
                val leg=legs.single().jsonObject
                val summary=trip["summary"]!!.jsonObject
                val distance=summary["length"]!!.jsonPrimitive.double*1000
                val seconds=summary["time"]!!.jsonPrimitive.double
                require(distance.isFinite() && distance>0 && seconds.isFinite() && seconds>0)
                val shape=decodePolyline6(leg["shape"]!!.jsonPrimitive.content)
                require(shape.size>=2 && shape.first().distanceTo(request.origin)<=100)
                val destination=requireNotNull(request.destinationPoint)
                require(shape.last().distanceTo(destination)<=100)
                val lengths=DoubleArray(shape.size)
                for(i in 1 until shape.size)lengths[i]=lengths[i-1]+shape[i-1].distanceTo(shape[i])
                val total=lengths.last();require(total>0)
                require(abs(total-distance)<=maxOf(30.0,distance*.05))
                val raw=leg["maneuvers"]!!.jsonArray.map{it.jsonObject};require(raw.isNotEmpty())
                val expectedMode=if(request.profile=="driving")"drive"else"pedestrian"
                val times=DoubleArray(shape.size){Double.NaN};times[0]=0.0
                var elapsed=0.0;var lastEnd=0
                val maneuvers=raw.mapIndexed { index,m ->
                    require(m["travel_mode"]?.jsonPrimitive?.content==expectedMode)
                    val start=m["begin_shape_index"]!!.jsonPrimitive.int
                    val end=m["end_shape_index"]!!.jsonPrimitive.int
                    require(start in shape.indices && end in start..shape.lastIndex && start==lastEnd)
                    lastEnd=end
                    val duration=m["time"]!!.jsonPrimitive.double;require(duration.isFinite() && duration>=0)
                    val instruction=m["instruction"]!!.jsonPrimitive.content.trim();require(instruction.isNotEmpty())
                    val type=m["type"]!!.jsonPrimitive.int
                    val arrival=type in 4..6
                    if(index==raw.lastIndex)require(arrival && start==shape.lastIndex)
                    if(arrival)require(index==raw.lastIndex)
                    val length=lengths[end]-lengths[start]
                    for(i in start..end)times[i]=elapsed+if(length>0)duration*(lengths[i]-lengths[start])/length else 0.0
                    elapsed+=duration
                    Maneuver(instruction,shape[start],(lengths[start]/total*distance).coerceIn(0.0,distance),maneuverKind(type))
                }
                require(lastEnd==shape.lastIndex && elapsed>0 && abs(elapsed-seconds)<=maxOf(2.0,seconds*.01))
                val normalized=times.map { require(it.isFinite());it/elapsed*seconds }
                val warnings=buildList {
                    add("Valhalla · © OpenStreetMap contributors. Estimated time; live traffic is not included.")
                    if(summary["has_ferry"]?.jsonPrimitive?.booleanOrNull==true)add("This route includes a ferry; check its operating conditions.")
                    if(summary["has_toll"]?.jsonPrimitive?.booleanOrNull==true)add("This route includes toll roads.")
                    val offset=shape.first().distanceTo(request.origin)
                    if(offset>20)add("The mapped route starts ${offset.toInt()} m from your pin. Move to that start before beginning guidance.")
                    if(shape.last().distanceTo(destination)>20)add("Arrival is at the mapped road/path endpoint near the destination pin, not a verified building entrance.")
                }
                val route=CampusRoute(id=UUID.randomUUID().toString(),requestId=request.requestId,regionId=request.regionId,
                    graphVersion="valhalla-response-v1",destinationPlaceId=request.destinationPlaceId,entranceId="",geometry=shape,
                    edgeIds=emptyList(),distanceMeters=distance,estimatedSeconds=seconds,maneuvers=maneuvers,reviewedAt="",
                    source="valhalla",profile=request.profile,provider=provider,fetchedAtEpochMillis=now,destinationPoint=destination,
                    destinationTitle=request.destinationTitle,shapeElapsedSeconds=normalized,warnings=warnings)
                require(RouteIntegrity.shapeErrors(route).isEmpty())
                return RouteResult.Ready(route)
            }catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){return RouteResult.Unavailable(RouteFailure.InvalidGraph,"Routing response could not be validated. Choose pins near mapped roads/paths and retry.")}
        }

        /** Valhalla maneuver types: 1-3 start, 4-6 destination, 9-11 right, 12-13 U-turn, 14-16 left, 17-25 ramps/merges, 26-27 roundabout. */
        internal fun maneuverKind(type:Int):String=when(type){
            in 1..3->"Start";in 4..6->"Arrive";9->"SlightRight";10,11->"Right";12,13->"UTurn";14,15->"Left";16->"SlightLeft"
            18,20,23->"SlightRight";19,21,24->"SlightLeft";26,27->"Roundabout";else->"Straight"
        }

        internal fun decodePolyline6(encoded:String):List<GeoPoint> {
            require(encoded.length<=MAX_BYTES)
            var index=0;var latitude=0L;var longitude=0L
            fun delta():Long {
                var value=0L;var shift=0
                while(true){require(index<encoded.length && shift<=35);val b=encoded[index++].code-63;require(b in 0..63)
                    value=value or ((b and 31).toLong() shl shift);shift+=5
                    if(b<32)break
                }
                return if(value and 1L!=0L)(value shr 1).inv() else value shr 1
            }
            return buildList {
                while(index<encoded.length){require(size<200_000);latitude+=delta();longitude+=delta()
                    val point=GeoPoint(latitude/1_000_000.0,longitude/1_000_000.0);require(point.valid);add(point)
                }
            }
        }
    }
}
