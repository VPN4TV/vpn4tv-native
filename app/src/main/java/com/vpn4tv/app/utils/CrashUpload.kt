package com.vpn4tv.app.utils

import android.os.Build
import android.util.Log
import com.vpn4tv.app.BuildConfig

/**
 * Best-effort off-device upload of diagnostic dumps to bell.a4e.ar/crash.
 * Shared by the Go-crash surfacer (consumeCrashLog, kind="crash") and the
 * connect-watchdog (kind="hang") — Play strips/truncates the in-app frames and
 * field hangs never reach Vitals at all, so the receiver is the only way to see
 * full goroutine stacks. Off-thread, never throws.
 */
object CrashUpload {
    fun upload(content: String, bridges: String, kind: String) {
        Thread {
            var conn: java.net.HttpURLConnection? = null
            try {
                val body = (if (content.length > 1_500_000) content.take(1_500_000) else content)
                    .toByteArray(Charsets.UTF_8)
                conn = (java.net.URL("https://bell.a4e.ar/crash").openConnection()
                    as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 10000
                    doOutput = true
                    setRequestProperty("X-Kind", kind)
                    setRequestProperty("X-VC", BuildConfig.VERSION_CODE.toString())
                    setRequestProperty("X-Android", Build.VERSION.SDK_INT.toString())
                    setRequestProperty("X-Device", "${Build.MANUFACTURER} ${Build.MODEL}".take(120))
                    setRequestProperty("X-Bridges", bridges.take(120))
                    setFixedLengthStreamingMode(body.size)
                }
                conn.outputStream.use { it.write(body) }
                Log.i("CrashUpload", "$kind dump uploaded: HTTP ${conn.responseCode} (${body.size} bytes)")
            } catch (e: Exception) {
                Log.w("CrashUpload", "$kind dump upload failed: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }.apply { name = "crash-upload" }.start()
    }
}
