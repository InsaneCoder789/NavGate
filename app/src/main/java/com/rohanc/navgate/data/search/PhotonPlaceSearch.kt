package com.rohanc.navgate.data.search

import com.rohanc.navgate.core.CampusPlace
import com.rohanc.navgate.core.GeoPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

fun interface WorldwidePlaceSearch { suspend fun search(query:String):List<CampusPlace> }

/** Category search near a position, for places outside the offline region. */
interface NearbyPlaceSearch { suspend fun nearby(osmTag:String,word:String,latitude:Double,longitude:Double):List<CampusPlace> }

/** Public Photon geocoding: no bounding box/country filters, no keys, no automatic retries. */
class PhotonPlaceSearch(
    private val client:OkHttpClient=OkHttpClient.Builder().connectTimeout(8,TimeUnit.SECONDS)
        .readTimeout(10,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS).retryOnConnectionFailure(false).build(),
    private val endpoint:HttpUrl="https://photon.komoot.io/api/".toHttpUrl(),
    private val minimumIntervalMillis:Long=1_000,
    private val nowMillis:()->Long={System.nanoTime()/1_000_000},
):WorldwidePlaceSearch,NearbyPlaceSearch {
    private data class Cached(val results:List<CampusPlace>,val expires:Long)
    private val lock=Mutex()
    private val cache=LinkedHashMap<String,Cached>(64,0.75f,true)
    private var lastRequest:Long?=null
    private var retryAfter:Long=0
    init { require(minimumIntervalMillis>=0) }

    override suspend fun search(query:String):List<CampusPlace> {
        val normalized=query.trim().take(256)
        if(normalized.length<3) return emptyList()
        return lock.withLock {
            val key=normalized.lowercase(Locale.ROOT)
            cache[key]?.takeIf{nowMillis()<it.expires}?.let{return@withLock it.results}
            if(nowMillis()<retryAfter) throw IOException("Worldwide search is temporarily rate limited")
            lastRequest?.let{delay((minimumIntervalMillis-(nowMillis()-it)).coerceAtLeast(0))}
            lastRequest=nowMillis()
            val request=Request.Builder().url(endpoint.newBuilder().addQueryParameter("q",normalized)
                .addQueryParameter("limit","12").addQueryParameter("lang","en").build())
                .header("User-Agent","NavGate/1.0 (https://github.com/InsaneCoder789/NavGate)")
                .header("Accept","application/geo+json, application/json").build()
            val result=fetch(request)
            cache[key]=Cached(result,nowMillis()+if(result.isEmpty())60_000 else 15*60_000)
            while(cache.size>64) cache.remove(cache.keys.first())
            result
        }
    }

    /**
     * Places of one OpenStreetMap tag near a position. The position is rounded to about 10 km before it leaves the phone,
     * which is enough to rank nearby results without telling the geocoder where the user is.
     */
    override suspend fun nearby(osmTag:String,word:String,latitude:Double,longitude:Double):List<CampusPlace> {
        val lat=Math.round(latitude*10)/10.0;val lon=Math.round(longitude*10)/10.0
        return lock.withLock {
            val key="nearby|$osmTag|$lat|$lon"
            cache[key]?.takeIf{nowMillis()<it.expires}?.let{return@withLock it.results}
            if(nowMillis()<retryAfter) throw IOException("Worldwide search is temporarily rate limited")
            lastRequest?.let{delay((minimumIntervalMillis-(nowMillis()-it)).coerceAtLeast(0))}
            lastRequest=nowMillis()
            val request=Request.Builder().url(endpoint.newBuilder().addQueryParameter("q",word).addQueryParameter("osm_tag",osmTag)
                .addQueryParameter("lat",lat.toString()).addQueryParameter("lon",lon.toString()).addQueryParameter("limit","30").addQueryParameter("lang","en").build())
                .header("User-Agent","NavGate/1.0 (https://github.com/InsaneCoder789/NavGate)").header("Accept","application/geo+json, application/json").build()
            val result=fetch(request)
            cache[key]=Cached(result,nowMillis()+15*60_000)
            while(cache.size>64) cache.remove(cache.keys.first())
            result
        }
    }

    private suspend fun fetch(request:Request):List<CampusPlace> = suspendCancellableCoroutine { continuation ->
        val call=client.newCall(request)
        continuation.invokeOnCancellation{call.cancel()}
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,error:IOException){if(continuation.isActive)continuation.resumeWith(Result.failure(error))}
            override fun onResponse(call:Call,response:Response){
                val result=runCatching{response.use {
                    if(it.code==429 || it.code==503){
                        val seconds=it.header("Retry-After")?.toLongOrNull()?.coerceIn(60,3600) ?: 60
                        retryAfter=nowMillis()+seconds*1000
                    }
                    if(!it.isSuccessful)throw IOException("Worldwide search HTTP ${it.code}")
                    val body=it.body ?: throw IOException("Empty worldwide search response")
                    if(body.contentLength()>MAX_BYTES)throw IOException("Search response exceeded size limit")
                    val bytes=ByteArrayOutputStream()
                    body.byteStream().use{input ->
                        val buffer=ByteArray(8192)
                        while(true){
                            val count=input.read(buffer,0,minOf(buffer.size,MAX_BYTES-bytes.size()+1))
                            if(count<0)break
                            if(count==0 || bytes.size()+count>MAX_BYTES)throw IOException("Invalid or oversized search response")
                            bytes.write(buffer,0,count)
                        }
                    }
                    parse(bytes.toString(Charsets.UTF_8.name()))
                }}
                if(continuation.isActive)continuation.resumeWith(result)
            }
        })
    }

    companion object {
        private const val MAX_BYTES=1024*1024
        internal fun parse(payload:String):List<CampusPlace> {
            val root=Json.parseToJsonElement(payload) as? JsonObject ?: throw IOException("Invalid geocoding response")
            val features=root["features"] as? JsonArray ?: throw IOException("Missing geocoding results")
            return features.mapNotNull{feature ->
                val item=feature as? JsonObject ?: return@mapNotNull null
                val properties=item["properties"] as? JsonObject ?: return@mapNotNull null
                fun text(key:String)=(properties[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                val geometry=item["geometry"] as? JsonObject ?: return@mapNotNull null
                if((geometry["type"] as? JsonPrimitive)?.content!="Point")return@mapNotNull null
                val coordinates=geometry["coordinates"] as? JsonArray ?: return@mapNotNull null
                if(coordinates.size!=2)return@mapNotNull null
                val lon=(coordinates[0] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                val lat=(coordinates[1] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                val point=GeoPoint(lat,lon).takeIf{it.valid} ?: return@mapNotNull null
                val type=text("osm_type").uppercase(Locale.ROOT)
                val kind=when(type){"N"->"node";"W"->"way";"R"->"relation";else->return@mapNotNull null}
                val id=(properties["osm_id"] as? JsonPrimitive)?.longOrNull?.takeIf{it>0} ?: return@mapNotNull null
                val street=listOf(text("housenumber"),text("street")).filter{it.isNotBlank()}.joinToString(" ")
                val title=text("name").ifBlank{street}.ifBlank{ return@mapNotNull null }
                val address=listOf(street,text("district"),text("city"),text("state"),text("country"))
                    .filter{it.isNotBlank() && !it.equals(title,true)}.distinct().joinToString(", ")
                CampusPlace(id="osm:${type.lowercase(Locale.ROOT)}:$id",title=title,subtitle=address.ifBlank{"OpenStreetMap place"},
                    category=text("osm_value"),city=text("city").ifBlank{text("state")}.ifBlank{text("country")}.ifBlank{"Worldwide"},
                    point=point,sourceUrl="https://www.openstreetmap.org/$kind/$id")
            }.distinctBy{it.id}.take(12)
        }
    }
}
