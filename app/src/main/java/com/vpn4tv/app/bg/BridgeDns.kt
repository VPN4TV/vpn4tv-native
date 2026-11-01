package com.vpn4tv.app.bg

import org.json.JSONArray
import org.json.JSONObject

/**
 * DNS servers for the Outline and AmneziaWG bridges to resolve their own
 * servers with: the core asks them itself, over protected sockets outside the
 * tunnel, the way olcRTC does: the "dns" field of the bridge config, a list
 * of IP addresses with an optional port (libbox bridge_dns.go).
 *
 * Without it a bridge given its server by name asked the system resolver,
 * i.e. the DNS of the VPN network, our own tun: in a profile made only of
 * bridges the query went hijack-dns → dns-remote → select → the very bridge
 * that needed the answer, a loop.
 *
 * Applied when a bridge starts, in memory, like BridgeAuth: the sidecars on
 * disk stay as they are, so every saved profile gets it. A core without the
 * field ignores it; the core keeps its system resolver when none is given.
 * olcRTC is left to its own built-in list.
 */
object BridgeDns {

    /** outline / wireproxy sidecar: [servers] for every endpoint, unless the sidecar names its own. */
    fun applyToEndpoints(
        jsonText: String,
        servers: List<String> = com.vpn4tv.app.utils.DnsProviders.bridgeDnsServers,
    ): String {
        if (servers.isEmpty()) return jsonText
        val root = JSONObject(jsonText)
        if (root.has("dns")) return jsonText
        root.put("dns", JSONArray(servers))
        return root.toString()
    }
}
