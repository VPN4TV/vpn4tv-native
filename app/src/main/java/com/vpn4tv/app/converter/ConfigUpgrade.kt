package com.vpn4tv.app.converter

import com.vpn4tv.app.bg.RussiaDirect
import com.vpn4tv.app.xray.XrayConfigGenerator
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
 *  4. where no proxy carries UDP even then (olcRTC only),
 *     {network: udp, action: reject} is the last rule; where one does, a
 *     refusal an earlier generator wrote goes (an xray-only config of a 5.4.0
 *     release candidate had one);
 *  5. the socks outbounds to the TrustTunnel bridge carry plain SOCKS5 UDP
 *     (no network limit; its relay takes this process's datagrams only), and
 *     a config with FakeDNS and such an outbound resolves the names of UDP
 *     flows on the route, right after hijack-dns (the relay takes addresses
 *     only);
 *  6. the socks outbounds to the xray bridge become vless outbounds to it
 *     (ConfigGenerator.toXrayBridgeOutbound), and the SOCKS5 inbounds of the
 *     xray sidecar VLESS ones (XrayConfigGenerator): UDP rides inside the
 *     TCP connection as XUDP. The sidecar comes back in [Result.xray], to be
 *     written next to the config.
 * FakeDNS and the DNS strategy stay as they are.
 *
 * Only configs ConfigGenerator wrote are touched: our groups, DNS servers
 * and route, and at least one outbound to the bridge host on a port a
 * sidecar next to the config serves (socks; or vless, to a port of the xray
 * sidecar). A bridge outbound counts as Outline, AmneziaWG or xray only by
 * the port in that bridge's own sidecar, never by the port alone. A foreign
 * sing-box config (imported as is) comes back unchanged. Each half of the
 * xray change is made on its own, so a config and a sidecar left half done
 * (one written, the other not) are completed the next time. Idempotent: an
 * upgraded or newly generated config gives no changes. Pure, org.json only:
 * the JVM tests run it as is.
 */
object ConfigUpgrade {
    private const val SELECT = "select"
    private const val DIRECT = "direct"
    private const val DNS_REMOTE = "dns-remote"
    private const val DNS_DIRECT = "dns-direct"
    /** Tag prefix of the xray sidecar's SOCKS5 inbounds before 5.4.0. */
    private const val OLD_XRAY_INBOUND_PREFIX = "socks-in-"

    /** [Result.skipped] of a config without a bridge outbound on a port its sidecars serve. */
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
     * [json]: the config to write back; null when it does not change (already
     * in shape, not ours, not JSON). [xray]: the xray sidecar to write back,
     * null when it does not change. [changes]: what changed, for the log, no
     * keys in it. [skipped]: why the config was left alone, null when ours.
     */
    class Result(val json: String?, val changes: List<String>, val skipped: String?, val xray: String? = null)

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
        val xrayPorts = xrayPorts(sidecars.xray)
        val bridgePorts = udpPorts + xrayPorts + endpointPorts(sidecars.olcrtc) + endpointPorts(sidecars.tt)
        val outbounds = root.getJSONArray("outbounds")
        val bridges = objects(outbounds).filter {
            ConfigGenerator.isBridgeOutbound(it) && port(it) in (if (isSocks(it)) bridgePorts else xrayPorts)
        }
        // No bridges: nothing of the leak to fix, and our frame alone does not
        // tell our config from a foreign one with the same tags.
        if (bridges.isEmpty()) return Result(null, emptyList(), NO_BRIDGE)

        val changes = mutableListOf<String>()

        // 6a. xray: the vless outbound in place of the socks one. First, so the
        // loops below, over socks outbounds, never take it for another bridge.
        for (outbound in bridges) {
            if (!isSocks(outbound) || port(outbound) !in xrayPorts) continue
            ConfigGenerator.toXrayBridgeOutbound(outbound, port(outbound))
            changes += "${outbound.optString("tag")}: VLESS to the xray bridge, UDP as XUDP"
        }

        // 2. Outline and AmneziaWG carry UDP over TCP.
        for (outbound in bridges) {
            if (!isSocks(outbound) || port(outbound) !in udpPorts || hasUdpOverTcp(outbound)) continue
            outbound.remove("network")
            outbound.put("udp_over_tcp", ConfigGenerator.udpOverTcp())
            changes += "${outbound.optString("tag")}: UDP over TCP"
        }

        // 5a. TrustTunnel carries plain SOCKS5 UDP: no udp_over_tcp, which the
        // library does not speak.
        val ttPorts = endpointPorts(sidecars.tt)
        for (outbound in bridges) {
            if (!isSocks(outbound) || port(outbound) !in ttPorts || outbound.optString("network") != "tcp") continue
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

        // 4. No proxy carries UDP: refuse it, last. One does: no refusal.
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
        } else {
            for (i in rules.length() - 1 downTo 0) {
                if (rules.optJSONObject(i)?.let { RussiaDirect.isUdpReject(it) } != true) continue
                rules.remove(i)
                changes += "UDP refusal removed (a proxy carries UDP)"
            }
        }

        // 5b. With FakeDNS, the names of UDP flows are resolved on the route
        // before TrustTunnel gets them: its relay takes addresses only.
        if (bridges.any { port(it) in ttPorts } && hasFakeDns(root) && objects(rules).none { isUdpResolve(it) }) {
            val hijack = (0 until rules.length()).firstOrNull { rules.optJSONObject(it)?.optString("action") == "hijack-dns" }
            route.put("rules", insertAt(rules, if (hijack == null) 0 else hijack + 1, ConfigGenerator.udpResolveRule()))
            changes += "UDP names resolved on the route (TrustTunnel)"
        }

        // 6b. The xray sidecar: VLESS inbounds in place of SOCKS5 ones, once
        // an outbound of this config reaches it by VLESS (6a, or written so).
        val xray = if (bridges.any { !isSocks(it) }) upgradeXraySidecar(sidecars.xray) else null
        val configChanged = changes.isNotEmpty()
        if (xray != null) changes += "xray bridge: VLESS inbounds"

        if (changes.isEmpty()) return Result(null, emptyList(), null)
        return Result(if (configChanged) root.toString(2) else null, changes, null, xray)
    }

    /**
     * The xray sidecar [text] with VLESS inbounds in place of its SOCKS5
     * ones, as XrayConfigGenerator writes them: the placeholder client id
     * (BridgeAuth fills it in at start), no encryption; listen address, port
     * and sniffing stay. The tags "socks-in-N" become "vless-in-N", in the
     * inbounds and in the routing rules that name them. Null when it has no
     * SOCKS5 inbound, or is not an xray config.
     */
    internal fun upgradeXraySidecar(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        val inbounds = root.optJSONArray("inbounds") ?: return null
        val tags = objects(inbounds).map { it.optString("tag") }.toMutableSet()
        val renamed = mutableMapOf<String, String>()
        var changed = false
        for (inbound in objects(inbounds)) {
            if (inbound.optString("protocol") != "socks") continue
            inbound.put("protocol", "vless")
            inbound.put("settings", XrayConfigGenerator.vlessInboundSettings())
            changed = true
            val tag = inbound.optString("tag")
            val index = tag.removePrefix(OLD_XRAY_INBOUND_PREFIX).takeIf { tag.startsWith(OLD_XRAY_INBOUND_PREFIX) }?.toIntOrNull()
            val newTag = index?.let { XrayConfigGenerator.inboundTag(it) } ?: continue
            if (newTag in tags) continue
            inbound.put("tag", newTag)
            tags += newTag
            renamed[tag] = newTag
        }
        if (!changed) return null
        val rules = root.optJSONObject("routing")?.optJSONArray("rules") ?: JSONArray()
        for (rule in objects(rules)) {
            when (val inboundTag = rule.opt("inboundTag")) {
                is String -> renamed[inboundTag]?.let { rule.put("inboundTag", it) }
                is JSONArray -> for (i in 0 until inboundTag.length()) {
                    (inboundTag.opt(i) as? String)?.let { renamed[it] }?.let { inboundTag.put(i, it) }
                }
            }
        }
        return root.toString()
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

    private fun isSocks(outbound: JSONObject): Boolean = outbound.optString("type") == "socks"

    private fun hasUdpOverTcp(outbound: JSONObject): Boolean {
        val uot = outbound.optJSONObject("udp_over_tcp") ?: return false
        return !outbound.has("network") && uot.optBoolean("enabled", false) && uot.optInt("version", 0) == 2
    }

    private fun port(outbound: JSONObject): Int = outbound.optInt("server_port", -1)

    /** Ports of an outline / wireproxy / olcrtc / TrustTunnel sidecar: {"endpoints": [{"port": n, …}]}. */
    private fun endpointPorts(text: String?): Set<Int> = ports(text, "endpoints")

    /**
     * Ports of the xray sidecar, an xray config: its inbounds as the
     * generators write them, SOCKS5 before 5.4.0, VLESS since.
     */
    private fun xrayPorts(text: String?): Set<Int> =
        ports(text, "inbounds") { it.optString("protocol") == "socks" || it.optString("protocol") == "vless" }

    private fun ports(text: String?, key: String, filter: (JSONObject) -> Boolean = { true }): Set<Int> {
        if (text.isNullOrBlank()) return emptySet()
        val array = try {
            JSONObject(text).optJSONArray(key)
        } catch (_: Exception) {
            null
        } ?: return emptySet()
        return objects(array).filter(filter).mapNotNull { it.optInt("port", -1).takeIf { port -> port > 0 } }.toSet()
    }

    private fun objects(array: JSONArray): List<JSONObject> = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}
