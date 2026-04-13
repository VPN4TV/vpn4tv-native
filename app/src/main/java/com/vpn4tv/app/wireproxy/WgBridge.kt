package com.vpn4tv.app.wireproxy

import android.util.Log
import io.nekohasekai.libbox.Libbox

/**
 * Wrapper for the embedded wireproxy-awg bridge inside libbox.
 *
 * Starts an in-process AmneziaWG (awg 2.0) userspace WireGuard device for
 * each endpoint in the config JSON and fronts it with a SOCKS5 inbound on
 * 127.0.0.127:<port>. sing-box routes TUN traffic to that inbound via a
 * regular socks outbound; inside the bridge, amneziawg-go speaks the real
 * AWG wire protocol (with obfuscation params Jc/Jmin/Jmax/S1..S4/H1..H4)
 * to the remote peer.
 *
 * Outer UDP sockets are protected via the same
 * platformInterfaceWrapper.AutoDetectInterfaceControl used by the xray and
 * outline bridges (Android's VpnService.protect), so WG datagrams bypass
 * sing-box's TUN instead of looping through it.
 */
object WgBridge {

    private const val TAG = "WgBridge"

    /** Same loopback host as xray and outline bridges. */
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
            Libbox.startWireproxyBridge(configJson)
            running = true
            Log.i(TAG, "wireproxy bridge started")
            startLogDumper()
        } catch (e: Exception) {
            Log.e(TAG, "failed to start wireproxy bridge", e)
            throw e
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        try {
            Libbox.stopWireproxyBridge()
            Log.i(TAG, "wireproxy bridge stopped")
        } catch (e: Exception) {
            Log.e(TAG, "failed to stop wireproxy bridge", e)
        } finally {
            running = false
            dumperThread?.interrupt()
            dumperThread = null
        }
    }

    private var dumperThread: Thread? = null

    private fun startLogDumper() {
        dumperThread?.interrupt()
        dumperThread = Thread {
            var lastSize = 0
            while (running) {
                try {
                    val all = Libbox.wireproxyLog()
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
            name = "wireproxy-log-dumper"
            start()
        }
    }
}
