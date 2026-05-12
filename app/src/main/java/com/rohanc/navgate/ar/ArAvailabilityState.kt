package com.rohanc.navgate.ar

sealed interface ArAvailabilityState {
    data object Checking : ArAvailabilityState
    data object Supported : ArAvailabilityState
    data object Unsupported : ArAvailabilityState
    data object ApkTooOld : ArAvailabilityState
    data object NotInstalled : ArAvailabilityState
    data class Error(val message: String) : ArAvailabilityState
}
