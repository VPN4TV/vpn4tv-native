package com.vpn4tv.app.converter

import android.content.Context
import android.os.Build
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Metadata extracted from the subscription HTTP response headers.
 *
 * Hiddify / sing-box have a de-facto standard where the subscription server
 * advertises profile name, update interval, support URL, and per-user quota
 * via response headers. We honour all of them; the body is still the raw
 * proxy list that [ProxyParser] consumes.
 */
data class SubscriptionResponse(
    val body: String,
    /** `profile-title` header, base64-decoded if it had the `base64:` prefix. */
    val title: String?,
    /** `profile-update-interval` header value in hours. */
    val updateIntervalHours: Int?,
    /** `profile-web-page-url` header — provider's dashboard / billing page. */
    val webPageUrl: String?,
    /** `support-url` header — contact for the provider's support. */
    val supportUrl: String?,
    /** `subscription-userinfo` header, pre-parsed into fields. */
    val userInfo: SubscriptionUserInfo?,
)

data class SubscriptionUserInfo(
    val upload: Long?,
    val download: Long?,
    val total: Long?,
    val expireEpochSec: Long?,
) {
    fun toJson(): String = org.json.JSONObject().apply {
        upload?.let { put("u", it) }
        download?.let { put("d", it) }
        total?.let { put("t", it) }
        expireEpochSec?.let { put("e", it) }
    }.toString()

    companion object {
        fun fromJson(s: String?): SubscriptionUserInfo? {
            if (s.isNullOrBlank()) return null
            return try {
                val j = org.json.JSONObject(s)
                SubscriptionUserInfo(
                    upload = if (j.has("u")) j.getLong("u") else null,
                    download = if (j.has("d")) j.getLong("d") else null,
                    total = if (j.has("t")) j.getLong("t") else null,
                    expireEpochSec = if (j.has("e")) j.getLong("e") else null,
                )
            } catch (_: Exception) { null }
        }
    }
}

object HwidService {
    private const val PREFS = "hwid"
    private const val KEY = "device_hwid"
    private var cached: String? = null

    fun getHwid(context: Context): String {
        cached?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var hwid = prefs.getString(KEY, null)
        if (hwid.isNullOrEmpty()) {
            hwid = UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString(KEY, hwid).apply()
        }
        cached = hwid
        return hwid
    }

    /**
     * Download subscription content + honour the Hiddify metadata headers.
     * Callers that only want the body can use [downloadSubscription].
     */
    fun fetchSubscription(context: Context, url: String): SubscriptionResponse {
        val conn = openConnectionWithDnsFallback(url)
        conn.setRequestProperty("x-hwid", getHwid(context))
        conn.setRequestProperty("x-device-os", "Android")
        conn.setRequestProperty("x-ver-os", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        conn.setRequestProperty("User-Agent", "VPN4TV-Native/0.1.0")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        // Cap body at 2 MB — a real subscription is at most a few hundred
        // KB. Without this, a misconfigured server returning a huge
        // response (or an accidental redirect to a binary file) blows up
        // the heap with OutOfMemoryError inside ProxyParser.parseSubscription
        // (Vitals: 50103, 1 user).
        val maxBytes = 2 * 1024 * 1024
        val input = conn.inputStream
        val buf = ByteArray(maxBytes + 1)
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n <= 0) break
            read += n
        }
        if (read > maxBytes) {
            android.util.Log.w("HwidService", "Subscription response >${maxBytes / 1024 / 1024} MB, truncating (proxy links are usually in the first few KB)")
            read = maxBytes
        }
        val body = String(buf, 0, read, Charsets.UTF_8)
        return SubscriptionResponse(
            body = body,
            title = decodeTitleHeader(conn.getHeaderField("profile-title")),
            updateIntervalHours = conn.getHeaderField("profile-update-interval")?.toIntOrNull(),
            webPageUrl = conn.getHeaderField("profile-web-page-url"),
            supportUrl = conn.getHeaderField("support-url"),
            userInfo = parseUserInfo(conn.getHeaderField("subscription-userinfo")),
        )
    }

    /** Body-only shim so existing callers keep compiling during the transition. */
    fun downloadSubscription(context: Context, url: String): String =
        fetchSubscription(context, url).body

    /**
     * Open an HTTP connection with DNS fallback. If system DNS fails
     * (UnknownHostException — common when Russian ISPs block VPN-related
     * domains), resolve the hostname via the DoH server that [DnsProber]
     * found to work, then connect by IP with the Host header set manually.
     */
    fun openConnectionWithDnsFallbackPublic(url: String): HttpURLConnection =
        openConnectionWithDnsFallback(url)

    private fun openConnectionWithDnsFallback(url: String): HttpURLConnection {
        return try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: java.net.UnknownHostException) {
            android.util.Log.w("HwidService", "System DNS failed for $url, trying DoH fallback")
            val parsed = URL(url)
            val host = parsed.host
            val ip = resolveViaDoH(host)
                ?: throw java.net.UnknownHostException("DoH fallback also failed for $host")
            // Replace hostname with resolved IP, set Host header manually
            val directUrl = url.replace(host, ip)
            val conn = URL(directUrl).openConnection() as HttpURLConnection
            conn.setRequestProperty("Host", host)
            // For HTTPS, SNI is derived from the URL host (which is now an IP).
            // HttpsURLConnection uses Host header for SNI when the URL host
            // is an IP — but some implementations don't. To be safe, set
            // the hostname verifier to accept the cert for our original host.
            if (conn is javax.net.ssl.HttpsURLConnection) {
                conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { hostname, session ->
                    hostname == ip || hostname == host ||
                        javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)
                }
            }
            android.util.Log.i("HwidService", "DoH resolved $host → $ip, connecting directly")
            conn
        }
    }

    /** DoH endpoints for hostname resolution fallback (JSON API).
     *  Tried in order; each wrapped in try/catch so failures just skip.
     *  Yandex is excluded — only supports RFC 8484 wireformat, no JSON. */
    private val JSON_DOH_ENDPOINTS = listOf(
        "https://dns.adguard-dns.com/resolve",     // AdGuard — JSON API at /resolve
        "https://dns.google/resolve",              // Google — JSON API at /resolve
        "https://1.0.0.1/dns-query",               // Cloudflare secondary — JSON via Accept header
        "https://8.8.8.8/dns-query",               // Google by IP — JSON via Accept header
        "https://one.one.one.one/dns-query",       // Cloudflare alt hostname
        "https://1.1.1.1/dns-query",               // Cloudflare primary
    )

    private fun resolveViaDoH(hostname: String): String? {
        for (dohUrl in JSON_DOH_ENDPOINTS) {
            val result = tryResolveDoH(dohUrl, hostname)
            if (result != null) return result
        }
        return null
    }

    private fun tryResolveDoH(dohUrl: String, hostname: String): String? {
        return try {
            val queryUrl = "$dohUrl?name=$hostname&type=A"
            val conn = URL(queryUrl).openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/dns-json")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            // Parse DNS-over-HTTPS JSON response (RFC 8484 / Google JSON API)
            val json = org.json.JSONObject(body)
            val answers = json.optJSONArray("Answer") ?: return null
            for (i in 0 until answers.length()) {
                val answer = answers.getJSONObject(i)
                if (answer.optInt("type") == 1) { // A record
                    return answer.optString("data")
                }
            }
            null
        } catch (e: Exception) {
            android.util.Log.w("HwidService", "DoH resolve failed for $hostname: ${e.message}")
            null
        }
    }

    private const val SUB_INFO_PREFS = "sub_userinfo"

    fun saveUserInfo(context: Context, profileId: Long, info: SubscriptionUserInfo?) {
        context.getSharedPreferences(SUB_INFO_PREFS, Context.MODE_PRIVATE)
            .edit().putString("p$profileId", info?.toJson()).apply()
    }

    fun loadUserInfo(context: Context, profileId: Long): SubscriptionUserInfo? {
        val s = context.getSharedPreferences(SUB_INFO_PREFS, Context.MODE_PRIVATE)
            .getString("p$profileId", null)
        return SubscriptionUserInfo.fromJson(s)
    }

    /**
     * The `profile-title` header may be plain UTF-8 or `base64:<payload>`,
     * where the payload is base64(utf-8). Hiddify uses the base64 form for
     * titles that contain characters the HTTP spec doesn't allow unescaped.
     */
    private fun decodeTitleHeader(raw: String?): String? {
        val trimmed = raw?.trim()
        if (trimmed.isNullOrEmpty()) return null
        if (!trimmed.startsWith("base64:", ignoreCase = true)) return trimmed
        return try {
            val payload = trimmed.removePrefix("base64:").removePrefix("BASE64:").trim()
            String(Base64.decode(payload, Base64.DEFAULT), Charsets.UTF_8).trim().ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    /** Parses `upload=N; download=N; total=N; expire=epoch_sec` into fields. */
    private fun parseUserInfo(raw: String?): SubscriptionUserInfo? {
        val trimmed = raw?.trim()
        if (trimmed.isNullOrEmpty()) return null
        val pairs = trimmed.split(";")
            .mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) null
                else part.substring(0, eq).trim().lowercase() to part.substring(eq + 1).trim()
            }
            .toMap()
        return SubscriptionUserInfo(
            upload = pairs["upload"]?.toLongOrNull(),
            download = pairs["download"]?.toLongOrNull(),
            total = pairs["total"]?.toLongOrNull(),
            expireEpochSec = pairs["expire"]?.toLongOrNull(),
        )
    }
}
