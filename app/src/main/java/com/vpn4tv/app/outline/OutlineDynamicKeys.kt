package com.vpn4tv.app.outline

import android.util.Base64
import android.util.Log
import com.vpn4tv.app.utils.HTTPClient
import org.json.JSONObject
import java.io.File

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
 */
object OutlineDynamicKeys {

    private const val TAG = "OutlineDynamicKeys"

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
     * Fetch every dynamic key and return the updated sidecar, or the original
     * when nothing changed. Never throws: a failure keeps the previous key.
     */
    fun resolve(sidecarJson: String): String {
        val root = try {
            JSONObject(sidecarJson)
        } catch (error: Exception) {
            Log.w(TAG, "sidecar is not JSON: ${error.message}")
            return sidecarJson
        }
        val endpoints = root.optJSONArray("endpoints") ?: return sidecarJson

        var changed = false
        for (index in 0 until endpoints.length()) {
            val endpoint = endpoints.optJSONObject(index) ?: continue
            val dynamicUrl = endpoint.optString("dynamicUrl")
            if (dynamicUrl.isNullOrEmpty()) continue

            val url = fetchKey(dynamicUrl)
            if (url == null) {
                val previous = endpoint.optString("url")
                Log.w(
                    TAG,
                    if (previous.isNullOrEmpty()) "no key yet for $dynamicUrl"
                    else "keeping the previous key for $dynamicUrl"
                )
                continue
            }
            if (url != endpoint.optString("url")) {
                endpoint.put("url", url)
                changed = true
            }
        }
        return if (changed) root.toString() else sidecarJson
    }

    /** Resolve in place and persist, so the next connect starts from what worked. */
    fun resolveAndPersist(sidecar: File): String {
        val original = sidecar.readText()
        if (!hasDynamicKeys(original)) return original
        val resolved = resolve(original)
        if (resolved != original) {
            try {
                sidecar.writeText(resolved)
            } catch (error: Exception) {
                Log.w(TAG, "could not persist the resolved key: ${error.message}")
            }
        }
        return resolved
    }

    private fun fetchKey(url: String): String? = try {
        // libbox's client: modern TLS fingerprint and redirects handled for us,
        // which matters when the key host is the thing being blocked.
        HTTPClient().use { client ->
            toShadowsocksUrl(JSONObject(client.getString(url)), java.net.URI(url).host ?: "Outline")
        }
    } catch (error: Exception) {
        Log.w(TAG, "fetching $url failed: ${error.message}")
        null
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
