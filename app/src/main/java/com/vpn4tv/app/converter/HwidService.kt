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
)

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
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("x-hwid", getHwid(context))
        conn.setRequestProperty("x-device-os", "Android")
        conn.setRequestProperty("x-ver-os", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        conn.setRequestProperty("User-Agent", "VPN4TV-Native/0.1.0")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        val body = conn.inputStream.bufferedReader().readText()
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
