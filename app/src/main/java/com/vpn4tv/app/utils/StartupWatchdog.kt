package com.vpn4tv.app.utils

import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Catches a launch that never gets to the home screen. Seen on cheap boxes and
 * projectors since 5.3.0 (5.2.6 works there): a white window until Home, no
 * crash, nothing reported. The window itself draws; what never comes is the
 * profile the screens wait for (MainActivity.profileReady), so the check is on
 * that, not on a frame. A blocked main thread shows up the same way: the
 * posted value never lands.
 *
 * Armed once per process in MainActivity.onCreate. If [ready] is still false
 * after [TIMEOUT_MS], the stacks of all threads go to the crash host as a
 * hang-java report (trailer stage=ui-start, so the analyzer tells it from a
 * connect hang), then the Go goroutines. The app is left as it is.
 */
object StartupWatchdog {
    private const val TAG = "StartupWatchdog"

    // Beyond a first launch's subscription fetch on a slow network (30 s timeouts).
    private const val TIMEOUT_MS = 40_000L

    private val armed = AtomicBoolean(false)

    fun arm(ready: () -> Boolean) {
        if (!armed.compareAndSet(false, true)) return
        val armedAt = SystemClock.elapsedRealtime()
        Thread {
            try {
                Thread.sleep(TIMEOUT_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (ready()) return@Thread
            Log.w(TAG, "home screen not ready ${TIMEOUT_MS} ms after launch: reporting the thread stacks")
            try {
                val sb = StringBuilder()
                for ((th, frames) in Thread.getAllStackTraces()) {
                    sb.append('"').append(th.name).append("\" ").append(th.state).append('\n')
                    for (f in frames) sb.append("\tat ").append(f).append('\n')
                    sb.append('\n')
                }
                val now = SystemClock.elapsedRealtime()
                val uptime = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    now - android.os.Process.getStartElapsedRealtime()
                } else {
                    -1L
                }
                sb.append("# vpn4tv watchdog stage=ui-start attemptMs=${now - armedAt} processUptimeMs=$uptime\n")
                CrashUpload.upload(sb.toString(), "", "hang-java")
            } catch (t: Throwable) {
                Log.w(TAG, "java stack dump failed: ${t.message}")
            }
            // Last: runtime.Stack can itself block if the Go runtime is wedged.
            try {
                CrashUpload.upload(io.nekohasekai.libbox.Libbox.debugGoroutineDump().value, "", "hang")
            } catch (t: Throwable) {
                Log.w(TAG, "goroutine dump failed: ${t.message}")
            }
        }.apply {
            name = "startup-watchdog"
            isDaemon = true
        }.start()
    }
}
