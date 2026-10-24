package com.rohanc.navgate.data.routing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rohanc.navgate.core.*
import com.rohanc.navgate.data.routing.offline.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real BRouter on a real phone. Downloads the Mumbai routing tile (about 20 MB) the first time. */
@RunWith(AndroidJUnit4::class)
class OfflineRoutingDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val request = PlanRequest(1, "mumbai", GeoPoint(18.9256, 72.8242), "dest", profile = "driving", destinationPoint = GeoPoint(19.0596, 72.8295), destinationTitle = "Bandra", routingSource = "valhalla")

    private fun ensureTile(store: RoutingDataStore) {
        if (store.isInstalled("E70_N15")) return
        store.download("E70_N15")
        val deadline = System.currentTimeMillis() + 10 * 60_000
        while (!store.isInstalled("E70_N15") && System.currentTimeMillis() < deadline) {
            (store.progress.value["E70_N15"] as? RoutingDataStore.Progress.Failed)?.let { fail("download failed: ${it.reason}") }
            Thread.sleep(500)
        }
        assertTrue("tile installed", store.isInstalled("E70_N15"))
    }

    @Test fun mumbaiDriveAndWalkRouteOnThePhone() = runBlocking {
        val store = RoutingDataStore(context); ensureTile(store)
        val provider = OfflineRoutingProvider.of(store)
        val drive = provider.plan(request) as RouteResult.Ready
        println("OFFLINE drive ${drive.route.distanceMeters.toInt()} m ${drive.route.estimatedSeconds.toInt()} s, ${drive.route.maneuvers.size} maneuvers")
        assertTrue(drive.route.distanceMeters in 14_000.0..30_000.0)
        assertEquals(emptyList<String>(), RouteIntegrity.shapeErrors(drive.route))
        assertTrue(drive.route.maneuvers.size >= 5)
        val walk = provider.plan(request.copy(profile = "walking", origin = GeoPoint(18.9440, 72.8230), destinationPoint = GeoPoint(18.9600, 72.8100))) as RouteResult.Ready
        assertTrue(walk.route.distanceMeters in 1_500.0..6_000.0)
    }

    @Test fun anAreaWithoutTilesIsRefusedWithAHelpfulMessage() = runBlocking {
        val provider = OfflineRoutingProvider.of(RoutingDataStore(context))
        val r = provider.plan(request.copy(origin = GeoPoint(51.5, -0.12), destinationPoint = GeoPoint(51.6, -0.1)))
        assertTrue(r is RouteResult.Unavailable && r.reason == RouteFailure.MissingRegion)
    }
}
