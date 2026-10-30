package com.vpn4tv.app.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Java/Kotlin crashes off-device. bell.a4e.ar/crash only ever saw Go crashes and connect hangs, and Play
 * Vitals never hears from the cheap TV boxes that send Google no diagnostics — a crash at launch there
 * (5.3.0 on a "10k Ultra HD" box: white screen, then gone; 5.2.6 fine) left no trace anywhere.
 *
 * The uncaught-exception handler writes the stack trace to a file and hands over to the previous handler,
 * so the process still dies the normal way and Play still gets the report where it can. The next launch
 * uploads the file (kind="java-crash") before anything else runs.
 */
object JavaCrashReport {
    private const val FILE = "java_crash.log"

    fun install(context: Context) {
        val dir = context.filesDir
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                File(dir, FILE).writeText("java-crash thread=${thread.name}\n" + trace.take(200_000))
            } catch (_: Throwable) {
                // nothing more can be done while the process is going down
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * A crash at launch repeats a second or two into the next launch, so a background upload alone would
     * often die with the process: wait for it, up to 3 s, and only when there is a report to send.
     */
    fun uploadPrevious(context: Context) {
        val file = File(context.filesDir, FILE)
        if (!file.exists() || file.length() == 0L) return
        val content = try {
            file.readText()
        } catch (e: Exception) {
            Log.w("JavaCrashReport", "read failed: ${e.message}")
            return
        } finally {
            file.delete()
        }
        Log.e("JavaCrashReport", "previous Java crash (${content.length} bytes):\n$content")
        try {
            CrashUpload.upload(content, "", "java-crash").join(3000)
        } catch (_: InterruptedException) {
        }
    }
}
