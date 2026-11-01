package com.vpn4tv.app.bg

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

/**
 * Credentials for the local inbounds of the bridges: a username and password
 * for the SOCKS5 ones (outline, wireproxy, olcrtc, TrustTunnel, and xray's in
 * a sidecar written before 5.4.0), a UUID for xray's VLESS one.
 *
 * The bridges listen on 127.0.0.127, and the loopback on Android is shared by
 * every app on the device. Without credentials any app — one the user excluded
 * from the VPN included — can connect to a bridge, go out through our server
 * and learn its address: the "local proxy" leak (runetfreedom, 2026-04). So
 * every bridge inbound requires them, and every outbound sing-box aims at a
 * bridge carries them.
 *
 * Applied when the service starts, in memory: profiles and sidecars on disk
 * stay as they are (the generators write [UUID_PLACEHOLDER] where the UUID
 * goes), so profiles imported before this change are covered the moment the
 * app updates.
 *
 * One random set per process. The reload path rebuilds the sing-box config
 * from the profile but leaves the bridges running, so the set has to outlive a
 * single start; a new process starts the bridges afresh with a new set.
 *
 * UDP never travels as SOCKS5 UDP that a password cannot cover: the Outline
 * and AmneziaWG bridges take it inside the TCP connection (UDP over TCP), the
 * xray bridge inside its VLESS connection (XUDP), olcRTC carries none. The one
 * exception is TrustTunnel, whose UDP goes by SOCKS5 UDP ASSOCIATE: our build
 * of its library binds each association's relay to sing-box's own socket in
 * this process, which is what the password cannot do for a datagram. An xray
 * sidecar still in the SOCKS5 shape (not upgraded yet) keeps the password and
 * no UDP, as before.
 */
object BridgeAuth {

    /** Where every bridge listens; matches XrayBridge/OutlineBridge/… SOCKS_HOST. */
    private const val BRIDGE_HOST = "127.0.0.127"

    /**
     * What the generators write as the UUID of the xray bridge, in the VLESS
     * inbound of its sidecar and in sing-box's vless outbound to it. Replaced
     * with [uuid] at start; never accepted by a running bridge, since
     * [applyToXray] replaces every client of its VLESS inbounds.
     */
    const val UUID_PLACEHOLDER = "00000000-0000-0000-0000-000000000000"

    private val random = SecureRandom()

    val username: String by lazy { randomHex(8) }
    val password: String by lazy { randomHex(24) }

    /** The xray bridge's VLESS client id: random (version 4, from SecureRandom), never the placeholder. */
    val uuid: String by lazy {
        generateSequence { UUID.randomUUID().toString() }.first { it != UUID_PLACEHOLDER }
    }

    private fun randomHex(bytes: Int): String =
        ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** For log lines that echo a config. */
    fun redact(text: String): String = text.replace(password, "<bridge-password>").replace(uuid, "<bridge-uuid>")

    /**
     * sing-box: the pair on every socks outbound aimed at a bridge, the UUID on
     * the vless outbound to the xray bridge. A socks outbound that already names
     * its own user, or a vless one with an id other than [UUID_PLACEHOLDER],
     * is left alone. The pair is harmless where the bridge wants no password:
     * the client offers username/password and the server is free to answer
     * "no authentication", which sing-box accepts.
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
            if (outbound.optString("server") != BRIDGE_HOST) continue
            when (outbound.optString("type")) {
                "socks" -> {
                    if (outbound.optString("username").isNotEmpty()) continue
                    outbound.put("username", username)
                    outbound.put("password", password)
                    changed = true
                }
                "vless" -> {
                    if (outbound.optString("uuid") != UUID_PLACEHOLDER) continue
                    outbound.put("uuid", uuid)
                    changed = true
                }
            }
        }
        return if (changed) root.toString() else jsonText
    }

    /**
     * xray sidecar: on its VLESS inbounds the UUID as the one client, whatever
     * the sidecar lists. On SOCKS5 inbounds (a sidecar written before 5.4.0)
     * password authentication and no UDP, as before: SOCKS5 UDP is not covered
     * by the password, and sing-box's socks outbounds to it are TCP-only.
     */
    fun applyToXray(jsonText: String): String {
        val root = JSONObject(jsonText)
        val inbounds = root.optJSONArray("inbounds") ?: return jsonText
        for (i in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(i) ?: continue
            val protocol = inbound.optString("protocol")
            if (protocol != "vless" && protocol != "socks") continue
            val settings = inbound.optJSONObject("settings") ?: JSONObject().also { inbound.put("settings", it) }
            when (protocol) {
                "vless" -> {
                    settings.put("clients", JSONArray().put(JSONObject().put("id", uuid)))
                    settings.put("decryption", "none")
                    settings.remove("flow")
                }
                "socks" -> {
                    settings.put("auth", "password")
                    settings.put("accounts", JSONArray().put(JSONObject().apply {
                        put("user", username)
                        put("pass", password)
                    }))
                    settings.put("udp", false)
                }
            }
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
