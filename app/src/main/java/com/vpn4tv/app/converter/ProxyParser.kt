package com.vpn4tv.app.converter

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Parses proxy URI links (vless://, hy2://, trojan://, ss://) into
 * intermediate ProxyConfig objects that can be converted to sing-box JSON.
 */
data class ProxyConfig(
    val tag: String,
    val type: String,           // "vless", "hysteria2", "trojan", "shadowsocks"
    val server: String,
    val serverPort: Int,
    val outbound: JSONObject,   // full sing-box outbound JSON
)

/** DNS extracted from subscription (if any) */
data class SubscriptionDns(
    val remoteDns: String = "https://1.1.1.1/dns-query",
    val directDns: String = "1.1.1.1",
)

object ProxyParser {
    /** Last parsed DNS config from Xray JSON subscription */
    var lastDns = SubscriptionDns()

    fun parse(line: String): ProxyConfig? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) return null

        return when {
            trimmed.startsWith("vless://") -> parseVless(trimmed)
            trimmed.startsWith("vmess://") -> parseVmess(trimmed)
            trimmed.startsWith("hysteria2://") || trimmed.startsWith("hy2://") -> parseHysteria2(trimmed)
            trimmed.startsWith("trojan://") -> parseTrojan(trimmed)
            trimmed.startsWith("ss://") -> parseShadowsocks(trimmed)
            else -> null
        }
    }

    fun parseSubscription(content: String): List<ProxyConfig> {
        val trimmed = content.trim()

        // Detect JSON config (sing-box or Xray)
        if (trimmed.startsWith("{")) {
            try {
                val json = JSONObject(trimmed)
                if (json.has("outbounds")) {
                    // Check if sing-box format (has "inbounds" with "type":"tun")
                    val inbounds = json.optJSONArray("inbounds")
                    if (inbounds != null) {
                        for (i in 0 until inbounds.length()) {
                            if (inbounds.getJSONObject(i).optString("type") == "tun") {
                                // Native sing-box config — return special marker
                                return listOf(ProxyConfig(
                                    tag = "_singbox_passthrough_",
                                    type = "singbox",
                                    server = "",
                                    serverPort = 0,
                                    outbound = json // store entire config
                                ))
                            }
                        }
                    }
                    // Xray JSON config
                    return parseXrayConfig(json)
                }
            } catch (_: Exception) {}
        }

        // Try base64 decode first (some subs are base64-encoded)
        val decoded = tryBase64Decode(content) ?: content
        return decoded.lines()
            .mapNotNull { parse(it) }
    }

    private fun tryBase64Decode(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.contains("://")) return null // already URI format
        return try {
            String(Base64.decode(trimmed, Base64.DEFAULT or Base64.NO_WRAP))
                .takeIf { it.contains("://") }
        } catch (_: Exception) { null }
    }

    // ─── VLESS ──────────────────────────────────────────────

    private fun parseVless(uri: String): ProxyConfig? {
        // vless://UUID@HOST:PORT?params#NAME
        val parsed = Uri.parse(uri)
        val uuid = parsed.userInfo ?: return null
        val host = parsed.host ?: return null
        val port = parsed.port.takeIf { it > 0 } ?: 443
        val name = URLDecoder.decode(parsed.fragment ?: host, "UTF-8")
        val params = parseQueryParams(parsed)

        val security = params["security"] ?: ""
        val sni = params["sni"] ?: host
        val flow = params["flow"] ?: ""
        val fp = params["fp"] ?: "chrome"
        val alpn = params["alpn"]?.split(",")
        val pbk = params["pbk"] ?: ""
        val sid = params["sid"] ?: ""
        val spx = params["spx"] ?: ""
        val transportType = params["type"] ?: "tcp"

        val outbound = JSONObject().apply {
            put("type", "vless")
            put("tag", name)
            put("server", host)
            put("server_port", port)
            put("uuid", uuid)
            if (flow.isNotEmpty()) put("flow", flow)
            put("packet_encoding", "xudp")

            // TLS
            if (security == "tls" || security == "reality") {
                put("tls", JSONObject().apply {
                    put("enabled", true)
                    put("server_name", sni)
                    if (alpn != null) put("alpn", alpn.toJsonArray())
                    if (security == "reality") {
                        put("reality", JSONObject().apply {
                            put("enabled", true)
                            put("public_key", pbk)
                            put("short_id", sid)
                        })
                    }
                    put("utls", JSONObject().apply {
                        put("enabled", true)
                        put("fingerprint", fp)
                    })
                })
            }

            // Transport
            putTransport(this, transportType, params)
        }

        return ProxyConfig(tag = name, type = "vless", server = host, serverPort = port, outbound = outbound)
    }

    // ─── HYSTERIA2 ──────────────────────────────────────────

    private fun parseHysteria2(uri: String): ProxyConfig? {
        // hysteria2://AUTH@HOST:PORT?params#NAME
        // hy2://AUTH@HOST:PORT?params#NAME
        val normalized = uri.replace("hy2://", "hysteria2://")
        val parsed = Uri.parse(normalized)
        val password = parsed.userInfo ?: return null
        val host = parsed.host ?: return null
        val port = parsed.port.takeIf { it > 0 } ?: 443
        val name = URLDecoder.decode(parsed.fragment ?: host, "UTF-8")
        val params = parseQueryParams(parsed)

        val sni = params["sni"] ?: host
        val obfsType = params["obfs"] ?: ""
        val obfsPassword = params["obfs-password"] ?: ""
        val insecure = params["insecure"] == "1"

        val outbound = JSONObject().apply {
            put("type", "hysteria2")
            put("tag", name)
            put("server", host)
            put("server_port", port)
            put("password", password)

            put("tls", JSONObject().apply {
                put("enabled", true)
                put("server_name", sni)
                if (insecure) put("insecure", true)
            })

            if (obfsType.isNotEmpty()) {
                put("obfs", JSONObject().apply {
                    put("type", obfsType)
                    put("password", obfsPassword)
                })
            }
        }

        return ProxyConfig(tag = name, type = "hysteria2", server = host, serverPort = port, outbound = outbound)
    }

    // ─── TROJAN ─────────────────────────────────────────────

    private fun parseTrojan(uri: String): ProxyConfig? {
        // trojan://PASSWORD@HOST:PORT?params#NAME
        val parsed = Uri.parse(uri)
        val password = parsed.userInfo ?: return null
        val host = parsed.host ?: return null
        val port = parsed.port.takeIf { it > 0 } ?: 443
        val name = URLDecoder.decode(parsed.fragment ?: host, "UTF-8")
        val params = parseQueryParams(parsed)

        val security = params["security"] ?: "tls"
        val sni = params["sni"] ?: host
        val fp = params["fp"] ?: ""
        val alpn = params["alpn"]?.split(",")
        val transportType = params["type"] ?: "tcp"

        val outbound = JSONObject().apply {
            put("type", "trojan")
            put("tag", name)
            put("server", host)
            put("server_port", port)
            put("password", password)

            if (security != "none") {
                put("tls", JSONObject().apply {
                    put("enabled", true)
                    put("server_name", sni)
                    if (alpn != null) put("alpn", alpn.toJsonArray())
                    if (fp.isNotEmpty()) {
                        put("utls", JSONObject().apply {
                            put("enabled", true)
                            put("fingerprint", fp)
                        })
                    }
                })
            }

            putTransport(this, transportType, params)
        }

        return ProxyConfig(tag = name, type = "trojan", server = host, serverPort = port, outbound = outbound)
    }

    // ─── SHADOWSOCKS ────────────────────────────────────────

    private fun parseShadowsocks(uri: String): ProxyConfig? {
        // ss://BASE64(method:password)@HOST:PORT#NAME
        // or ss://BASE64(method:password@HOST:PORT)#NAME (SIP002)
        val withoutScheme = uri.removePrefix("ss://")
        val fragmentIdx = withoutScheme.lastIndexOf('#')
        val name = if (fragmentIdx >= 0) {
            URLDecoder.decode(withoutScheme.substring(fragmentIdx + 1), "UTF-8")
        } else ""
        val mainPart = if (fragmentIdx >= 0) withoutScheme.substring(0, fragmentIdx) else withoutScheme

        val atIdx = mainPart.lastIndexOf('@')
        val method: String
        val password: String
        val host: String
        val port: Int

        if (atIdx >= 0) {
            // method:password@host:port or base64@host:port
            val userInfo = mainPart.substring(0, atIdx)
            val serverPart = mainPart.substring(atIdx + 1)
            val decoded = tryBase64DecodeString(userInfo) ?: userInfo
            val colonIdx = decoded.indexOf(':')
            if (colonIdx < 0) return null
            method = decoded.substring(0, colonIdx)
            password = decoded.substring(colonIdx + 1)
            val (h, p) = parseHostPort(serverPart) ?: return null
            host = h; port = p
        } else {
            // Entire part is base64
            val decoded = tryBase64DecodeString(mainPart) ?: return null
            val atIdx2 = decoded.lastIndexOf('@')
            if (atIdx2 < 0) return null
            val colonIdx = decoded.indexOf(':')
            if (colonIdx < 0) return null
            method = decoded.substring(0, colonIdx)
            password = decoded.substring(colonIdx + 1, atIdx2)
            val (h, p) = parseHostPort(decoded.substring(atIdx2 + 1)) ?: return null
            host = h; port = p
        }

        val tag = name.ifEmpty { host }

        val outbound = JSONObject().apply {
            put("type", "shadowsocks")
            put("tag", tag)
            put("server", host)
            put("server_port", port)
            put("method", method)
            put("password", password)
        }

        return ProxyConfig(tag = tag, type = "shadowsocks", server = host, serverPort = port, outbound = outbound)
    }

    // ─── VMESS ───────────────────────────────────────────────

    private fun parseVmess(uri: String): ProxyConfig? {
        // vmess://BASE64_JSON
        val encoded = uri.removePrefix("vmess://")
        val json = try {
            JSONObject(String(Base64.decode(encoded, Base64.DEFAULT or Base64.NO_WRAP)))
        } catch (_: Exception) {
            try {
                JSONObject(String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP)))
            } catch (_: Exception) { return null }
        }

        val host = json.optString("add", "")
        val port = json.optString("port", "443").toIntOrNull() ?: 443
        val uuid = json.optString("id", "")
        val name = json.optString("ps", host)
        val aid = json.optString("aid", "0").toIntOrNull() ?: 0
        val security = json.optString("scy", "auto")
        val net = json.optString("net", "tcp")
        val tls = json.optString("tls", "")
        val sni = json.optString("sni", host)
        val fp = json.optString("fp", "")
        val alpn = json.optString("alpn", "")
        val path = json.optString("path", "")
        val headerHost = json.optString("host", "")

        if (host.isEmpty() || uuid.isEmpty()) return null

        val outbound = JSONObject().apply {
            put("type", "vmess")
            put("tag", name)
            put("server", host)
            put("server_port", port)
            put("uuid", uuid)
            put("security", security)
            put("alter_id", aid)

            // TLS
            if (tls == "tls") {
                put("tls", JSONObject().apply {
                    put("enabled", true)
                    put("server_name", sni)
                    if (alpn.isNotEmpty()) put("alpn", alpn.split(",").toJsonArray())
                    if (fp.isNotEmpty()) {
                        put("utls", JSONObject().apply {
                            put("enabled", true)
                            put("fingerprint", fp)
                        })
                    }
                })
            }

            // Transport
            val params = mutableMapOf<String, String>()
            if (path.isNotEmpty()) params["path"] = path
            if (headerHost.isNotEmpty()) params["host"] = headerHost
            json.optString("type", "").takeIf { it.isNotEmpty() && it != "none" }?.let {
                params["headerType"] = it
            }
            putTransport(this, net, params)
        }

        return ProxyConfig(tag = name, type = "vmess", server = host, serverPort = port, outbound = outbound)
    }

    // ─── XRAY JSON CONFIG ────────────────────────────────────

    private fun parseXrayConfig(config: JSONObject): List<ProxyConfig> {
        val outbounds = config.optJSONArray("outbounds") ?: return emptyList()
        val results = mutableListOf<ProxyConfig>()

        // Extract DNS from Xray config
        val dnsConfig = config.optJSONObject("dns")
        if (dnsConfig != null) {
            val servers = dnsConfig.optJSONArray("servers")
            if (servers != null && servers.length() > 0) {
                var remoteDns = "https://1.1.1.1/dns-query"
                var directDns = "1.1.1.1"
                for (i in 0 until servers.length()) {
                    val server = servers.optString(i, "")
                    if (server.startsWith("https://") || server.startsWith("https+local://")) {
                        remoteDns = server.replace("https+local://", "https://")
                        break
                    }
                }
                // Find a plain IP for direct DNS
                for (i in 0 until servers.length()) {
                    val server = servers.optString(i, "")
                    if (server.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
                        directDns = server
                        break
                    }
                }
                lastDns = SubscriptionDns(remoteDns, directDns)
            }
        }

        // Transports supported by sing-box (xhttp is Xray-only)
        val supportedTransports = setOf("tcp", "raw", "ws", "websocket", "grpc", "http", "h2", "httpupgrade", "quic")

        for (i in 0 until outbounds.length()) {
            val ob = outbounds.getJSONObject(i)
            val protocol = ob.optString("protocol")
            val tag = ob.optString("tag", protocol)
            val settings = ob.optJSONObject("settings") ?: continue
            val stream = ob.optJSONObject("streamSettings")

            // Skip outbounds with unsupported transports (e.g. xhttp)
            if (stream != null) {
                val network = stream.optString("network", "tcp")
                if (network !in supportedTransports) {
                    android.util.Log.w("ProxyParser", "Skipping outbound '$tag' with unsupported transport: $network")
                    continue
                }
            }

            when (protocol) {
                "vless", "vmess" -> {
                    val vnext = settings.optJSONArray("vnext")?.optJSONObject(0) ?: continue
                    val server = vnext.optString("address")
                    val port = vnext.optInt("port", 443)
                    val user = vnext.optJSONArray("users")?.optJSONObject(0) ?: continue
                    val uuid = user.optString("id")
                    val flow = user.optString("flow", "")

                    val singboxOb = JSONObject().apply {
                        put("type", protocol)
                        put("tag", tag)
                        put("server", server)
                        put("server_port", port)
                        if (protocol == "vless") {
                            put("uuid", uuid)
                            if (flow.isNotEmpty()) put("flow", flow)
                            put("packet_encoding", "xudp")
                        } else {
                            put("uuid", uuid)
                            put("security", user.optString("security", "auto"))
                            put("alter_id", user.optInt("alterId", 0))
                        }

                        // TLS / Reality
                        if (stream != null) {
                            putXrayTls(this, stream)
                            putXrayTransport(this, stream)
                        }
                    }
                    results.add(ProxyConfig(tag, protocol, server, port, singboxOb))
                }
                "trojan" -> {
                    val servers = settings.optJSONArray("servers")?.optJSONObject(0) ?: continue
                    val server = servers.optString("address")
                    val port = servers.optInt("port", 443)
                    val password = servers.optString("password")

                    val singboxOb = JSONObject().apply {
                        put("type", "trojan")
                        put("tag", tag)
                        put("server", server)
                        put("server_port", port)
                        put("password", password)
                        if (stream != null) {
                            putXrayTls(this, stream)
                            putXrayTransport(this, stream)
                        }
                    }
                    results.add(ProxyConfig(tag, "trojan", server, port, singboxOb))
                }
                "shadowsocks" -> {
                    val servers = settings.optJSONArray("servers")?.optJSONObject(0) ?: continue
                    val server = servers.optString("address")
                    val port = servers.optInt("port", 443)
                    val method = servers.optString("method")
                    val password = servers.optString("password")

                    val singboxOb = JSONObject().apply {
                        put("type", "shadowsocks")
                        put("tag", tag)
                        put("server", server)
                        put("server_port", port)
                        put("method", method)
                        put("password", password)
                    }
                    results.add(ProxyConfig(tag, "shadowsocks", server, port, singboxOb))
                }
            }
        }
        return results
    }

    private fun putXrayTls(obj: JSONObject, stream: JSONObject) {
        val security = stream.optString("security", "")
        when (security) {
            "tls" -> {
                val tls = stream.optJSONObject("tlsSettings") ?: JSONObject()
                obj.put("tls", JSONObject().apply {
                    put("enabled", true)
                    put("server_name", tls.optString("serverName", ""))
                    val insecure = tls.optBoolean("allowInsecure", false)
                    if (insecure) put("insecure", true)
                    val fp = tls.optString("fingerprint", "")
                    if (fp.isNotEmpty()) {
                        put("utls", JSONObject().apply {
                            put("enabled", true)
                            put("fingerprint", fp)
                        })
                    }
                    val alpn = tls.optJSONArray("alpn")
                    if (alpn != null && alpn.length() > 0) {
                        put("alpn", alpn)
                    }
                })
            }
            "reality" -> {
                val reality = stream.optJSONObject("realitySettings") ?: JSONObject()
                obj.put("tls", JSONObject().apply {
                    put("enabled", true)
                    put("server_name", reality.optString("serverName", ""))
                    put("reality", JSONObject().apply {
                        put("enabled", true)
                        put("public_key", reality.optString("publicKey", ""))
                        put("short_id", reality.optString("shortId", ""))
                    })
                    val fp = reality.optString("fingerprint", "chrome")
                    put("utls", JSONObject().apply {
                        put("enabled", true)
                        put("fingerprint", fp)
                    })
                })
            }
        }
    }

    private fun putXrayTransport(obj: JSONObject, stream: JSONObject) {
        when (stream.optString("network", "tcp")) {
            "ws" -> {
                val ws = stream.optJSONObject("wsSettings") ?: return
                obj.put("transport", JSONObject().apply {
                    put("type", "ws")
                    ws.optString("path", "").takeIf { it.isNotEmpty() }?.let { put("path", it) }
                    ws.optJSONObject("headers")?.optString("Host")?.takeIf { it.isNotEmpty() }?.let {
                        put("headers", JSONObject().put("Host", it))
                    }
                })
            }
            "grpc" -> {
                val grpc = stream.optJSONObject("grpcSettings") ?: return
                obj.put("transport", JSONObject().apply {
                    put("type", "grpc")
                    grpc.optString("serviceName", "").takeIf { it.isNotEmpty() }?.let { put("service_name", it) }
                })
            }
            "xhttp", "splithttp" -> {
                val xhttp = stream.optJSONObject("xhttpSettings")
                    ?: stream.optJSONObject("xHTTPSettings")
                    ?: stream.optJSONObject("splithttpSettings") ?: return
                obj.put("transport", JSONObject().apply {
                    put("type", "xhttp")
                    xhttp.optString("path", "").takeIf { it.isNotEmpty() }?.let { put("path", it) }
                    xhttp.optString("host", "").takeIf { it.isNotEmpty() }?.let { put("host", it) }
                    xhttp.optString("mode", "").takeIf { it.isNotEmpty() }?.let { put("mode", it) }
                })
            }
            "httpupgrade" -> {
                val hu = stream.optJSONObject("httpupgradeSettings") ?: return
                obj.put("transport", JSONObject().apply {
                    put("type", "httpupgrade")
                    hu.optString("path", "").takeIf { it.isNotEmpty() }?.let { put("path", it) }
                    hu.optString("host", "").takeIf { it.isNotEmpty() }?.let { put("host", it) }
                })
            }
            "h2" -> {
                val h2 = stream.optJSONObject("httpSettings") ?: return
                obj.put("transport", JSONObject().apply {
                    put("type", "http")
                    h2.optString("path", "").takeIf { it.isNotEmpty() }?.let { put("path", it) }
                    h2.optJSONArray("host")?.let { put("host", it) }
                })
            }
        }
    }

    // ─── HELPERS ────────────────────────────────────────────

    private fun parseQueryParams(uri: Uri): Map<String, String> {
        val params = mutableMapOf<String, String>()
        uri.queryParameterNames?.forEach { key ->
            uri.getQueryParameter(key)?.let { params[key] = it }
        }
        return params
    }

    private fun putTransport(obj: JSONObject, type: String, params: Map<String, String>) {
        when (type) {
            "ws" -> obj.put("transport", JSONObject().apply {
                put("type", "ws")
                params["host"]?.let { put("headers", JSONObject().put("Host", it)) }
                params["path"]?.let { put("path", it) }
            })
            "grpc" -> obj.put("transport", JSONObject().apply {
                put("type", "grpc")
                params["serviceName"]?.let { put("service_name", it) }
            })
            "http", "h2" -> obj.put("transport", JSONObject().apply {
                put("type", "http")
                params["host"]?.let { put("host", listOf(it).toJsonArray()) }
                params["path"]?.let { put("path", it) }
            })
            "httpupgrade" -> obj.put("transport", JSONObject().apply {
                put("type", "httpupgrade")
                params["host"]?.let { put("host", it) }
                params["path"]?.let { put("path", it) }
            })
            "xhttp", "splithttp" -> obj.put("transport", JSONObject().apply {
                put("type", "xhttp")
                params["host"]?.let { put("host", it) }
                params["path"]?.let { put("path", it) }
                params["mode"]?.let { put("mode", it) }
            })
            // "tcp" -> no transport needed
        }
    }

    private fun parseHostPort(s: String): Pair<String, Int>? {
        // Handle [ipv6]:port
        if (s.startsWith("[")) {
            val closeBracket = s.indexOf(']')
            if (closeBracket < 0) return null
            val host = s.substring(1, closeBracket)
            val port = if (closeBracket + 1 < s.length && s[closeBracket + 1] == ':') {
                s.substring(closeBracket + 2).toIntOrNull() ?: 443
            } else 443
            return host to port
        }
        val lastColon = s.lastIndexOf(':')
        if (lastColon < 0) return s to 443
        val host = s.substring(0, lastColon)
        val port = s.substring(lastColon + 1).toIntOrNull() ?: 443
        return host to port
    }

    private fun tryBase64DecodeString(input: String): String? {
        return try {
            String(Base64.decode(input, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        } catch (_: Exception) {
            try {
                String(Base64.decode(input, Base64.DEFAULT or Base64.NO_WRAP))
            } catch (_: Exception) { null }
        }
    }

    private fun List<String>.toJsonArray(): org.json.JSONArray {
        val arr = org.json.JSONArray()
        forEach { arr.put(it) }
        return arr
    }
}
