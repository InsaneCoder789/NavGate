package com.rohanc.navgate.data.routing

import com.rohanc.navgate.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class ValhallaRoutingProviderTest {
    private fun request(mode:String="driving")=PlanRequest(9,"mumbai",GeoPoint(18.92297,72.83329),"marine-drive",profile=mode,
        destinationPoint=GeoPoint(18.9341,72.82365),destinationTitle="Marine Drive")
    private fun fixture(mode:String)=javaClass.classLoader!!.getResourceAsStream("routing/mumbai-$mode.json")!!.bufferedReader().readText()
    @Test fun `real Mumbai responses preserve car and pedestrian geometry distance time and maneuvers`() {
        for(mode in listOf("walking","driving")) {
            val result=ValhallaRoutingProvider.parse(fixture(mode),request(mode),"test-provider",1000)
            assertTrue("$mode: $result",result is RouteResult.Ready)
            val route=(result as RouteResult.Ready).route
            assertEquals(mode,route.profile);assertEquals("valhalla",route.source)
            assertEquals(if(mode=="driving")2124.0 else 2042.0,route.distanceMeters,0.01)
            assertEquals(if(mode=="driving")454.199 else 1485.122,route.estimatedSeconds,0.001)
            assertEquals(route.geometry.size,route.shapeElapsedSeconds.size)
            assertTrue(route.remainingSecondsAt(route.distanceMeters/2) in 0.0..route.estimatedSeconds)
            assertTrue(RouteIntegrity.shapeErrors(route).isEmpty())
        }
    }
    @Test fun `request selects separate costing modes and does not snap cars to ferries`() {
        for(mode in listOf("walking","driving")) {
            val root=Json.parseToJsonElement(ValhallaRoutingProvider.payload(request(mode))).jsonObject
            assertEquals(if(mode=="driving")"auto"else"pedestrian",root["costing"]!!.jsonPrimitive.content)
            assertEquals(100,root["locations"]!!.jsonArray[0].jsonObject["search_cutoff"]!!.jsonPrimitive.int)
        }
    }
    @Test fun `provider mode mismatch corrupt geometry and missing arrival are rejected`() {
        assertTrue(ValhallaRoutingProvider.parse(fixture("driving"),request("walking"),"test",1000) is RouteResult.Unavailable)
        for(payload in listOf("{}","{\"error_code\":442}",fixture("driving").replace("\"length\": 2.124","\"length\": -1"),fixture("driving").replace("\"type\": 4","\"type\": 99"))) {
            if(payload==fixture("driving"))continue
            assertTrue(ValhallaRoutingProvider.parse(payload,request(),"test",1000) is RouteResult.Unavailable)
        }
        assertThrows(IllegalArgumentException::class.java){ValhallaRoutingProvider.decodePolyline6("~")}
    }
    @Test fun `persistent cache distinguishes modes expires and is reused across provider instances`()=runBlocking {
        val server=MockWebServer();server.start()
        val entries=mutableMapOf<String,CampusRoute>()
        val cache=object:RoadRouteCache {
            override suspend fun read(key:String)=entries[key]
            override suspend fun write(key:String,route:CampusRoute){entries[key]=route}
        }
        var time=1000L
        fun provider()=ValhallaRoutingProvider(server.url("/route"),cache=cache,epochMillis={time},minimumIntervalMillis=0)
        try {
            server.enqueue(MockResponse().setBody(fixture("driving")))
            val first=provider().plan(request()) as RouteResult.Ready
            val second=provider().plan(request().copy(requestId=10)) as RouteResult.Ready
            assertEquals(10L,second.route.requestId);assertTrue(second.route.fromCache);assertFalse(first.route.fromCache);assertEquals(1,server.requestCount)
            server.enqueue(MockResponse().setBody(fixture("walking")))
            assertTrue(provider().plan(request("walking")) is RouteResult.Ready);assertEquals(2,server.requestCount)
            time+=300_000
            server.enqueue(MockResponse().setBody(fixture("driving")))
            assertFalse((provider().plan(request()) as RouteResult.Ready).route.fromCache);assertEquals(3,server.requestCount)
        }finally{server.shutdown()}
    }
    @Test fun `HTTP failure and rate limit never produce synthetic directions`()=runBlocking {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After","120"))
            val provider=ValhallaRoutingProvider(server.url("/route"),minimumIntervalMillis=0)
            repeat(2){assertTrue(provider.plan(request()) is RouteResult.Unavailable)}
            assertEquals(1,server.requestCount)
        }finally{server.shutdown()}
    }
    @Test fun `a slow request does not block a concurrent request`()=runBlocking<Unit> {
        val body=fixture("driving")
        val first=java.util.concurrent.atomic.AtomicBoolean(true)
        val server=MockWebServer()
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest)=MockResponse().setBody(body)
            .also{if(first.getAndSet(false))it.setBodyDelay(1500,TimeUnit.MILLISECONDS)}}
        server.start()
        try {
            val provider=ValhallaRoutingProvider(server.url("/route"),minimumIntervalMillis=0)
            val slow=async(Dispatchers.Default){provider.plan(request())}
            delay(200)
            val started=System.nanoTime()
            val fast=withContext(Dispatchers.Default){provider.plan(request().copy(requestId=10))}
            val elapsedMs=(System.nanoTime()-started)/1_000_000
            assertTrue("$fast",fast is RouteResult.Ready)
            assertTrue("second request waited ${elapsedMs}ms behind the slow one",elapsedMs<1000)
            slow.await()
        } finally { server.shutdown() }
    }
    @Test fun `short Retry-After is honored instead of forcing a minute lockout`()=runBlocking<Unit> {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After","1"))
            server.enqueue(MockResponse().setBody(fixture("driving")))
            var now=0L
            val provider=ValhallaRoutingProvider(server.url("/route"),minimumIntervalMillis=0,elapsedMillis={now})
            assertTrue(provider.plan(request()) is RouteResult.Unavailable)
            assertTrue(provider.plan(request()) is RouteResult.Unavailable) // still inside the window; no HTTP sent
            assertEquals(1,server.requestCount)
            now=1_500
            assertTrue(provider.plan(request()) is RouteResult.Ready)
        } finally { server.shutdown() }
    }
    @Test fun `cancellation propagates and cancels provider HTTP`()=runBlocking {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val pending=async(Dispatchers.Default){ValhallaRoutingProvider(server.url("/route"),minimumIntervalMillis=0).plan(request())}
            assertNotNull(withContext(Dispatchers.IO){server.takeRequest(3,TimeUnit.SECONDS)})
            withTimeout(2000){pending.cancelAndJoin()};assertTrue(pending.isCancelled)
        }finally{server.shutdown()}
    }
}
