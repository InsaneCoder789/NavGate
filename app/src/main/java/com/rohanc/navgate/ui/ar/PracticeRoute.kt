package com.rohanc.navgate.ui.ar

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.GeomagneticField
import com.rohanc.navgate.core.CampusRoute
import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.core.Maneuver
import com.rohanc.navgate.core.lengthMeters
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.cos
import kotlin.math.sin

/**
 * A short made-up walking route that starts where you stand and heads the way the phone faces, with one right turn. It lets you try AR
 * anywhere, indoors or in a car park, without being on a real route. It is never used for real guidance.
 */
object PracticeRoute {
    const val PLACE_ID = "practice"

    fun build(start: GeoPoint, headingDegrees: Double, nowMillis: Long): CampusRoute {
        val legMeters = 45.0
        fun move(from: GeoPoint, bearing: Double, meters: Double): GeoPoint {
            val b = Math.toRadians(bearing)
            return GeoPoint(from.latitude + meters * cos(b) / 111_320.0, from.longitude + meters * sin(b) / (111_320.0 * cos(Math.toRadians(from.latitude))))
        }
        val corner = move(start, headingDegrees, legMeters)
        val end = move(corner, headingDegrees + 90.0, legMeters)
        val points = ArrayList<GeoPoint>()
        for (i in 0..9) points += move(start, headingDegrees, legMeters * i / 9.0)
        for (i in 1..9) points += move(corner, headingDegrees + 90.0, legMeters * i / 9.0)
        val length = points.lengthMeters()
        val seconds = length / 1.4
        val times = DoubleArray(points.size).also { for (i in 1 until points.size) it[i] = it[i - 1] + points[i - 1].distanceTo(points[i]) / 1.4 }
        val turnAlong = points.take(10).lengthMeters()
        return CampusRoute("practice-$nowMillis", 0, "practice", "practice-v1", PLACE_ID, "", points, emptyList(), length, times.last(),
            listOf(Maneuver("Walk straight ahead", points.first(), 0.0, "Start"), Maneuver("Turn right", corner, turnAlong, "Right"), Maneuver("You have arrived at your practice spot", end, length, "Arrive")),
            "", source = "valhalla", profile = "walking", provider = "practice", fetchedAtEpochMillis = nowMillis, destinationPoint = end, destinationTitle = "Practice path",
            shapeElapsedSeconds = times.toList()).also { check(kotlin.math.abs(it.estimatedSeconds - seconds) < 1.0) }
    }
}

/** One reading of which way the phone faces (the lens if it is upright, the top edge if it lies flat), true north; null if there is no sensor. */
suspend fun readPhoneHeading(context: Context, near: GeoPoint?): Double? = withTimeoutOrNull(1_500) {
    val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return@withTimeoutOrNull null
    suspendCancellableCoroutine<Double?> { continuation ->
        val matrix = FloatArray(9)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                val declination = near?.let { GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), 0f, System.currentTimeMillis()).declination.toDouble() } ?: 0.0
                val basis = cameraBasis(matrix, declination)
                val heading = if (kotlin.math.abs(basis.pitchDegrees) < 55) basis.headingDegrees else (Math.toDegrees(kotlin.math.atan2(basis.up[0], basis.up[1])) + 360.0) % 360.0
                manager.unregisterListener(this)
                if (continuation.isActive) continuation.resumeWith(Result.success(heading))
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        continuation.invokeOnCancellation { manager.unregisterListener(listener) }
    }
}
