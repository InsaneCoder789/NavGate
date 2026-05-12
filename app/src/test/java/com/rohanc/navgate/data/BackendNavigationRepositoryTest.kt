package com.rohanc.navgate.data

import com.rohanc.navgate.model.Coordinate
import com.rohanc.navgate.model.RouteRequest
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class BackendNavigationRepositoryTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `search uses backend payload when server is available`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                [{"id":"lib-1","title":"Central Library","subtitle":"Quiet study hub","latitude":20.35008,"longitude":85.808021,"type":"Academic"}]
                """.trimIndent(),
            ),
        )
        val repository = BackendNavigationRepository(baseUrl = server.url("/").toString().removeSuffix("/"))

        val places = repository.searchPlaces("library", "Bhubaneswar")

        assertEquals(1, places.size)
        assertEquals("Central Library", places.first().title)
    }

    @Test
    fun `route falls back to fake repository when backend is unavailable`() = runTest {
        val repository = BackendNavigationRepository(baseUrl = "http://127.0.0.1:1")

        val route = repository.fetchRoute(RouteRequest(Coordinate(20.349884, 85.807529), Coordinate(20.350412, 85.808665)))

        assertFalse(route.steps.isEmpty())
        assertEquals(3, route.pathCoordinates.size)
    }
}
