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
fun BindLocationTracking(enabled: Boolean,onLocationSample: (LocationFix) -> Unit,onSignalTick: () -> Unit = {},navigating: Boolean = false) {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    val callback by rememberUpdatedState(onLocationSample)
    val tick by rememberUpdatedState(onSignalTick)
    val enabledOnce = remember { mutableStateOf(false) }
    DisposableEffect(context,owner,enabled,navigating) {
        val manager=context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var registered=false
        var lastGpsMillis=Long.MIN_VALUE
        val listener=object:LocationListener {
            override fun onLocationChanged(location:Location) {
                val at=location.elapsedRealtimeNanos/1_000_000
                // Coarse network positions alternate with GPS and make the position jump; use them only when GPS has gone quiet.
                if(location.provider==LocationManager.GPS_PROVIDER) lastGpsMillis=at
                else if(navigating || (lastGpsMillis!=Long.MIN_VALUE && at-lastGpsMillis<20_000)) return
                callback(LocationFix(GeoPoint(location.latitude,location.longitude),location.elapsedRealtimeNanos/1_000_000,
                    if(location.hasAccuracy()) location.accuracy.toDouble() else Double.POSITIVE_INFINITY,
                    if(location.hasBearing()) location.bearing.toDouble() else null,if(location.hasSpeed()) location.speed.toDouble() else null))
            }
            override fun onProviderEnabled(provider:String) = Unit
            override fun onProviderDisabled(provider:String) { tick() }
            @Deprecated("Framework compatibility") override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?) = Unit
        }
        fun remove() { if(registered) { manager.removeUpdates(listener);registered=false } }
        fun refresh() {
            remove()
            if(!enabled || !hasLocationPermission(context) || !(navigating || owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))) return
            val fine=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
            // While guiding, only GPS is used: it is the only source accurate enough for a road. Idle, the network gives a quick first fix.
            val providers=if(!fine) listOf(LocationManager.NETWORK_PROVIDER) else if(navigating) listOf(LocationManager.GPS_PROVIDER) else listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER)
            try {
                // Show a position immediately from the last known fix instead of waiting for a fresh one.
                if(!enabledOnce.value) { enabledOnce.value=true
                    providers.mapNotNull{ if(manager.allProviders.contains(it)) manager.getLastKnownLocation(it) else null }
                        .maxByOrNull{it.elapsedRealtimeNanos}?.let{listener.onLocationChanged(it)} }
                for(provider in providers) if(manager.allProviders.contains(provider) && manager.isProviderEnabled(provider)) {
                    // Fewer, slower updates when not guiding: the receiver is one of the biggest battery costs.
                    val interval=if(navigating) 1000L else if(provider==LocationManager.GPS_PROVIDER) 2000L else 10_000L
                    manager.requestLocationUpdates(provider,interval,if(navigating) 0f else 3f,listener,Looper.getMainLooper());registered=true
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

/** True while the phone's location switch is on. Updates the moment the user flips it, so guidance can say why it has stopped. */
@Composable
fun rememberLocationServicesOn(): Boolean {
    val context=LocalContext.current
    fun on():Boolean=runCatching{androidx.core.location.LocationManagerCompat.isLocationEnabled(context.getSystemService(Context.LOCATION_SERVICE) as LocationManager)}.getOrDefault(true)
    var enabled by remember{mutableStateOf(on())}
    DisposableEffect(context){
        val receiver=object:android.content.BroadcastReceiver(){override fun onReceive(c:Context?,i:android.content.Intent?){enabled=on()}}
        androidx.core.content.ContextCompat.registerReceiver(context,receiver,android.content.IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        enabled=on()
        onDispose{runCatching{context.unregisterReceiver(receiver)}}
    }
    return enabled
}

fun hasLocationPermission(context:Context):Boolean=
    ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
