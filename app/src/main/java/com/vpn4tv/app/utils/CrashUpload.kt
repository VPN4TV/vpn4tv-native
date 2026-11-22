package com.vpn4tv.app.utils

import android.os.Build
import android.util.Log
import com.vpn4tv.app.BuildConfig

/**
 * Best-effort off-device upload of diagnostic dumps to bell.a4e.ar/crash.
 * Shared by the Go-crash surfacer (consumeCrashLog, kind="crash"), the Java crash
 * reporter (JavaCrashReport, kind="java-crash"), the connect-watchdog (kind="hang"), the exits the system
 * forced while the app mattered (ExitReasons, kind="exit") and "Send to support" (SupportReport,
 * kind="user-report") — Play strips/truncates the in-app frames and
 * field hangs never reach Vitals at all, so the receiver is the only way to see
 * full goroutine stacks. Never throws.
 */
object CrashUpload {
    private const val ENDPOINT = "https://bell.a4e.ar/crash"

    /** Returns the upload thread, already started; callers that must not lose the report can join it. */
    fun upload(content: String, bridges: String, kind: String): Thread =
        Thread { post(content, bridges, kind) }.apply { name = "crash-upload"; start() }

    /**
     * Blocking: true once the receiver took the report. [report]: its name at
     * the client (X-Report), the support code of a user report. [failed]: told
     * why it did not go, in a few words without addresses of links. [open]:
     * the connection to send by; by default the app's own way out, through the
     * VPN while it is up.
     */
    fun post(
        content: String,
        bridges: String,
        kind: String,
        report: String? = null,
        failed: ((String) -> Unit)? = null,
        open: (java.net.URL) -> java.net.URLConnection = { it.openConnection() },
    ): Boolean {
        var conn: java.net.HttpURLConnection? = null
        try {
            val body = (if (content.length > 1_500_000) content.take(1_500_000) else content)
                .toByteArray(Charsets.UTF_8)
            conn = (open(java.net.URL(ENDPOINT)) as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 10000
                doOutput = true
                setRequestProperty("X-Kind", kind)
                setRequestProperty("X-VC", BuildConfig.VERSION_CODE.toString())
                setRequestProperty("X-Android", Build.VERSION.SDK_INT.toString())
                setRequestProperty("X-Device", "${Build.MANUFACTURER} ${Build.MODEL}".take(120))
                setRequestProperty("X-Bridges", bridges.take(120))
                // The receiver's first line has these for every client (Apple sends them too); "?" without.
                // The version went as X-App until 5.6.5, a header the receiver does not read.
                setRequestProperty("X-Client", "android")
                setRequestProperty("X-App-Ver", BuildConfig.VERSION_NAME.take(40))
                setRequestProperty("X-OS", "android ${Build.VERSION.RELEASE}".take(40))
                if (report != null) setRequestProperty("X-Report", report.take(40))
                setFixedLengthStreamingMode(body.size)
            }
            conn.outputStream.use { it.write(body) }
            val status = conn.responseCode
            Log.i("CrashUpload", "$kind dump uploaded: HTTP $status (${body.size} bytes)")
            if (status != 200) failed?.invoke("HTTP $status")
            return status == 200
        } catch (e: Exception) {
            Log.w("CrashUpload", "$kind dump upload failed: ${LogPrivacy.redactLinks(e.message)}")
            failed?.invoke("${e.javaClass.simpleName}: ${LogPrivacy.redactLinks(e.message)}".take(200))
            return false
        } finally {
            conn?.disconnect()
        }
    }
}
