package com.rohanc.navgate.data.search

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PhotonPlaceSearchTest {
    private val paris="""{"type":"FeatureCollection","features":[{"geometry":{"type":"Point","coordinates":[2.2945,48.8584]},"properties":{"name":"Eiffel Tower","city":"Paris","country":"France","osm_type":"W","osm_id":5013364,"osm_value":"attraction"}}]}"""
    @Test fun `global request has no campus bounds and parses longitude latitude correctly with cached repeats`()=runBlocking {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setBody(paris))
            val search=PhotonPlaceSearch(endpoint=server.url("/api/"),minimumIntervalMillis=0)
            val place=search.search("Eiffel Tower, Paris").single()
            assertEquals(48.8584,place.point!!.latitude,0.00001);assertEquals(2.2945,place.point!!.longitude,0.00001)
            assertEquals("osm:w:5013364",place.id);assertEquals("Paris",place.city)
            val request=server.takeRequest()
            assertEquals("Eiffel Tower, Paris",request.requestUrl!!.queryParameter("q"))
            assertNull(request.requestUrl!!.queryParameter("bbox"));assertNull(request.requestUrl!!.queryParameter("countrycode"))
            assertTrue(request.getHeader("User-Agent")!!.startsWith("NavGate/"))
            assertEquals(place,search.search("  eiffel tower, paris ").single())
            assertEquals(1,server.requestCount)
            assertTrue(search.search("a").isEmpty());assertEquals(1,server.requestCount)
        } finally {server.shutdown()}
    }
    @Test fun `invalid and duplicated features never become fake coordinates`() {
        val invalid="""{"geometry":{"type":"Point","coordinates":[200,95]},"properties":{"name":"Bad","osm_type":"N","osm_id":1}}"""
        val valid="""{"geometry":{"type":"Point","coordinates":[139.7,35.6]},"properties":{"name":"Tokyo","country":"Japan","osm_type":"N","osm_id":2}}"""
        val parsed=PhotonPlaceSearch.parse("""{"features":[$invalid,$valid,$valid,{}]}""")
        assertEquals(1,parsed.size);assertEquals("Tokyo",parsed.single().title)
        assertThrows(IOException::class.java){PhotonPlaceSearch.parse("{}")}
    }
    @Test fun `provider rate limiting backs off without caching a false empty response`()=runBlocking {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After","120"))
            val search=PhotonPlaceSearch(endpoint=server.url("/api/"),minimumIntervalMillis=0)
            repeat(2){try{search.search("Berlin");fail("Expected rate limiting")}catch(_:IOException){}}
            assertEquals(1,server.requestCount)
        } finally {server.shutdown()}
    }
    @Test fun `cancelling an in flight search cancels the HTTP call`()=runBlocking {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val search=PhotonPlaceSearch(endpoint=server.url("/api/"),minimumIntervalMillis=0)
            val pending=async(Dispatchers.Default){search.search("London")}
            assertNotNull(withContext(Dispatchers.IO){server.takeRequest(3,java.util.concurrent.TimeUnit.SECONDS)})
            withTimeout(2_000){pending.cancelAndJoin()}
            assertTrue(pending.isCancelled)
        } finally {server.shutdown()}
    }
}
