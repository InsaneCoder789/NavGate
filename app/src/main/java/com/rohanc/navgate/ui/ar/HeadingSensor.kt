package com.rohanc.navgate.ui.ar

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs

@Composable
fun rememberHeadingState(): MutableState<Double?> {
    val context = LocalContext.current
    val headingState = remember { mutableStateOf<Double?>(null) }

    DisposableEffect(context) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnetometer = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val gyroscope = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        val accelerometerValues = FloatArray(3)
        val magnetometerValues = FloatArray(3)
        var hasAccelerometer = false
        var hasMagnetometer = false
        var smoothedHeading: Double? = null
        var latestTurnRate = 0.0

        fun publishHeading(rawHeading: Double) {
            val previous = smoothedHeading
            val delta = if (previous == null) 0.0 else normalizeDelta(rawHeading - previous)
            val smoothingFactor = if (abs(latestTurnRate) > 0.8) 0.55 else 0.22
            smoothedHeading = if (previous == null) rawHeading else normalizeHeading(previous + (delta * smoothingFactor))
            headingState.value = smoothedHeading
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                        SensorManager.getOrientation(rotationMatrix, orientation)
                        publishHeading(normalizeHeading(Math.toDegrees(orientation[0].toDouble())))
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        event.values.copyInto(accelerometerValues)
                        hasAccelerometer = true
                        if (rotationSensor == null) publishFromFallback()
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        event.values.copyInto(magnetometerValues)
                        hasMagnetometer = true
                        if (rotationSensor == null) publishFromFallback()
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        latestTurnRate = event.values[2].toDouble()
                    }
                }
            }

            private fun publishFromFallback() {
                if (!hasAccelerometer || !hasMagnetometer) return
                if (SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerValues, magnetometerValues)) {
                    SensorManager.getOrientation(rotationMatrix, orientation)
                    publishHeading(normalizeHeading(Math.toDegrees(orientation[0].toDouble())))
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        rotationSensor?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        accelerometer?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        magnetometer?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroscope?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }

        onDispose { manager.unregisterListener(listener) }
    }

    return headingState
}

private fun normalizeHeading(value: Double): Double = (value + 360.0) % 360.0

private fun normalizeDelta(value: Double): Double = when {
    value > 180 -> value - 360
    value < -180 -> value + 360
    else -> value
}
