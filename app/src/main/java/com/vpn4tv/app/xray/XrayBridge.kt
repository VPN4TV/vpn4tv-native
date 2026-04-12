package com.vpn4tv.app.xray

import android.content.Context
import android.util.Log
import io.nekohasekai.libbox.Libbox

/**
 * Wrapper for the embedded xray-core instance inside libbox.
 *
 * libbox now ships with xray-core baked in via our fork of sing-box
 * (experimental/libbox/xray.go). Both runtimes share the same Go runtime,
 * same libgojni.so and same go.Seq, so there are no duplicate-class or
 * JNI mid==null conflicts that would arise if we loaded a separate
 * libv2ray.aar alongside libbox.aar.
 *
 * The bridge is only started when the active subscription contains outbounds
 * that sing-box can't handle natively (xhttp/splithttp). For fully native
 * subscriptions it stays dormant and has zero runtime overhead.
 */
object XrayBridge {

    private const val TAG = "XrayBridge"

    /**
     * Bind address for the local SOCKS5 bridge.
     *
     * We intentionally bind to 127.0.0.127 instead of 127.0.0.1 so that
     * government-mandated scanners in RU apps don't flag an open port on
     * standard localhost. All of 127.0.0.0/8 is loopback, so this address
     * is functionally identical while staying off the beaten path. The
     * port is randomized per install (see Settings.xrayPortBase).
     */
    const val SOCKS_HOST = "127.0.0.127"

    /** Base port for xray SOCKS5 inbounds. Randomized once per install. */
    val SOCKS_PORT_BASE: Int get() = com.vpn4tv.app.database.Settings.xrayPortBase

    /**
     * Whether the xray bridge is supported on this device. The embedded
     * xray-core uses the same Go runtime as libbox, so if libbox loads at
     * all (Android 6.0+/API 23), xray is available too. We keep this as a
     * constant for callsite readability and future tightening if needed.
     */
    val isSupported: Boolean = true

    @Volatile private var running = false

    fun isRunning(): Boolean = running

    @Synchronized
    fun start(context: Context, configJson: String) {
        if (running) {
            Log.w(TAG, "start() called while already running — stopping first")
            stop()
        }
        // Log in 3000-char chunks so logcat doesn't truncate a large config
        configJson.chunked(3000).forEachIndexed { idx, chunk ->
            Log.d(TAG, "xray config[$idx]: $chunk")
        }
        try {
            Libbox.startXrayInstance(configJson)
            running = true
            Log.i(TAG, "xray started on SOCKS5 $SOCKS_HOST:$SOCKS_PORT_BASE+")
        } catch (e: Exception) {
            Log.e(TAG, "failed to start xray", e)
            throw e
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        try {
            Libbox.stopXrayInstance()
            Log.i(TAG, "xray stopped")
        } catch (e: Exception) {
            Log.e(TAG, "failed to stop xray", e)
        } finally {
            running = false
        }
    }
}
