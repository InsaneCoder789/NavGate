package com.rohanc.navgate.ui.ar

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.rohanc.navgate.core.CampusRoute
import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.core.projectPath

/** Live rear-camera axes from the rotation-vector sensor, in a true-north frame. Null until the first reading. */
@Composable
fun rememberCameraBasis(enabled: Boolean, position: () -> GeoPoint?): State<CameraBasis?> {
    val context = LocalContext.current
    val basis = remember { mutableStateOf<CameraBasis?>(null) }
    DisposableEffect(context, enabled) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val matrix = FloatArray(9)
        var declination = 0.0
        var declinationAt = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                val now = System.currentTimeMillis()
                if (now - declinationAt > 60_000) position()?.let {
                    declination = GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), 0f, now).declination.toDouble(); declinationAt = now
                }
                basis.value = cameraBasis(matrix, declination)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (enabled && sensor != null) manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { manager.unregisterListener(listener) }
    }
    return basis
}

/** How close to the route the phone must be before AR guidance is offered. */
const val AR_MAX_DISTANCE_FROM_ROUTE = 25.0

/** Null when AR guidance may run; otherwise the reason it may not. AR only works while standing on the planned road. */
fun arBlockedReason(route: CampusRoute?, position: GeoPoint?, accuracyMeters: Double?): String? {
    if (route == null || route.geometry.size < 2) return "Plan a route first to use AR."
    if (route.profile != "walking") return "AR guidance is for walking routes. Choose Walk to use it."
    if (position == null) return "Waiting for your location."
    if ((accuracyMeters ?: 0.0) > 35.0) return "Location is too rough for AR. Step into the open and try again."
    val off = projectPath(position, route.geometry)?.distanceMeters ?: return "Waiting for your location."
    return if (off > AR_MAX_DISTANCE_FROM_ROUTE) "AR works once you are on the route. You are ${off.toInt()} m away from it." else null
}
