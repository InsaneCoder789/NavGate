package com.rohanc.navgate.data.traffic

import com.rohanc.navgate.core.GeoPoint
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrafficEstimatorTest {
    private fun north(m: Double) = GeoPoint(19.0 + m / 111_320.0, 72.85)
    private val route = listOf(north(0.0), north(9_000.0))   // 9 km

    @Test fun freeFlowRoadsAddNoDelay() = runBlocking {
        val report = assertNotNull(TrafficEstimator({ FlowSample(50.0, 50.0) }).estimate("a", route))
        assertEquals(TrafficLevel.Clear, report.level)
    }

    @Test fun slowRoadsAddMinutesAndALevel() = runBlocking {
        // 9 km that should take ~10.8 min at 50 km/h but crawls at 15 km/h (36 min): about +25 min.
        val report = assertNotNull(TrafficEstimator({ FlowSample(15.0, 50.0) }).estimate("b", route))
        assertTrue(report.delayMinutes in 22..28, "delay ${report.delayMinutes}")
        assertEquals(TrafficLevel.Heavy, report.level)
        assertTrue(report.summary.startsWith("Heavy traffic"))
    }

    @Test fun missingSamplesAreFilledInFromTheOnesThatLoaded() = runBlocking {
        var calls = 0
        val report = assertNotNull(TrafficEstimator({ if (calls++ % 2 == 0) FlowSample(20.0, 50.0) else null }).estimate("c", route))
        assertTrue(report.delaySeconds > 600)
    }

    @Test fun noDataMeansNoReportAndRepeatsAreCached() = runBlocking {
        assertNull(TrafficEstimator({ null }).estimate("d", route))
        var calls = 0
        val estimator = TrafficEstimator({ calls++; FlowSample(30.0, 50.0) })
        estimator.estimate("e", route, nowMillis = 0); val first = calls
        estimator.estimate("e", route, nowMillis = 1_000)
        assertEquals(first, calls, "second call within 5 minutes uses the cache")
        assertTrue(first <= 8, "stays inside the API budget")
    }

    @Test fun parsesTomTomFlowSegmentJson() {
        val sample = assertNotNull(TomTomTraffic.parse("""{"flowSegmentData":{"frc":"FRC3","currentSpeed":41,"freeFlowSpeed":70,"currentTravelTime":153,"freeFlowTravelTime":90,"confidence":0.59,"roadClosure":true}}"""))
        assertEquals(41.0, sample.currentKmh); assertEquals(70.0, sample.freeFlowKmh); assertTrue(sample.roadClosed)
        assertNull(TomTomTraffic.parse("""{"error":"bad key"}"""))
        assertNull(TomTomTraffic.parse("not json"))
    }
}
