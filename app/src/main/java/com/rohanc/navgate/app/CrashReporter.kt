package com.rohanc.navgate.app

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last crash on the phone so it can be reported, and never sends anything anywhere by itself.
 * The next launch can offer the user a "share details" button. A short trail of the last few things the app was doing is kept
 * with it, so the report says what screen and state the crash happened in.
 */
object CrashReporter {
    private const val FILE = "last-crash.txt"
    private val trail = ArrayDeque<String>()

    /** Remember what the app is doing (screen names, trip state). Only the last dozen are kept; no places or coordinates. */
    @Synchronized fun note(event: String) { if (trail.lastOrNull() == event) return; trail.addLast(event); while (trail.size > 12) trail.removeFirst() }

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { File(app.filesDir, FILE).writeText(report(app, thread, error).take(30_000)) }
            previous?.uncaughtException(thread, error)
        }
    }

    @Synchronized private fun report(context: Context, thread: Thread, error: Throwable): String {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).let { "${it.versionName} (${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it)})" } }.getOrDefault("?")
        return buildString {
            appendLine("NavGate crash report")
            appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
            appendLine("App: $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Thread: ${thread.name}")
            appendLine("Recent: ${trail.joinToString(" > ")}")
            appendLine(); append(stack)
        }
    }

    /** The saved report from the previous run, if the app crashed. */
    fun pending(context: Context): String? = runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(context: Context) { runCatching { File(context.filesDir, FILE).delete() } }
}
