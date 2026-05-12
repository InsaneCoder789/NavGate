package com.rohanc.navgate.ui.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.rohanc.navgate.model.Coordinate

@Composable
@SuppressLint("MissingPermission")
fun BindLocationTracking(
    enabled: Boolean,
    onLocationSample: (Coordinate, Double?, Long) -> Unit,
) {
    val context = LocalContext.current

    DisposableEffect(context, enabled) {
        if (!enabled || !hasLocationPermission(context)) {
            onDispose { }
        } else {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val request =
                LocationRequest.Builder(1500L)
                    .setMinUpdateDistanceMeters(2f)
                    .setMinUpdateIntervalMillis(1000L)
                    .setWaitForAccurateLocation(false)
                    .build()

            val callback =
                object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        val fix = result.lastLocation ?: return
                        val heading = if (fix.hasBearing()) fix.bearing.toDouble() else null
                        onLocationSample(
                            Coordinate(fix.latitude, fix.longitude),
                            heading,
                            System.currentTimeMillis() - fix.time,
                        )
                    }
                }

            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            onDispose {
                client.removeLocationUpdates(callback)
            }
        }
    }
}

fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
