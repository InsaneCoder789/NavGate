package com.rohanc.navgate.ui.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.core.LocationFix
import kotlinx.coroutines.delay

/** Visible-app framework location adapter. No Play Services or background-location permission required. */
@Composable
@SuppressLint("MissingPermission")
fun BindLocationTracking(enabled: Boolean,onLocationSample: (LocationFix) -> Unit,onSignalTick: () -> Unit = {}) {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    val callback by rememberUpdatedState(onLocationSample)
    val tick by rememberUpdatedState(onSignalTick)
    DisposableEffect(context,owner,enabled) {
        val manager=context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var registered=false
        val listener=object:LocationListener {
            override fun onLocationChanged(location:Location) {
                callback(LocationFix(GeoPoint(location.latitude,location.longitude),location.elapsedRealtimeNanos/1_000_000,
                    if(location.hasAccuracy()) location.accuracy.toDouble() else Double.POSITIVE_INFINITY,
                    if(location.hasBearing()) location.bearing.toDouble() else null))
            }
            override fun onProviderEnabled(provider:String) = Unit
            override fun onProviderDisabled(provider:String) { tick() }
            @Deprecated("Framework compatibility") override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?) = Unit
        }
        fun remove() { if(registered) { manager.removeUpdates(listener);registered=false } }
        fun refresh() {
            remove()
            if(!enabled || !hasLocationPermission(context) || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
            val fine=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
            val providers=if(fine) listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER) else listOf(LocationManager.NETWORK_PROVIDER)
            try {
                for(provider in providers) if(manager.allProviders.contains(provider) && manager.isProviderEnabled(provider)) {
                    manager.requestLocationUpdates(provider,1000L,1f,listener,Looper.getMainLooper());registered=true
                }
            } catch(_:SecurityException) { remove();tick() }
        }
        val observer=LifecycleEventObserver { _,_ -> refresh() }
        owner.lifecycle.addObserver(observer)
        refresh()
        onDispose { owner.lifecycle.removeObserver(observer);remove() }
    }
    LaunchedEffect(owner) {
        // Freshness must age even after permission loss removes the location listener.
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while(true) { tick();delay(1000) } }
    }
}

fun hasLocationPermission(context:Context):Boolean=
    ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
