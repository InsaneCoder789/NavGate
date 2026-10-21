package com.rohanc.navgate.core

import org.junit.Assert.*
import org.junit.Test

class ManeuverKindsTest {
    @Test fun `turn sharpness comes from the measured angle`() {
        assertEquals("Straight", ManeuverKinds.fromAngle(6.0))
        assertEquals("SlightRight", ManeuverKinds.fromAngle(30.0))
        assertEquals("Left", ManeuverKinds.fromAngle(-90.0))
        assertEquals("SharpRight", ManeuverKinds.fromAngle(140.0))
        assertEquals("UTurn", ManeuverKinds.fromAngle(-175.0))
    }

    @Test fun `a provider label that contradicts the geometry is corrected`() {
        // The provider says "right" but the road clearly turns left: the driver must be told left.
        assertEquals("Left", ManeuverKinds.forValhalla(10, -88.0))
        assertEquals("SharpRight", ManeuverKinds.forValhalla(10, 140.0))      // "Turn right" of 140 degrees is a sharp turn
        assertEquals("SlightRight", ManeuverKinds.forValhalla(10, 13.5))   // a real Mumbai "Turn right" that only bears 13 degrees
        assertEquals("KeepLeft", ManeuverKinds.forValhalla(23, -40.0))        // "keep right" but the road bears left
        assertEquals("KeepRight", ManeuverKinds.forValhalla(23, -5.0))        // near straight: provider keeps its meaning
        assertEquals("UTurn", ManeuverKinds.forValhalla(13, null))
    }

    @Test fun `road types are recognised`() {
        assertEquals("Roundabout", ManeuverKinds.forValhalla(26, -47.0))
        assertEquals("RoundaboutExit", ManeuverKinds.forValhalla(27, -85.0))
        assertEquals("Merge", ManeuverKinds.forValhalla(37, null))
        assertEquals("KeepRight", ManeuverKinds.forValhalla(20, 20.0))        // highway exit right
        assertEquals("KeepLeft", ManeuverKinds.forValhalla(24, -20.0))
        assertEquals("Straight", ManeuverKinds.forValhalla(8, 3.0))
    }

    @Test fun `ordinals and roundabout exits read naturally`() {
        assertEquals(listOf("1st", "2nd", "3rd", "4th", "11th", "12th", "21st"), listOf(1, 2, 3, 4, 11, 12, 21).map { ManeuverKinds.ordinal(it) })
        assertEquals("straight on", ManeuverKinds.roundaboutExitDirection(5.0))
        assertEquals("left", ManeuverKinds.roundaboutExitDirection(-80.0))
        assertEquals("back the way you came", ManeuverKinds.roundaboutExitDirection(178.0))
    }

    @Test fun `turn angle is measured from the shape`() {
        val origin = GeoPoint(19.0, 72.85)
        fun at(e: Double, n: Double) = GeoPoint(origin.latitude + n / 111_320.0, origin.longitude + e / (111_320.0 * Math.cos(Math.toRadians(origin.latitude))))
        val rightAngle = listOf(at(0.0, 0.0), at(0.0, 20.0), at(0.0, 40.0), at(20.0, 40.0), at(40.0, 40.0))   // north then east: a right turn
        val angle = ManeuverKinds.turnAngle(rightAngle, 2)!!
        assertTrue("angle $angle", angle in 60.0..120.0)
        val leftAngle = listOf(at(0.0, 0.0), at(0.0, 20.0), at(0.0, 40.0), at(-20.0, 40.0), at(-40.0, 40.0))
        assertTrue(ManeuverKinds.turnAngle(leftAngle, 2)!! < -60.0)
    }
}
