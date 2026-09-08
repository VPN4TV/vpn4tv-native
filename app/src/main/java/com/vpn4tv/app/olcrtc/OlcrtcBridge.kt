package com.vpn4tv.app.olcrtc

import android.util.Log
import com.vpn4tv.app.ktx.unwrap
import io.nekohasekai.libbox.Libbox

/**
 * Wrapper for the embedded olcRTC bridge inside libbox: TCP over a WebRTC
 * "video call" on a meeting service that stays reachable when nothing else
 * does (the whitelist scenario). One local SOCKS5 inbound per olcrtc:// link,
 * on 127.0.0.127:<port>; sing-box routes to it through a socks outbound.
 *
 * Only the 64-bit libbox carries the runtime (it costs ~30 MB); on a 32-bit
 * TV box [Libbox.olcrtcAvailable] is false and start() fails with a clear
 * message, which BoxService shows instead of a generic error.
 */
object OlcrtcBridge {

    private const val TAG = "OlcrtcBridge"

    const val SOCKS_HOST = "127.0.0.127"

    @Volatile private var running = false

    fun isRunning(): Boolean = running

    fun isAvailable(): Boolean = try {
        Libbox.olcrtcAvailable()
    } catch (_: Throwable) {
        false
    }

    @Synchronized
    fun start(configJson: String) {
        if (running) {
            Log.w(TAG, "start() called while already running — stopping first")
            stop()
        }
        try {
            Libbox.startOlcrtcBridge(configJson)
            running = true
            Log.i(TAG, "olcrtc bridge started")
            startLogDumper()
        } catch (e: Exception) {
            Log.e(TAG, "failed to start olcrtc bridge", e)
            throw e
        }
    }

    private var dumperThread: Thread? = null
    private fun startLogDumper() {
        dumperThread?.interrupt()
        dumperThread = Thread {
            var lastSize = 0
            while (running) {
                try {
                    val all = Libbox.olcrtcLog().unwrap
                    val lines = if (all.isEmpty()) emptyList() else all.split("\n")
                    if (lines.size > lastSize) {
                        for (i in lastSize until lines.size) {
                            Log.i(TAG, "[bridge] ${lines[i]}")
                        }
                        lastSize = lines.size
                    }
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (_: Exception) {}
            }
        }.apply {
            isDaemon = true
            name = "olcrtc-log-dumper"
            start()
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        try {
            Libbox.stopOlcrtcBridge()
            Log.i(TAG, "olcrtc bridge stopped")
        } catch (e: Exception) {
            Log.e(TAG, "failed to stop olcrtc bridge", e)
        } finally {
            running = false
            dumperThread?.interrupt()
            dumperThread = null
        }
    }
}
