package com.rohanc.navgate.core

import org.junit.Assert.*
import org.junit.Test

class PathProjectionTest {
    @Test fun `projection uses path segments not sparse vertices`() {
        val path = listOf(GeoPoint(20.0, 85.0), GeoPoint(20.0, 85.001))
        val mid = GeoPoint(20.0, 85.0005)
        val projection = requireNotNull(projectPath(mid, path))
        assertEquals(0.0, projection.distanceMeters, 0.01)
        assertEquals(path.lengthMeters() / 2, projection.alongMeters, 0.01)
    }
}
