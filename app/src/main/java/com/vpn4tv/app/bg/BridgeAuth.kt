package com.vpn4tv.app.bg

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Username and password for the local SOCKS5 inbounds of the bridges (xray,
 * outline, wireproxy, olcrtc, TrustTunnel).
 *
 * The bridges listen on 127.0.0.127, and the loopback on Android is shared by
 * every app on the device. Without a password any app — one the user excluded
 * from the VPN included — can connect to a bridge, go out through our server
 * and learn its address: the "local proxy" leak (runetfreedom, 2026-04). So
 * every bridge inbound requires this pair, and every socks outbound sing-box
 * aims at a bridge carries it.
 *
 * Applied when the service starts, in memory: profiles and sidecars on disk
 * stay as they are, so profiles imported before this change are covered the
 * moment the app updates.
 *
 * One random pair per process. The reload path rebuilds the sing-box config
 * from the profile but leaves the bridges running, so the pair has to outlive a
 * single start; a new process starts the bridges afresh with a new pair. The
 * xray inbound also loses UDP: SOCKS5 UDP carries no credentials, and sing-box
 * reaches every bridge over TCP only.
 */
object BridgeAuth {

    /** Where every bridge listens; matches XrayBridge/OutlineBridge/… SOCKS_HOST. */
    private const val BRIDGE_HOST = "127.0.0.127"

    private val random = SecureRandom()

    val username: String by lazy { randomHex(8) }
    val password: String by lazy { randomHex(24) }

    private fun randomHex(bytes: Int): String =
        ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** For log lines that echo a config. */
    fun redact(text: String): String = text.replace(password, "<bridge-password>")

    /**
     * sing-box: the pair on every socks outbound aimed at a bridge. An outbound
     * that already names its own user is left alone. Harmless where the bridge
     * wants no password: the client offers username/password and the server is
     * free to answer "no authentication", which sing-box accepts.
     */
    fun applyToSingbox(jsonText: String): String {
        val root = try {
            JSONObject(jsonText)
        } catch (_: Exception) {
            return jsonText // not ours to rewrite; no bridges run for it
        }
        val outbounds = root.optJSONArray("outbounds") ?: return jsonText
        var changed = false
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(i) ?: continue
            if (outbound.optString("type") != "socks") continue
            if (outbound.optString("server") != BRIDGE_HOST) continue
            if (outbound.optString("username").isNotEmpty()) continue
            outbound.put("username", username)
            outbound.put("password", password)
            changed = true
        }
        return if (changed) root.toString() else jsonText
    }

    /** xray sidecar: password authentication and no UDP on its socks inbounds. */
    fun applyToXray(jsonText: String): String {
        val root = JSONObject(jsonText)
        val inbounds = root.optJSONArray("inbounds") ?: return jsonText
        for (i in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(i) ?: continue
            if (inbound.optString("protocol") != "socks") continue
            val settings = inbound.optJSONObject("settings") ?: JSONObject().also { inbound.put("settings", it) }
            settings.put("auth", "password")
            settings.put("accounts", JSONArray().put(JSONObject().apply {
                put("user", username)
                put("pass", password)
            }))
            // SOCKS5 UDP is not covered by the password, and nothing uses it:
            // sing-box's outbounds to the bridge are TCP-only.
            settings.put("udp", false)
        }
        return root.toString()
    }

    /** outline / wireproxy / olcrtc / TrustTunnel sidecars: the pair on each endpoint. */
    fun applyToEndpoints(jsonText: String): String {
        val root = JSONObject(jsonText)
        val endpoints = root.optJSONArray("endpoints") ?: return jsonText
        for (i in 0 until endpoints.length()) {
            val endpoint = endpoints.optJSONObject(i) ?: continue
            if (endpoint.optString("username").isNotEmpty()) continue
            endpoint.put("username", username)
            endpoint.put("password", password)
        }
        return root.toString()
    }
}
