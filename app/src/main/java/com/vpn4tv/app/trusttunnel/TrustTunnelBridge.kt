package com.vpn4tv.app.trusttunnel

import android.os.Build
import android.util.Log
import com.adguard.trusttunnel.CertificateVerificator
import com.adguard.trusttunnel.DeepLink
import com.adguard.trusttunnel.VpnClient
import com.adguard.trusttunnel.VpnClientListener
import com.adguard.trusttunnel.VpnState
import org.json.JSONObject

/**
 * TrustTunnel (AdGuard's HTTP/2 + HTTP/3 VPN protocol) as a bridge under
 * sing-box — the same shape as xray, outline, wireproxy and olcrtc: one local
 * SOCKS5 per link, sing-box routes to it through a socks outbound and keeps
 * the TUN. The library is vendored as app/libs/trusttunnel-client.aar, built
 * from source by .scripts/build_trusttunnel.sh with our one JNI patch: a
 * start without a TUN descriptor selects the SOCKS listener (upstream's
 * Android adapter only ever ran in TUN mode).
 *
 * Each tt:// link is decoded by the library into an `[endpoint]` TOML; we
 * append the `[listener.socks]` section and the top-level fields the CLI
 * insists on. The library's upstream sockets are protected through
 * [protect], which the service wires to VpnService.protect — without it the
 * tunnel would loop back through sing-box's TUN.
 */
object TrustTunnelBridge {

    private const val TAG = "TrustTunnelBridge"

    const val SOCKS_HOST = "127.0.0.127"

    /** The AAR declares minSdk 24; the app runs on 23. Never touch it below 24. */
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= 24

    private class Endpoint(val port: Int, val client: VpnClient)

    private val clients = mutableListOf<Endpoint>()

    @Volatile private var running = false

    fun isRunning(): Boolean = running

    /**
     * Start one client per endpoint in [configJson]. [protect] is called for
     * every upstream socket the library opens; return false and the library
     * treats the socket as unusable.
     */
    @Synchronized
    fun start(configJson: String, protect: (Int) -> Boolean) {
        if (!isSupported) throw IllegalStateException("TrustTunnel needs Android 7.0 or newer")
        if (running) {
            Log.w(TAG, "start() called while already running — stopping first")
            stop()
        }
        val endpoints = JSONObject(configJson).getJSONArray("endpoints")
        if (endpoints.length() == 0) throw IllegalArgumentException("no TrustTunnel endpoints")
        val verificator = CertificateVerificator()
        try {
            for (i in 0 until endpoints.length()) {
                val entry = endpoints.getJSONObject(i)
                val port = entry.getInt("port")
                val toml = buildToml(entry.getString("url"), port)
                val client = VpnClient(toml, object : VpnClientListener {
                    override fun protectSocket(fd: Int): Boolean = protect(fd)
                    override fun verifyCertificate(certificate: ByteArray?, rawChain: List<ByteArray?>?): Boolean =
                        verificator.verifyCertificate(certificate, rawChain)
                    override fun onStateChanged(state: Int) {
                        Log.i(TAG, "endpoint $i: ${VpnState.getByCode(state)}")
                    }
                    // One line per tunnelled connection — debug only, or it
                    // drowns everything else in logcat.
                    override fun onConnectionInfo(info: String) {
                        Log.d(TAG, "endpoint $i: $info")
                    }
                })
                // null TUN → SOCKS-only (our JNI patch).
                if (!client.start(null)) {
                    client.close()
                    throw IllegalStateException("TrustTunnel endpoint $i failed to start")
                }
                clients.add(Endpoint(port, client))
                Log.i(TAG, "endpoint $i: SOCKS5 on $SOCKS_HOST:$port")
            }
            running = true
        } catch (e: Exception) {
            stopLocked()
            throw e
        }
    }

    /**
     * `[endpoint]` from the link plus what the SOCKS mode needs. The library
     * refuses a config without vpn_mode; the kill switch is a TUN-mode
     * feature and must stay off here or the listener blocks traffic when the
     * endpoint drops instead of letting sing-box fail over.
     */
    private fun buildToml(link: String, port: Int): String {
        val endpointToml = DeepLink.decode(link)
        return buildString {
            append("vpn_mode = \"general\"\n")
            append("killswitch_enabled = false\n")
            append(endpointToml.trimEnd()).append('\n')
            append("\n[listener.socks]\n")
            append("address = \"").append(SOCKS_HOST).append(':').append(port).append("\"\n")
        }
    }

    @Synchronized
    fun stop() {
        if (!running && clients.isEmpty()) return
        stopLocked()
    }

    private fun stopLocked() {
        for (endpoint in clients) {
            try {
                endpoint.client.stop()
                endpoint.client.close()
            } catch (e: Exception) {
                Log.w(TAG, "stop endpoint on port ${endpoint.port}: ${e.message}")
            }
        }
        clients.clear()
        running = false
        Log.i(TAG, "trusttunnel bridge stopped")
    }
}
