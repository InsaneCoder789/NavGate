package com.rohanc.navgate.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Posts one crash report as plain text. Used only when the user opted in and a report endpoint was configured at build time. */
object CrashUploader {
    private val client by lazy { OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build() }

    /** True when the server accepted the report; false (and the report is kept for next time) on any failure. */
    fun send(url: String, report: String): Boolean {
        if (url.isBlank()) return false
        return runCatching {
            val request = Request.Builder().url(url).post(report.toRequestBody("text/plain; charset=utf-8".toMediaType())).build()
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}
