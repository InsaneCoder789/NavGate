package com.rohanc.navgate.ui.ar

import com.rohanc.navgate.model.*
import com.rohanc.navgate.navigation.*
import org.junit.Assert.*
import org.junit.Test

class AlignmentGuideTest {
    private val origin=Coordinate(20.0,85.0)
    private val target=Coordinate(20.001,85.0)
    private val route=RouteResponse(111.0,80.0,listOf(origin,target),listOf(RouteStep("North",111.0,target.latitude,target.longitude,ManeuverType.Straight)))
    private val snapshot=NavigationSnapshot(origin=origin,userLocation=origin,route=route,
        locationConfidence=SignalConfidence(GuidanceConfidence.Medium,"accepted"))
    @Test fun `aligned heading reports aligned state`() {
        assertEquals(AlignmentLevel.Aligned,alignmentStatus(snapshot,origin.bearingTo(target)).level)
    }
    @Test fun `large heading gap reports recover state`() {
        assertEquals(AlignmentLevel.Recover,alignmentStatus(snapshot,270.0).level)
    }
    @Test fun `orientation cannot substitute for a usable location`() {
        assertEquals(AlignmentLevel.Aligned,alignmentStatus(snapshot,0.0).level)
        assertEquals(AlignmentLevel.Recover,alignmentStatus(snapshot.copy(userLocation=null),0.0).level)
        assertEquals(AlignmentLevel.Recover,alignmentStatus(snapshot.copy(locationConfidence=SignalConfidence(GuidanceConfidence.Low,"stale")),0.0).level)
        assertEquals(AlignmentLevel.Recover,alignmentStatus(snapshot.copy(isRerouting=true),0.0).level)
        assertEquals(AlignmentLevel.Recover,alignmentStatus(snapshot,Double.NaN).level)
        assertEquals(AlignmentLevel.Recover,alignmentStatus(snapshot.copy(userLocation=target),0.0).level)
    }
}
