package com.rohanc.navgate.app

import android.app.Application
import android.os.StrictMode
import com.rohanc.navgate.BuildConfig

class NavGateApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        if (BuildConfig.DEBUG) {
            // Debug builds shout when the main thread touches disk or the network, or when something leaks, so slow spots get found early.
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectNetwork().detectCustomSlowCalls().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectActivityLeaks().penaltyLog().build())
        }
    }
}
