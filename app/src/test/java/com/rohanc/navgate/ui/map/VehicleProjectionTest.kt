package com.rohanc.navgate.ui.map

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class VehicleProjectionTest {
    @Test fun `clockwise bearings put the model nose in the same direction as the map`() {
        val matrix = FloatArray(16)
        for ((yaw, expected) in listOf(0f to (0f to -1f), 90f to (1f to 0f), 180f to (0f to 1f), 270f to (-1f to 0f))) {
            vehicleTransform(yaw, matrix)
            // Every runtime asset points along local -Z.
            assertEquals("nose x at $yaw", expected.first, -matrix[8], 1e-5f)
            assertEquals("nose z at $yaw", expected.second, -matrix[10], 1e-5f)
            assertEquals(1f, matrix[15], 0f)
        }
    }

    @Test fun `different preview sizes keep the ground origin at the requested screen pivot`() {
        for ((width, height, centre) in listOf(Triple(420, 420, .5f), Triple(660, 510, .70f), Triple(252, 180, .68f))) {
            val frame = VehicleViewFrame(0f, 58f, 50f, centre, Color.White)
            val b = vehicleProjectionBounds(width, height, frame)
            val originX = (0 - b[0]) / (b[1] - b[0]) * width
            val originY = (b[3] - 0) / (b[3] - b[2]) * height
            assertEquals(width / 2.0, originX, 1e-5)
            assertEquals(height * centre.toDouble(), originY, 1e-4)
            assertEquals(width / 50.0, b[1] - b[0], 1e-5)
        }
    }
}
