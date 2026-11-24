package com.vpn4tv.app.outline

import android.util.Base64
import android.util.Log
import com.vpn4tv.app.utils.LogPrivacy
import com.vpn4tv.app.utils.HTTPClient
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Outline dynamic access keys (ssconf://).
 *
 * The provider rotates servers behind such a key, so resolving it once — which
 * the backend used to do — hands the user a snapshot that goes stale. Outline's
 * own clients fetch the key before connecting; so do we, right before the
 * bridge starts.
 *
 * The resolved URL is written back into the sidecar, so a key host that is
 * blocked or down falls back to the last key that worked instead of leaving the
 * user with a dead endpoint.
 *
 * Support case of 2026-11-24 (a Xiaomi TV in Russia, 5.6.7): the key's host was
 * frozen at the TLS handshake by most Russian home ISPs, the key had never been
 * fetched, so its server was left without a bridge. The app said "Connected",
 * the core's every connection was refused at the bridge's port, and nothing in
 * the report said why. Each key's fate is now an [Outcome] the service writes
 * for support and acts on.
 */
object OutlineDynamicKeys {

    private const val TAG = "OutlineDynamicKeys"

    /** How long a start waits for a key when the last one that worked is there to fall back on. */
    const val WAIT_WITH_EARLIER_MS = 6_000L

    /**
     * How long it waits when there is none. The client gives the TLS handshake
     * 15 s (sing-box's C.TCPTimeout) and the TCP connect no limit of its own.
     */
    const val WAIT_WITHOUT_EARLIER_MS = 20_000L

    enum class State {
        /** The key came (the same as before or a new one). */
        FETCHED,

        /** It did not; the last one that worked is used. */
        KEPT,

        /** It did not, and none ever did: the endpoint has no server. */
        NONE,
    }

    /**
     * What became of one dynamic key. [host] is the key's host (never its
     * path, which is the secret); [port] is the endpoint's own, the one the
     * core's socks outbound dials on 127.0.0.127; [reason] says why it was not
     * fetched; [changed] is true when a fetched key differs from the one kept.
     */
    class Outcome(val host: String, val port: Int, val state: State, val reason: String?, val changed: Boolean)

    /** The sidecar with every key that came filled in, and what became of each dynamic key. */
    class Resolution(val sidecar: String, val outcomes: List<Outcome>)

    /** One fetch: the key as a SIP002 link, or why there is none. */
    class Fetch(val url: String?, val error: String?)

    private val pool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "outline-key").apply { isDaemon = true }
    }

    /** True when the sidecar carries at least one key that needs fetching. */
    fun hasDynamicKeys(sidecarJson: String): Boolean = try {
        val endpoints = JSONObject(sidecarJson).optJSONArray("endpoints")
        (0 until (endpoints?.length() ?: 0)).any { index ->
            !endpoints!!.getJSONObject(index).optString("dynamicUrl").isNullOrEmpty()
        }
    } catch (_: Exception) {
        false
    }

    /**
     * Fetch the dynamic keys (of the endpoints on [onlyPorts], or all of them)
     * at once, each waited for [waitWithEarlierMs] when it has a key that
     * worked before and [waitWithoutEarlierMs] when it has none. Never throws:
     * a key that does not come keeps the previous one. A fetch that runs over
     * its time is left to finish on its own; its answer is not used.
     */
    fun resolve(
        sidecarJson: String,
        onlyPorts: Set<Int>? = null,
        waitWithEarlierMs: Long = WAIT_WITH_EARLIER_MS,
        waitWithoutEarlierMs: Long = WAIT_WITHOUT_EARLIER_MS,
        fetch: (String) -> Fetch = ::fetchKey,
    ): Resolution {
        val root = try {
            JSONObject(sidecarJson)
        } catch (error: Exception) {
            Log.w(TAG, "sidecar is not JSON: ${LogPrivacy.error(error)}")
            return Resolution(sidecarJson, emptyList())
        }
        val endpoints = root.optJSONArray("endpoints") ?: return Resolution(sidecarJson, emptyList())

        class Job(val endpoint: JSONObject, val dynamicUrl: String, val previous: String, val future: Future<Fetch>)

        val jobs = ArrayList<Job>()
        for (index in 0 until endpoints.length()) {
            val endpoint = endpoints.optJSONObject(index) ?: continue
            val dynamicUrl = endpoint.optString("dynamicUrl")
            if (dynamicUrl.isNullOrEmpty()) continue
            if (onlyPorts != null && endpoint.optInt("port") !in onlyPorts) continue
            jobs.add(Job(endpoint, dynamicUrl, endpoint.optString("url").orEmpty().trim(), pool.submit<Fetch> { fetch(dynamicUrl) }))
        }

        val startedAt = System.nanoTime()
        var changed = false
        val outcomes = ArrayList<Outcome>()
        for (job in jobs) {
            val limit = if (job.previous.isEmpty()) waitWithoutEarlierMs else waitWithEarlierMs
            val left = limit - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            val result = try {
                job.future.get(left.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                job.future.cancel(true)
                Fetch(null, "no answer in ${limit / 1000} s")
            } catch (error: ExecutionException) {
                Fetch(null, shortReason(error.cause?.message))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                Fetch(null, "interrupted")
            }
            val host = host(job.dynamicUrl)
            val port = job.endpoint.optInt("port")
            val url = result.url
            if (url == null) {
                val state = if (job.previous.isEmpty()) State.NONE else State.KEPT
                Log.w(TAG, "${LogPrivacy.link(job.dynamicUrl)}: ${result.error}; ${if (state == State.NONE) "no key yet" else "keeping the previous key"}")
                outcomes.add(Outcome(host, port, state, result.error ?: "unknown error", changed = false))
                continue
            }
            val differs = url != job.previous
            if (differs) {
                job.endpoint.put("url", url)
                changed = true
            }
            outcomes.add(Outcome(host, port, State.FETCHED, null, differs))
        }
        return Resolution(if (changed) root.toString() else sidecarJson, outcomes)
    }

    /**
     * The sidecar without the endpoints that still have no key: a dynamic key
     * that could not be fetched and never was before. Null when none is left.
     * The core takes an empty url for no Shadowsocks at all, i.e. a plain
     * direct dialer, so such an endpoint would carry traffic past the VPN with
     * the real address, and 'auto' tends to pick it, the direct path being the
     * fastest. Without its listener the socks outbound is refused and urltest
     * marks it dead. Every endpoint names its own port, so dropping one moves
     * no other.
     */
    fun dropUnresolved(sidecarJson: String): String? {
        val root = try {
            JSONObject(sidecarJson)
        } catch (_: Exception) {
            return sidecarJson
        }
        val endpoints = root.optJSONArray("endpoints") ?: return sidecarJson
        val usable = org.json.JSONArray()
        for (index in 0 until endpoints.length()) {
            val endpoint = endpoints.optJSONObject(index) ?: continue
            if (endpoint.optString("url").isBlank()) {
                Log.w(TAG, "endpoint on port ${endpoint.optInt("port")} has no key yet, left out")
            } else {
                usable.put(endpoint)
            }
        }
        if (usable.length() == 0) return null
        if (usable.length() == endpoints.length()) return sidecarJson
        root.put("endpoints", usable)
        return root.toString()
    }

    /** Resolve in place and persist, so the next connect starts from what worked. */
    fun resolveAndPersist(sidecar: File): Resolution {
        val original = sidecar.readText()
        if (!hasDynamicKeys(original)) return Resolution(original, emptyList())
        val resolution = resolve(original)
        if (resolution.sidecar != original) persist(sidecar, resolution.sidecar)
        return resolution
    }

    fun persist(sidecar: File, text: String) {
        try {
            sidecar.writeText(text)
        } catch (error: Exception) {
            Log.w(TAG, "could not persist the resolved key: ${LogPrivacy.error(error)}")
        }
    }

    /** The key's host for the lines support reads: "ssconf://keys.example/abc" → "keys.example". */
    internal fun host(dynamicUrl: String): String = try {
        java.net.URI(dynamicUrl).host
    } catch (_: Exception) {
        null
    } ?: "?"

    private val goRequestPrefix = Regex("""^[A-Z][a-z]+ "[^"]*": """)

    /**
     * Why a fetch failed, short and without the link. Go's net/http says
     * `Get "<link>": net/http: TLS handshake timeout`, and the link is the key;
     * libbox says "HTTP 404 Not Found: <the body>" for an answer that is not 200.
     * Addresses go as in the rest of a report.
     */
    internal fun shortReason(message: String?): String {
        var text = message.orEmpty().replace('\n', ' ').trim()
        text = goRequestPrefix.replace(text, "")
        if (text.startsWith("HTTP ")) text = text.substringBefore(": ")
        // "read tcp <device>:<port>-><server>:443: …": the device's own address can be a public one.
        return com.vpn4tv.app.utils.SupportReport.withoutAddresses(LogPrivacy.redactLinks(text)).take(160)
            .ifEmpty { "unknown error" }
    }

    private fun fetchKey(url: String): Fetch = try {
        // libbox's client: modern TLS fingerprint and redirects handled for us,
        // which matters when the key host is the thing being blocked.
        HTTPClient().use { client ->
            val body = client.getString(url)
            val key = try {
                JSONObject(body)
            } catch (_: Exception) {
                null
            }
            if (key == null) {
                Fetch(null, "the answer is not a key")
            } else {
                toShadowsocksUrl(key, java.net.URI(url).host ?: "Outline")
                    ?.let { Fetch(it, null) }
                    ?: Fetch(null, "the key has no server, port, password or method")
            }
        }
    } catch (error: Exception) {
        Fetch(null, shortReason(error.message))
    }

    /** SIP002, the shape the bridge already takes. */
    private fun toShadowsocksUrl(key: JSONObject, name: String): String? {
        val server = key.optString("server")
        val port = key.optInt("server_port")
        val password = key.optString("password")
        val method = key.optString("method")
        if (server.isNullOrEmpty() || port == 0 || password.isNullOrEmpty() || method.isNullOrEmpty()) {
            Log.w(TAG, "dynamic key is missing required fields")
            return null
        }
        val userInfo = Base64.encodeToString(
            "$method:$password".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP or Base64.NO_PADDING,
        )
        val host = if (server.contains(':')) "[$server]" else server
        val prefix = key.optString("prefix")
        val query = if (prefix.isNullOrEmpty()) "" else
            "?prefix=" + java.net.URLEncoder.encode(prefix, "UTF-8")
        val tag = java.net.URLEncoder.encode(name, "UTF-8")
        return "ss://$userInfo@$host:$port$query#$tag"
    }
}
