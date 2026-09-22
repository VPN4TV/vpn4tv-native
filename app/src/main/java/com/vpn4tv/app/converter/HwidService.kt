package com.vpn4tv.app.converter

import android.content.Context
import android.os.Build
import android.util.Base64
import com.vpn4tv.app.BuildConfig
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

    private const val MAX_ATTEMPTS = 10

    data class RetryProgress(val attempt: Int, val maxAttempts: Int, val mirror: Boolean)

    /**
     * Download subscription content + honour the Hiddify metadata headers.
     * Retries each endpoint on SocketTimeoutException (up to [MAX_ATTEMPTS])
     * and falls back to the bell.a4e.ar mirror if api.vpn4tv.com is
     * unreachable from the user's network (ISP blocks, TSPU interference).
     * `onProgress` fires on every attempt so the UI can show a counter.
     */
    fun fetchSubscription(
        context: Context,
        url: String,
        onProgress: ((RetryProgress) -> Unit)? = null,
    ): SubscriptionResponse {
        val endpoints = endpointsWithMirror(url)
        var lastError: Exception? = null
        for ((endpointIdx, endpoint) in endpoints.withIndex()) {
            val isMirror = endpointIdx > 0
            for (attempt in 1..MAX_ATTEMPTS) {
                onProgress?.invoke(RetryProgress(attempt, MAX_ATTEMPTS, isMirror))
                try {
                    return fetchSubscriptionOnce(context, endpoint)
                } catch (e: java.net.SocketTimeoutException) {
                    lastError = e
                    android.util.Log.w("HwidService", "fetchSubscription timeout ${attempt}/${MAX_ATTEMPTS} @ $endpoint")
                    if (attempt < MAX_ATTEMPTS) Thread.sleep(1000L)
                } catch (e: java.io.IOException) {
                    // Non-timeout IO (reset, 5xx) — jump to mirror without retries
                    lastError = e
                    android.util.Log.w("HwidService", "fetchSubscription IO error at $endpoint: ${e.message}")
                    break
                }
            }
        }
        throw lastError ?: java.net.SocketTimeoutException("all subscription endpoints exhausted")
    }

    private fun fetchSubscriptionOnce(context: Context, url: String): SubscriptionResponse {
        val conn = openConnectionWithDnsFallback(url)
        conn.setRequestProperty("x-hwid", getHwid(context))
        conn.setRequestProperty("x-device-os", "Android")
        conn.setRequestProperty("x-ver-os", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        // The app version decides what the backend may serve: naive needs a
        // client that sets route.default_domain_resolver (5.2.4+), xhttp needs
        // one that passes the transport's extra through (5.2.5+). Until now the
        // version went nowhere — the user agent was hardcoded to 0.1.0 and
        // x-ver-os carries the Android version — so the backend had to gate by
        // platform and a hand-maintained allowlist.
        conn.setRequestProperty("x-app-ver", BuildConfig.VERSION_NAME)
        conn.setRequestProperty("x-app-build", BuildConfig.VERSION_CODE.toString())
        conn.setRequestProperty("User-Agent", "VPN4TV-Native/${BuildConfig.VERSION_NAME}")
        conn.connectTimeout = 30000
        conn.readTimeout = 30000
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

    /**
     * Primary endpoint plus mirror for ISP-blocked users. bell.a4e.ar proxies
     * requests to api.vpn4tv.com; when the primary is unreachable (Russian
     * TSPU dropping TLS to api.vpn4tv.com, SocketTimeoutException cluster
     * on vc50313 AddProfile flow), the mirror delivers the same payload.
     */
    fun endpointsWithMirror(url: String): List<String> {
        val mirror = url.replace("//api.vpn4tv.com/", "//bell.a4e.ar/")
        return if (mirror != url) listOf(url, mirror) else listOf(url)
    }

    /** Body-only shim so existing callers keep compiling during the transition. */
    fun downloadSubscription(context: Context, url: String): String =
        fetchSubscription(context, url).body

    /**
     * Open an HTTP connection with DNS fallback. If system DNS fails
     * (UnknownHostException — common when Russian ISPs block VPN-related
     * domains), resolve the hostname via the DoH chain and connect by IP
     * with the Host header set manually. For OUR OWN infra hosts there is a
     * final hardcoded anchor: if even the DoH chain is dead, connect to
     * bell's static IP with SNI/Host pinned to bell.a4e.ar.
     */
    fun openConnectionWithDnsFallbackPublic(url: String): HttpURLConnection =
        openConnectionWithDnsFallback(url)

    /** Last-resort anchor for OUR infrastructure. bell.a4e.ar mirrors
     *  api.vpn4tv.com path-for-path (see [endpointsWithMirror]), so both
     *  hosts can be served from bell's static IP with SNI bell.a4e.ar. */
    private const val INFRA_IP = "152.53.207.6"
    private const val INFRA_SNI = "bell.a4e.ar"
    private val INFRA_HOSTS = setOf("api.vpn4tv.com", "bell.a4e.ar")


    /**
     * TLS trust for our own hosts on old TVs. Android before 7.1 has no
     * ISRG Root X1 in its store, so every Let's Encrypt certificate — bell's
     * included, and bell is the last-resort mirror of api.vpn4tv.com — is
     * "Unacceptable certificate: CN=YR2, O=Let's Encrypt" there. Verification
     * stays: without it whoever intercepts DNS or the address hands the user
     * a subscription with their own servers. The two ISRG roots are simply
     * trusted alongside the system store. Fingerprints (SHA-256):
     *   X1 96:BC:EC:06:26:49:76:F3:74:60:77:9A:CF:28:C5:A7:CF:E8:A3:C0:AA:E1:1A:8F:FC:EE:05:C0:BD:DF:08:C6
     *   X2 69:72:9B:8E:15:A8:6E:FC:17:7A:57:AF:B7:17:1D:FC:64:AD:D2:8C:2F:CA:8C:F1:50:7E:34:45:3C:CB:14:70
     */
    object TrustedRoots {
        private const val ISRG_ROOT_X1 = """
-----BEGIN CERTIFICATE-----
MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAw
TzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2Vh
cmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMTUwNjA0MTEwNDM4
WhcNMzUwNjA0MTEwNDM4WjBPMQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJu
ZXQgU2VjdXJpdHkgUmVzZWFyY2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBY
MTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAK3oJHP0FDfzm54rVygc
h77ct984kIxuPOZXoHj3dcKi/vVqbvYATyjb3miGbESTtrFj/RQSa78f0uoxmyF+
0TM8ukj13Xnfs7j/EvEhmkvBioZxaUpmZmyPfjxwv60pIgbz5MDmgK7iS4+3mX6U
A5/TR5d8mUgjU+g4rk8Kb4Mu0UlXjIB0ttov0DiNewNwIRt18jA8+o+u3dpjq+sW
T8KOEUt+zwvo/7V3LvSye0rgTBIlDHCNAymg4VMk7BPZ7hm/ELNKjD+Jo2FR3qyH
B5T0Y3HsLuJvW5iB4YlcNHlsdu87kGJ55tukmi8mxdAQ4Q7e2RCOFvu396j3x+UC
B5iPNgiV5+I3lg02dZ77DnKxHZu8A/lJBdiB3QW0KtZB6awBdpUKD9jf1b0SHzUv
KBds0pjBqAlkd25HN7rOrFleaJ1/ctaJxQZBKT5ZPt0m9STJEadao0xAH0ahmbWn
OlFuhjuefXKnEgV4We0+UXgVCwOPjdAvBbI+e0ocS3MFEvzG6uBQE3xDk3SzynTn
jh8BCNAw1FtxNrQHusEwMFxIt4I7mKZ9YIqioymCzLq9gwQbooMDQaHWBfEbwrbw
qHyGO0aoSCqI3Haadr8faqU9GY/rOPNk3sgrDQoo//fb4hVC1CLQJ13hef4Y53CI
rU7m2Ys6xt0nUW7/vGT1M0NPAgMBAAGjQjBAMA4GA1UdDwEB/wQEAwIBBjAPBgNV
HRMBAf8EBTADAQH/MB0GA1UdDgQWBBR5tFnme7bl5AFzgAiIyBpY9umbbjANBgkq
hkiG9w0BAQsFAAOCAgEAVR9YqbyyqFDQDLHYGmkgJykIrGF1XIpu+ILlaS/V9lZL
ubhzEFnTIZd+50xx+7LSYK05qAvqFyFWhfFQDlnrzuBZ6brJFe+GnY+EgPbk6ZGQ
3BebYhtF8GaV0nxvwuo77x/Py9auJ/GpsMiu/X1+mvoiBOv/2X/qkSsisRcOj/KK
NFtY2PwByVS5uCbMiogziUwthDyC3+6WVwW6LLv3xLfHTjuCvjHIInNzktHCgKQ5
ORAzI4JMPJ+GslWYHb4phowim57iaztXOoJwTdwJx4nLCgdNbOhdjsnvzqvHu7Ur
TkXWStAmzOVyyghqpZXjFaH3pO3JLF+l+/+sKAIuvtd7u+Nxe5AW0wdeRlN8NwdC
jNPElpzVmbUq4JUagEiuTDkHzsxHpFKVK7q4+63SM1N95R1NbdWhscdCb+ZAJzVc
oyi3B43njTOQ5yOf+1CceWxG1bQVs5ZufpsMljq4Ui0/1lvh+wjChP4kqKOJ2qxq
4RgqsahDYVvTH9w7jXbyLeiNdd8XM2w9U/t7y0Ff/9yi0GE44Za4rF2LN9d11TPA
mRGunUHBcnWEvgJBQl9nJEiU0Zsnvgc/ubhPgXRR4Xq37Z0j4r7g1SgEEzwxA57d
emyPxgcYxn/eR44/KJ4EBs+lVDR3veyJm+kXQ99b21/+jh5Xos1AnX5iItreGCc=
-----END CERTIFICATE-----
"""
        private const val ISRG_ROOT_X2 = """
-----BEGIN CERTIFICATE-----
MIICGzCCAaGgAwIBAgIQQdKd0XLq7qeAwSxs6S+HUjAKBggqhkjOPQQDAzBPMQsw
CQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJuZXQgU2VjdXJpdHkgUmVzZWFyY2gg
R3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBYMjAeFw0yMDA5MDQwMDAwMDBaFw00
MDA5MTcxNjAwMDBaME8xCzAJBgNVBAYTAlVTMSkwJwYDVQQKEyBJbnRlcm5ldCBT
ZWN1cml0eSBSZXNlYXJjaCBHcm91cDEVMBMGA1UEAxMMSVNSRyBSb290IFgyMHYw
EAYHKoZIzj0CAQYFK4EEACIDYgAEzZvVn4CDCuwJSvMWSj5cz3es3mcFDR0HttwW
+1qLFNvicWDEukWVEYmO6gbf9yoWHKS5xcUy4APgHoIYOIvXRdgKam7mAHf7AlF9
ItgKbppbd9/w+kHsOdx1ymgHDB/qo0IwQDAOBgNVHQ8BAf8EBAMCAQYwDwYDVR0T
AQH/BAUwAwEB/zAdBgNVHQ4EFgQUfEKWrt5LSDv6kviejM9ti6lyN5UwCgYIKoZI
zj0EAwMDaAAwZQIwe3lORlCEwkSHRhtFcP9Ymd70/aTSVaYgLXTWNLxBo1BfASdW
tL4ndQavEi51mI38AjEAi/V3bNTIZargCyzuFJ0nN6T5U6VR5CmD1/iQMVtCnwr1
/q4AaOeMSQ+2b1tbFfLn
-----END CERTIFICATE-----
"""

        private fun systemTrustManager(): javax.net.ssl.X509TrustManager {
            val factory = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as java.security.KeyStore?)
            return factory.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>().first()
        }

        private fun bundledTrustManager(): javax.net.ssl.X509TrustManager {
            val certificates = java.security.cert.CertificateFactory.getInstance("X.509")
            val store = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType())
            store.load(null, null)
            listOf(ISRG_ROOT_X1, ISRG_ROOT_X2).forEachIndexed { index, pem ->
                store.setCertificateEntry("isrg-$index", certificates.generateCertificate(pem.trim().byteInputStream()))
            }
            val factory = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
            factory.init(store)
            return factory.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>().first()
        }

        /** System store first; the bundled roots only when the system says no. */
        val trustManager: javax.net.ssl.X509TrustManager by lazy {
            val system = systemTrustManager()
            val bundled = bundledTrustManager()
            object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) =
                    system.checkClientTrusted(chain, authType)
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
                    try {
                        system.checkServerTrusted(chain, authType)
                    } catch (systemRefused: java.security.cert.CertificateException) {
                        try {
                            bundled.checkServerTrusted(chain, authType)
                        } catch (_: java.security.cert.CertificateException) {
                            throw systemRefused
                        }
                    }
                }
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> =
                    system.acceptedIssuers + bundled.acceptedIssuers
            }
        }

        val socketFactory: javax.net.ssl.SSLSocketFactory by lazy {
            val context = javax.net.ssl.SSLContext.getInstance("TLS")
            context.init(null, arrayOf(trustManager), null)
            context.socketFactory
        }

        fun apply(connection: HttpURLConnection): HttpURLConnection {
            if (connection is javax.net.ssl.HttpsURLConnection) {
                connection.sslSocketFactory = socketFactory
            }
            return connection
        }
    }

    private fun openConnectionWithDnsFallback(url: String): HttpURLConnection {
        val host = URL(url).host
        return try {
            // Force resolution NOW: openConnection() alone never resolves, so
            // without this the UnknownHostException surfaced later at
            // getInputStream() in the caller — and this whole fallback path
            // never actually ran (the 2026-07 "subscription won't refresh"
            // incident shipped over exactly that dead code).
            java.net.InetAddress.getByName(host)
            TrustedRoots.apply(URL(url).openConnection() as HttpURLConnection)
        } catch (e: java.net.UnknownHostException) {
            android.util.Log.w("HwidService", "System DNS failed for $host, trying DoH chain")
            val ip = resolveViaDoH(host)
            when {
                ip != null -> {
                    // Replace hostname with resolved IP, set Host header manually
                    val conn = TrustedRoots.apply(URL(url.replace(host, ip)).openConnection() as HttpURLConnection)
                    conn.setRequestProperty("Host", host)
                    // For HTTPS, SNI is derived from the URL host (now an IP).
                    // Accept the cert for the original hostname instead.
                    if (conn is javax.net.ssl.HttpsURLConnection) {
                        conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { hostname, session ->
                            hostname == ip || hostname == host ||
                                javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)
                        }
                    }
                    android.util.Log.i("HwidService", "DoH resolved $host → $ip, connecting directly")
                    conn
                }
                host in INFRA_HOSTS -> {
                    // Total DNS blackout (system + whole DoH chain). Our infra
                    // has a stable address — pin it, with proper SNI so nginx
                    // picks the right vhost/cert.
                    android.util.Log.w("HwidService", "DoH chain dead too — pinning $host → $INFRA_IP (sni=$INFRA_SNI)")
                    infraPinnedConnection(url)
                }
                else -> throw java.net.UnknownHostException("DoH fallback also failed for $host")
            }
        }
    }

    /** Connect to [INFRA_IP] serving the same path, TLS with SNI [INFRA_SNI]
     *  and certificate checked against [INFRA_SNI] (not the raw IP). */
    private fun infraPinnedConnection(originalUrl: String): HttpURLConnection {
        val parsed = URL(originalUrl)
        val conn = URL("https://$INFRA_IP${parsed.file}").openConnection() as HttpURLConnection
        conn.setRequestProperty("Host", INFRA_SNI)
        if (conn is javax.net.ssl.HttpsURLConnection) {
            conn.sslSocketFactory = SniSocketFactory(INFRA_SNI)
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, session ->
                javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(INFRA_SNI, session)
            }
        }
        return conn
    }

    /**
     * SSLSocketFactory that pins the TLS SNI (and certificate identity) to a
     * fixed hostname regardless of the URL host. Android's conscrypt derives
     * SNI from the `host` argument of createSocket(socket, host, port, _) —
     * when the URL host is a raw IP, overriding that argument is the only way
     * to still present a proper server_name in the ClientHello.
     */
    private class SniSocketFactory(private val sni: String) : javax.net.ssl.SSLSocketFactory() {
        private val d = TrustedRoots.socketFactory
        override fun getDefaultCipherSuites(): Array<String> = d.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = d.supportedCipherSuites
        override fun createSocket(s: java.net.Socket, host: String, port: Int, autoClose: Boolean): java.net.Socket =
            d.createSocket(s, sni, port, autoClose)  // ← the host arg drives SNI
        override fun createSocket(host: String, port: Int): java.net.Socket =
            d.createSocket(host, port)
        override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int): java.net.Socket =
            d.createSocket(host, port, localHost, localPort)
        override fun createSocket(host: java.net.InetAddress, port: Int): java.net.Socket =
            d.createSocket(host, port)
        override fun createSocket(address: java.net.InetAddress, port: Int, localAddress: java.net.InetAddress, localPort: Int): java.net.Socket =
            d.createSocket(address, port, localAddress, localPort)
    }

    /** In-app DoH resolution chain. The endpoint list lives in
     *  [com.vpn4tv.app.utils.DnsProviders] — the single registry shared with
     *  DnsProber — so a provider added/reordered there propagates everywhere.
     *  2026-07: RKN killed Google AND Cloudflare DoH the same day; Quad9
     *  survived. Quad9 has no JSON API on :443, hence wireformat (RFC 8484)
     *  support: `wire = true` entries query with ?dns=<base64url> and parse
     *  the binary answer, which every DoH server speaks. Yandex is excluded
     *  by the registry's lastResort flag — resolving circumvention domains
     *  through a RU-jurisdiction resolver leaks exactly the wrong queries. */

    /** All A records from the first DoH endpoint in the chain that answers. */
    fun resolveAllViaDoH(hostname: String): List<String> {
        for (ep in com.vpn4tv.app.utils.DnsProviders.resolutionEndpoints) {
            val ips = if (ep.wire) tryResolveDoHWire(ep.url, hostname)
                      else tryResolveDoHJson(ep.url, hostname)
            if (ips.isNotEmpty()) return ips
        }
        return emptyList()
    }

    private fun resolveViaDoH(hostname: String): String? =
        resolveAllViaDoH(hostname).firstOrNull()

    private fun tryResolveDoHJson(dohUrl: String, hostname: String): List<String> {
        return try {
            val queryUrl = "$dohUrl?name=$hostname&type=A"
            val conn = URL(queryUrl).openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/dns-json")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            // Parse DNS-over-HTTPS JSON response (Google JSON API shape)
            val json = org.json.JSONObject(body)
            val answers = json.optJSONArray("Answer") ?: return emptyList()
            val ips = mutableListOf<String>()
            for (i in 0 until answers.length()) {
                val answer = answers.getJSONObject(i)
                if (answer.optInt("type") == 1) { // A record
                    val ip = answer.optString("data")
                    if (ip.isNotBlank()) ips.add(ip)
                }
            }
            ips
        } catch (e: Exception) {
            android.util.Log.w("HwidService", "DoH(json) $dohUrl failed for $hostname: ${e.message}")
            emptyList()
        }
    }

    /** RFC 8484 wireformat query: GET ?dns=<base64url(query)>. */
    private fun tryResolveDoHWire(dohUrl: String, hostname: String): List<String> {
        return try {
            val q = Base64.encodeToString(
                buildDnsQuery(hostname),
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
            )
            val conn = URL("$dohUrl?dns=$q").openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/dns-message")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val body = conn.inputStream.readBytes()
            conn.disconnect()
            parseDnsAnswers(body)
        } catch (e: Exception) {
            android.util.Log.w("HwidService", "DoH(wire) $dohUrl failed for $hostname: ${e.message}")
            emptyList()
        }
    }

    /** Minimal DNS query packet: one A question, RD set. */
    private fun buildDnsQuery(hostname: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val id = (Math.random() * 0xFFFF).toInt()
        out.write(id shr 8); out.write(id and 0xFF)
        out.write(0x01); out.write(0x00)   // flags: RD
        out.write(0x00); out.write(0x01)   // QDCOUNT = 1
        repeat(6) { out.write(0x00) }      // ANCOUNT/NSCOUNT/ARCOUNT = 0
        for (label in hostname.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes, 0, bytes.size)
        }
        out.write(0x00)                    // root label
        out.write(0x00); out.write(0x01)   // QTYPE = A
        out.write(0x00); out.write(0x01)   // QCLASS = IN
        return out.toByteArray()
    }

    /** Extract all A-record IPs from a DNS wireformat response. */
    private fun parseDnsAnswers(msg: ByteArray): List<String> {
        val ips = mutableListOf<String>()
        try {
            if (msg.size < 12) return ips
            val qdCount = ((msg[4].toInt() and 0xFF) shl 8) or (msg[5].toInt() and 0xFF)
            val anCount = ((msg[6].toInt() and 0xFF) shl 8) or (msg[7].toInt() and 0xFF)
            var p = 12
            fun skipName() {
                while (p < msg.size) {
                    val b = msg[p].toInt() and 0xFF
                    when {
                        b == 0 -> { p += 1; return }
                        b >= 0xC0 -> { p += 2; return }  // compression pointer
                        else -> p += 1 + b
                    }
                }
            }
            repeat(qdCount) { skipName(); p += 4 }       // QTYPE + QCLASS
            repeat(anCount) {
                skipName()
                if (p + 10 > msg.size) return ips
                val type = ((msg[p].toInt() and 0xFF) shl 8) or (msg[p + 1].toInt() and 0xFF)
                val rdLen = ((msg[p + 8].toInt() and 0xFF) shl 8) or (msg[p + 9].toInt() and 0xFF)
                p += 10
                if (type == 1 && rdLen == 4 && p + 4 <= msg.size) {
                    ips.add((0..3).joinToString(".") { i -> (msg[p + i].toInt() and 0xFF).toString() })
                }
                p += rdLen
            }
        } catch (_: Exception) { /* truncated/malformed — return what we have */ }
        return ips
    }

    // ---------------------------------------------------------------------
    // Bulk pre-resolution of proxy hostnames (the urltest bootstrap fix).
    // sing-box has NO failover between DNS servers — a query goes to the one
    // server picked by rules, and if that server is blocked the resolution
    // just fails. So the fallback CHAIN lives here at the app level:
    // BoxService pre-resolves every outbound hostname through DOH_ENDPOINTS
    // and injects the answers into the config as a `hosts` DNS server, making
    // urltest independent of any single resolver's liveness.
    // System DNS is deliberately NOT used here: RKN poisons plaintext DNS,
    // and a poisoned IP baked into `hosts` would be worse than no answer.
    // ---------------------------------------------------------------------

    private const val DNS_CACHE_PREFS = "dns_prefetch"
    private const val DNS_FRESH_MS = 6L * 3600 * 1000  // 6h: skips re-resolving on every reconnect

    /**
     * Resolve [hosts] via the DoH chain, in parallel, within [budgetMs].
     * Fresh cache hits skip the network; failures fall back to the last
     * known-good answer regardless of age (a stale server IP usually still
     * works — VPN server IPs rarely move — while a blocked resolver returns
     * nothing at all). Never throws; returns whatever subset resolved.
     */
    fun preResolveHosts(context: Context, hosts: List<String>, budgetMs: Long = 6000): Map<String, List<String>> {
        val unique = hosts.distinct()
        if (unique.isEmpty()) return emptyMap()
        val prefs = context.getSharedPreferences(DNS_CACHE_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val result = HashMap<String, List<String>>()

        fun cached(host: String): Pair<List<String>, Long>? {
            val raw = prefs.getString(host, null) ?: return null
            val ips = raw.substringBefore('|').split(',').filter { it.isNotBlank() }
            val ts = raw.substringAfter('|', "0").toLongOrNull() ?: 0L
            return if (ips.isEmpty()) null else ips to ts
        }

        val toResolve = mutableListOf<String>()
        for (host in unique) {
            val c = cached(host)
            if (c != null && now - c.second < DNS_FRESH_MS) result[host] = c.first
            else toResolve.add(host)
        }
        if (toResolve.isEmpty()) return result

        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(4, toResolve.size))
        try {
            val futures = toResolve.map { host ->
                host to pool.submit<List<String>> { resolveAllViaDoH(host) }
            }
            val deadline = now + budgetMs
            for ((host, future) in futures) {
                val left = deadline - System.currentTimeMillis()
                try {
                    val ips = future.get(maxOf(left, 100), java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (ips.isNotEmpty()) {
                        result[host] = ips
                        prefs.edit().putString(host, ips.joinToString(",") + "|" + now).apply()
                    }
                } catch (_: Exception) { /* timeout — stale fallback below */ }
            }
        } finally {
            pool.shutdownNow()
        }
        // Stale fallback for anything the chain could not resolve in budget.
        for (host in toResolve) {
            if (host !in result) cached(host)?.let { result[host] = it.first }
        }
        android.util.Log.i("HwidService", "preResolveHosts: ${result.size}/${unique.size} resolved (${toResolve.size} queried)")
        return result
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
