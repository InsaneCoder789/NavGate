package com.rohanc.navgate.ui.location

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.core.ImuSample
import com.rohanc.navgate.core.MotionFusion
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Feeds the accelerometer, gyroscope and orientation sensors into [fusion] while the app is visible.
 * Everything is rotated into the world frame first, so it works with the phone in any cradle or pocket orientation.
 * Without these sensors the filter simply runs on GPS alone.
 */
@Composable
fun BindMotionSensors(enabled: Boolean, fusion: MotionFusion, lastKnownPoint: () -> GeoPoint?) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(context, lifecycle, enabled) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotation = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val linear = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val gyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val matrix = FloatArray(9)
        var haveMatrix = false
        var compassOk = false
        var yawRate = 0.0
        var deviceHeading: Double? = null
        var declination = 0.0
        var declinationAt = 0L
        var registered = false
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(matrix, event.values); haveMatrix = true
                        val now = System.currentTimeMillis()
                        if (now - declinationAt > 60_000) {
                            lastKnownPoint()?.let { declination = GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), 0f, now).declination.toDouble(); declinationAt = now }
                        }
                        // Lying flat the top edge leads; upright in a cradle the back of the phone faces the road.
                        val flat = kotlin.math.abs(matrix[8]) > 0.7f
                        val east = if (flat) matrix[1] else -matrix[2]
                        val north = if (flat) matrix[4] else -matrix[5]
                        deviceHeading = (Math.toDegrees(atan2(east.toDouble(), north.toDouble())) + declination + 360.0) % 360.0
                    }
                    Sensor.TYPE_GYROSCOPE -> if (haveMatrix) yawRate = (matrix[6] * event.values[0] + matrix[7] * event.values[1] + matrix[8] * event.values[2]).toDouble()
                    Sensor.TYPE_LINEAR_ACCELERATION -> if (haveMatrix) {
                        val ax = event.values[0]; val ay = event.values[1]; val az = event.values[2]
                        val magEast = (matrix[0] * ax + matrix[1] * ay + matrix[2] * az).toDouble()
                        val magNorth = (matrix[3] * ax + matrix[4] * ay + matrix[5] * az).toDouble()
                        // The sensor frame points to magnetic north; rotate to true north.
                        val d = Math.toRadians(declination)
                        val east = magEast * cos(d) + magNorth * sin(d)
                        val north = -magEast * sin(d) + magNorth * cos(d)
                        fusion.onImu(ImuSample(event.timestamp / 1_000_000, east, north, yawRate, deviceHeading, compassOk))
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) compassOk = accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
            }
        }
        fun refresh() {
            val started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (enabled && started && !registered) {
                // 25 Hz is plenty for road navigation (GAME is ~50 Hz); batching lets the CPU sleep between deliveries.
                registered = listOfNotNull(rotation, linear, gyro).fold(false) { any, s -> manager.registerListener(listener, s, 40_000, 200_000) || any }
            } else if ((!enabled || !started) && registered) { manager.unregisterListener(listener); registered = false }
        }
        val observer = LifecycleEventObserver { _, _ -> refresh() }
        lifecycle.addObserver(observer); refresh()
        onDispose { lifecycle.removeObserver(observer); manager.unregisterListener(listener) }
    }
}
