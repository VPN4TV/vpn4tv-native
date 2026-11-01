package com.vpn4tv.app.converter

import com.vpn4tv.app.bg.RussiaDirect
import org.json.JSONArray
import org.json.JSONObject

/**
 * Brings a saved config to the shape ConfigGenerator writes since 5.4.0, for
 * the bridge-UDP leak fix: a config is generated at import or refresh and
 * stays on disk, and local profiles (pasted keys, LAN import) are never
 * regenerated, so without this their UDP and DNS would keep going around the
 * tunnel. SavedConfigUpgrade runs it over every profile once, when the
 * version code changes; nothing transforms the config at start.
 *
 * What changes, as the new generator has it:
 *  1. the rule {network: udp, outbound: direct} goes;
 *  2. the socks outbounds to the Outline and AmneziaWG bridges carry UDP over
 *     TCP (no network limit, udp_over_tcp v2);
 *  3. dns-remote goes through "select", asked over TCP (udp → tcp,
 *     quic → tls, h3 → https) since the config can pick a bridge;
 *  4. where no proxy carries UDP even then (olcRTC, xray only),
 *     {network: udp, action: reject} is the last rule;
 *  5. the socks outbounds to the TrustTunnel bridge carry plain SOCKS5 UDP
 *     (no network limit; its relay takes this process's datagrams only), and
 *     a config with FakeDNS and such an outbound resolves the names of UDP
 *     flows on the route, right after hijack-dns (the relay takes addresses
 *     only).
 * FakeDNS and the DNS strategy stay as they are.
 *
 * Only configs ConfigGenerator wrote are touched: our groups, DNS servers
 * and route, and at least one socks outbound to the bridge host on a port a
 * sidecar next to the config serves. A bridge outbound counts as Outline or
 * AmneziaWG only by the port in that bridge's own sidecar, never by the port
 * alone. A foreign sing-box config (imported as is) comes back unchanged.
 * Idempotent: an upgraded or newly generated config gives no changes. Pure,
 * org.json only: the JVM tests run it as is.
 */
object ConfigUpgrade {
    private const val SELECT = "select"
    private const val DIRECT = "direct"
    private const val DNS_REMOTE = "dns-remote"
    private const val DNS_DIRECT = "dns-direct"
    private const val BRIDGE_HOST = com.vpn4tv.app.xray.XrayBridge.SOCKS_HOST

    /** [Result.skipped] of a config without a socks outbound to a port its sidecars serve. */
    const val NO_BRIDGE = "no bridge outbound its sidecars serve"

    /** The bridge sidecars next to a config, as read from disk (null: no such file). */
    class Sidecars(
        val xray: String? = null,
        val outline: String? = null,
        val wg: String? = null,
        val olcrtc: String? = null,
        val tt: String? = null,
    )

    /**
     * [json]: the config to write back; null when nothing changes (already in
     * shape, not ours, not JSON). [changes]: what changed, for the log, no
     * keys in it. [skipped]: why the config was left alone, null when ours.
     */
    class Result(val json: String?, val changes: List<String>, val skipped: String?)

    fun upgrade(text: String, sidecars: Sidecars): Result {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            return Result(null, emptyList(), "not a JSON object")
        }
        return try {
            upgrade(root, sidecars)
        } catch (e: Exception) {
            Result(null, emptyList(), "error: ${e.message}")
        }
    }

    private fun upgrade(root: JSONObject, sidecars: Sidecars): Result {
        notOursReason(root)?.let { return Result(null, emptyList(), "not ours: $it") }

        val udpPorts = endpointPorts(sidecars.outline) + endpointPorts(sidecars.wg)
        val bridgePorts = udpPorts + xrayPorts(sidecars.xray) + endpointPorts(sidecars.olcrtc) + endpointPorts(sidecars.tt)
        val outbounds = root.getJSONArray("outbounds")
        val bridges = objects(outbounds).filter { isBridgeOutbound(it) && port(it) in bridgePorts }
        // No bridges: nothing of the leak to fix, and our frame alone does not
        // tell our config from a foreign one with the same tags.
        if (bridges.isEmpty()) return Result(null, emptyList(), NO_BRIDGE)

        val changes = mutableListOf<String>()

        // 2. Outline and AmneziaWG carry UDP over TCP.
        for (outbound in bridges) {
            if (port(outbound) !in udpPorts || hasUdpOverTcp(outbound)) continue
            outbound.remove("network")
            outbound.put("udp_over_tcp", ConfigGenerator.udpOverTcp())
            changes += "${outbound.optString("tag")}: UDP over TCP"
        }

        // 5a. TrustTunnel carries plain SOCKS5 UDP: no udp_over_tcp, which the
        // library does not speak.
        val ttPorts = endpointPorts(sidecars.tt)
        for (outbound in bridges) {
            if (port(outbound) !in ttPorts || outbound.optString("network") != "tcp") continue
            outbound.remove("network")
            changes += "${outbound.optString("tag")}: UDP through TrustTunnel's relay"
        }

        // 1. No UDP around the tunnel.
        val route = root.getJSONObject("route")
        val rules = route.optJSONArray("rules") ?: JSONArray().also { route.put("rules", it) }
        for (i in rules.length() - 1 downTo 0) {
            val rule = rules.optJSONObject(i) ?: continue
            if (isUdpDirect(rule)) {
                rules.remove(i)
                changes += "rule {udp → direct} removed"
            }
        }

        // 3. Remote DNS through the tunnel, over TCP where a bridge can be picked.
        val remote = objects(root.getJSONObject("dns").getJSONArray("servers")).first { it.optString("tag") == DNS_REMOTE }
        if (remote.optString("detour") != SELECT) {
            remote.put("detour", SELECT)
            changes += "$DNS_REMOTE through $SELECT"
        }
        if (ConfigGenerator.dnsOverTcp(root)) {
            val type = remote.optString("type")
            val tcp = ConfigGenerator.tcpDnsType(type)
            if (tcp != type) {
                remote.put("type", tcp)
                changes += "$DNS_REMOTE $type → $tcp"
            }
        }

        // 4. No proxy carries UDP: refuse it, last.
        if (objects(outbounds).none { ConfigGenerator.carriesUdp(it) }) {
            val at = (0 until rules.length()).firstOrNull { i -> rules.optJSONObject(i)?.let { RussiaDirect.isUdpReject(it) } == true }
            when {
                at == null -> {
                    rules.put(ConfigGenerator.udpRejectRule())
                    changes += "UDP refused (no proxy carries it)"
                }
                at != rules.length() - 1 -> {
                    RussiaDirect.keepUdpRejectLast(rules)
                    changes += "UDP refusal moved last"
                }
            }
        }

        // 5b. With FakeDNS, the names of UDP flows are resolved on the route
        // before TrustTunnel gets them: its relay takes addresses only.
        if (bridges.any { port(it) in ttPorts } && hasFakeDns(root) && objects(rules).none { isUdpResolve(it) }) {
            val hijack = (0 until rules.length()).firstOrNull { rules.optJSONObject(it)?.optString("action") == "hijack-dns" }
            route.put("rules", insertAt(rules, if (hijack == null) 0 else hijack + 1, ConfigGenerator.udpResolveRule()))
            changes += "UDP names resolved on the route (TrustTunnel)"
        }

        if (changes.isEmpty()) return Result(null, emptyList(), null)
        return Result(root.toString(2), changes, null)
    }

    private fun hasFakeDns(root: JSONObject): Boolean =
        objects(root.optJSONObject("dns")?.optJSONArray("servers") ?: JSONArray()).any { it.optString("type") == "fakeip" }

    private fun isUdpResolve(rule: JSONObject): Boolean =
        rule.optString("action") == "resolve" && rule.optString("network") == "udp"

    private fun insertAt(array: JSONArray, index: Int, value: JSONObject): JSONArray {
        val out = JSONArray()
        for (i in 0 until array.length()) {
            if (i == index) out.put(value)
            out.put(array.get(i))
        }
        if (index >= array.length()) out.put(value)
        return out
    }

    /**
     * Null when [root] has ConfigGenerator's frame: the "select" selector and
     * the "direct" outbound, dns-remote and dns-direct, a route ending in
     * "select". The bridge outbounds are checked against the sidecars after.
     */
    internal fun notOursReason(root: JSONObject): String? {
        val outbounds = root.optJSONArray("outbounds") ?: return "no outbounds"
        val list = objects(outbounds)
        if (list.none { it.optString("type") == "selector" && it.optString("tag") == SELECT }) return "no selector \"$SELECT\""
        if (list.none { it.optString("type") == "direct" && it.optString("tag") == DIRECT }) return "no outbound \"$DIRECT\""
        val servers = root.optJSONObject("dns")?.optJSONArray("servers") ?: return "no dns servers"
        val tags = objects(servers).map { it.optString("tag") }
        if (DNS_REMOTE !in tags || DNS_DIRECT !in tags) return "no $DNS_REMOTE/$DNS_DIRECT"
        val route = root.optJSONObject("route") ?: return "no route"
        if (route.optString("final") != SELECT) return "route.final is not \"$SELECT\""
        return null
    }

    /** Exactly what the old generators wrote for profiles made only of bridges. */
    private fun isUdpDirect(rule: JSONObject): Boolean =
        rule.length() == 2 && rule.opt("network") == "udp" && rule.opt("outbound") == DIRECT

    private fun isBridgeOutbound(outbound: JSONObject): Boolean =
        outbound.optString("type") == "socks" && outbound.optString("server") == BRIDGE_HOST

    private fun hasUdpOverTcp(outbound: JSONObject): Boolean {
        val uot = outbound.optJSONObject("udp_over_tcp") ?: return false
        return !outbound.has("network") && uot.optBoolean("enabled", false) && uot.optInt("version", 0) == 2
    }

    private fun port(outbound: JSONObject): Int = outbound.optInt("server_port", -1)

    /** Ports of an outline / wireproxy / olcrtc / TrustTunnel sidecar: {"endpoints": [{"port": n, …}]}. */
    private fun endpointPorts(text: String?): Set<Int> = ports(text, "endpoints")

    /** Ports of the xray sidecar, an xray config: its socks inbounds. */
    private fun xrayPorts(text: String?): Set<Int> = ports(text, "inbounds")

    private fun ports(text: String?, key: String): Set<Int> {
        if (text.isNullOrBlank()) return emptySet()
        val array = try {
            JSONObject(text).optJSONArray(key)
        } catch (_: Exception) {
            null
        } ?: return emptySet()
        return objects(array).mapNotNull { it.optInt("port", -1).takeIf { port -> port > 0 } }.toSet()
    }

    private fun objects(array: JSONArray): List<JSONObject> = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}
