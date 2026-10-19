package com.rohanc.navgate.ui.map

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/** Connectivity is a capability hint, not a guarantee the tile provider is reachable. */
@Composable internal fun rememberMapConnection():Boolean {
    val context=LocalContext.current
    val manager=remember(context){context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager}
    fun available():Boolean=manager.getNetworkCapabilities(manager.activeNetwork)?.let{
        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }==true
    var connected by remember(manager){mutableStateOf(available())}
    DisposableEffect(manager){
        var disposed=false
        val handler=Handler(Looper.getMainLooper())
        fun refresh(){handler.post{if(!disposed)connected=available()}}
        val callback=object:ConnectivityManager.NetworkCallback(){
            override fun onAvailable(network:Network){refresh()}
            override fun onLost(network:Network){refresh()}
            override fun onCapabilitiesChanged(network:Network,capabilities:NetworkCapabilities){refresh()}
        }
        manager.registerDefaultNetworkCallback(callback)
        onDispose{disposed=true;manager.unregisterNetworkCallback(callback)}
    }
    return connected
}
