package com.vpn4tv.app.outline

import android.util.Log
import io.nekohasekai.libbox.Libbox

/**
 * Wrapper for the embedded outline-sdk bridge inside libbox.
 *
 * The bridge is started only when the active subscription contains
 * Shadowsocks endpoints with the SIP002 ?prefix= parameter (Outline TLS
 * prefix obfuscation), which neither sing-box nor xray-core support
 * natively. For each such endpoint we open a dedicated SOCKS5 inbound
 * on 127.0.0.127:<port> and outline-sdk forwards the traffic to the
 * Shadowsocks server, applying the prefix on the dial salt.
 *
 * Bound sockets are protected against the sing-box TUN via the same
 * platformInterfaceWrapper.AutoDetectInterfaceControl callback used by
 * the xray bridge (Android's VpnService.protect).
 */
object OutlineBridge {

    private const val TAG = "OutlineBridge"

    /** Same loopback host as XrayBridge to keep both bridges in 127.0.0.127. */
    const val SOCKS_HOST = "127.0.0.127"

    @Volatile private var running = false

    fun isRunning(): Boolean = running

    @Synchronized
    fun start(configJson: String) {
        if (running) {
            Log.w(TAG, "start() called while already running — stopping first")
            stop()
        }
        try {
            Libbox.startOutlineBridge(configJson)
            running = true
            Log.i(TAG, "outline bridge started")
            // Periodically dump bridge log lines into logcat for debugging.
            // Cheap (~200 lines max) — safe to leave on.
            startLogDumper()
        } catch (e: Exception) {
            Log.e(TAG, "failed to start outline bridge", e)
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
                    val all = Libbox.outlineLog()
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
            name = "outline-log-dumper"
            start()
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        try {
            Libbox.stopOutlineBridge()
            Log.i(TAG, "outline bridge stopped")
        } catch (e: Exception) {
            Log.e(TAG, "failed to stop outline bridge", e)
        } finally {
            running = false
            dumperThread?.interrupt()
            dumperThread = null
        }
    }
}
