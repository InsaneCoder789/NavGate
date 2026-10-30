package com.rohanc.navgate.nav

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** In-process bridge between the system surfaces (notification action, floating window) and the screens. The app and its service share one process. */
object NavigationEvents {
    private val exit = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** "Exit navigation" pressed in the notification. */
    val exitRequests: SharedFlow<Unit> = exit
    fun requestExit() { exit.tryEmit(Unit) }

    private val pipReady = MutableStateFlow(false)
    /** True while a trip is on the navigation screen, so leaving the app should shrink it to a floating window. */
    val pipEligible: StateFlow<Boolean> = pipReady
    fun setPipEligible(value: Boolean) { pipReady.value = value }

    private val inPip = MutableStateFlow(false)
    /** True while the activity is the floating window. */
    val inPictureInPicture: StateFlow<Boolean> = inPip
    fun setInPictureInPicture(value: Boolean) { inPip.value = value }
}
